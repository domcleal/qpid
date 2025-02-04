/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
#include "ConnectionImpl.h"
#include "SessionImpl.h"
#include "qpid/messaging/exceptions.h"
#include "qpid/messaging/Session.h"
#include "qpid/messaging/PrivateImplRef.h"
#include "qpid/framing/Uuid.h"
#include "qpid/log/Statement.h"
#include "qpid/Url.h"
#include <boost/intrusive_ptr.hpp>
#include <vector>
#include <sstream>

namespace qpid {
namespace client {
namespace amqp0_10 {

using qpid::types::Variant;
using qpid::types::VAR_LIST;
using qpid::framing::Uuid;

namespace {
void merge(const std::string& value, std::vector<std::string>& list) {
    if (std::find(list.begin(), list.end(), value) == list.end())
        list.push_back(value);
}

void merge(const Variant::List& from, std::vector<std::string>& to)
{
    for (Variant::List::const_iterator i = from.begin(); i != from.end(); ++i)
        merge(i->asString(), to);
}

std::string asString(const std::vector<std::string>& v) {
    std::stringstream os;
    os << "[";
    for(std::vector<std::string>::const_iterator i = v.begin(); i != v.end(); ++i ) {
        if (i != v.begin()) os << ", ";
        os << *i;
    }
    os << "]";
    return os.str();
}
}

ConnectionImpl::ConnectionImpl(const std::string& url, const Variant::Map& options) :
    replaceUrls(false), reconnect(false), timeout(-1), limit(-1),
    minReconnectInterval(3), maxReconnectInterval(60),
    retries(0), reconnectOnLimitExceeded(true)
{
    setOptions(options);
    urls.insert(urls.begin(), url);
    QPID_LOG(debug, "Created connection " << url << " with " << options);
}

void ConnectionImpl::setOptions(const Variant::Map& options)
{
    for (Variant::Map::const_iterator i = options.begin(); i != options.end(); ++i) {
        setOption(i->first, i->second);
    }
}

void ConnectionImpl::setOption(const std::string& name, const Variant& value)
{
    sys::Mutex::ScopedLock l(lock);
    if (name == "reconnect") {
        reconnect = value;
    } else if (name == "reconnect-timeout" || name == "reconnect_timeout") {
        timeout = value;
    } else if (name == "reconnect-limit" || name == "reconnect_limit") {
        limit = value;
    } else if (name == "reconnect-interval" || name == "reconnect_interval") {
        maxReconnectInterval = minReconnectInterval = value;
    } else if (name == "reconnect-interval-min" || name == "reconnect_interval_min") {
        minReconnectInterval = value;
    } else if (name == "reconnect-interval-max" || name == "reconnect_interval_max") {
        maxReconnectInterval = value;
    } else if (name == "reconnect-urls-replace" || name == "reconnect_urls_replace") {
        replaceUrls = value.asBool();
    } else if (name == "reconnect-urls" || name == "reconnect_urls") {
        if (replaceUrls) urls.clear();
        if (value.getType() == VAR_LIST) {
            merge(value.asList(), urls);
        } else {
            merge(value.asString(), urls);
        }
    } else if (name == "username") {
        settings.username = value.asString();
    } else if (name == "password") {
        settings.password = value.asString();
    } else if (name == "sasl-mechanism" || name == "sasl_mechanism" ||
               name == "sasl-mechanisms" || name == "sasl_mechanisms") {
        settings.mechanism = value.asString();
    } else if (name == "sasl-service" || name == "sasl_service") {
        settings.service = value.asString();
    } else if (name == "sasl-min-ssf" || name == "sasl_min_ssf") {
        settings.minSsf = value;
    } else if (name == "sasl-max-ssf" || name == "sasl_max_ssf") {
        settings.maxSsf = value;
    } else if (name == "heartbeat") {
        settings.heartbeat = value;
    } else if (name == "tcp-nodelay" || name == "tcp_nodelay") {
        settings.tcpNoDelay = value;
    } else if (name == "locale") {
        settings.locale = value.asString();
    } else if (name == "max-channels" || name == "max_channels") {
        settings.maxChannels = value;
    } else if (name == "max-frame-size" || name == "max_frame_size") {
        settings.maxFrameSize = value;
    } else if (name == "bounds") {
        settings.bounds = value;
    } else if (name == "transport") {
        settings.protocol = value.asString();
    } else if (name == "ssl-cert-name" || name == "ssl_cert_name") {
        settings.sslCertName = value.asString();
    } else if (name == "x-reconnect-on-limit-exceeded" || name == "x_reconnect_on_limit_exceeded") {
        reconnectOnLimitExceeded = value;
    } else {
        throw qpid::messaging::MessagingException(QPID_MSG("Invalid option: " << name << " not recognised"));
    }
}


void ConnectionImpl::close()
{
    while(true) {
        messaging::Session session;
        {
            qpid::sys::Mutex::ScopedLock l(lock);
            if (sessions.empty()) break;
            session = sessions.begin()->second;
        }
        session.close();
    }
    detach();
}

void ConnectionImpl::detach()
{
    qpid::sys::Mutex::ScopedLock l(lock);
    connection.close();
}

bool ConnectionImpl::isOpen() const
{
    qpid::sys::Mutex::ScopedLock l(lock);
    return connection.isOpen();
}

boost::intrusive_ptr<SessionImpl> getImplPtr(qpid::messaging::Session& session)
{
    return boost::dynamic_pointer_cast<SessionImpl>(
        qpid::messaging::PrivateImplRef<qpid::messaging::Session>::get(session)
    );
}

void ConnectionImpl::closed(SessionImpl& s)
{
    qpid::sys::Mutex::ScopedLock l(lock);
    for (Sessions::iterator i = sessions.begin(); i != sessions.end(); ++i) {
        if (getImplPtr(i->second).get() == &s) {
            sessions.erase(i);
            break;
        }
    }
}

qpid::messaging::Session ConnectionImpl::getSession(const std::string& name) const
{
    qpid::sys::Mutex::ScopedLock l(lock);
    Sessions::const_iterator i = sessions.find(name);
    if (i == sessions.end()) {
        throw qpid::messaging::KeyError("No such session: " + name);
    } else {
        return i->second;
    }
}

qpid::messaging::Session ConnectionImpl::newSession(bool transactional, const std::string& n)
{
    std::string name = n.empty() ? Uuid(true).str() : n;
    qpid::messaging::Session impl(new SessionImpl(*this, transactional));
    while (true) {
        try {
            getImplPtr(impl)->setSession(connection.newSession(name));
            qpid::sys::Mutex::ScopedLock l(lock);
            sessions[name] = impl;
            break;
        } catch (const qpid::TransportFailure&) {
            reopen();
        } catch (const qpid::SessionException& e) {
            throw qpid::messaging::SessionError(e.what());
        } catch (const std::exception& e) {
            throw qpid::messaging::MessagingException(e.what());
        }
    }
    return impl;
}

void ConnectionImpl::open()
{
    qpid::sys::AbsTime start = qpid::sys::now();
    qpid::sys::ScopedLock<qpid::sys::Semaphore> l(semaphore);
    try {
        if (!connection.isOpen()) connect(start);
    }
    catch (const types::Exception&) { throw; }
    catch (const qpid::Exception& e) { throw messaging::ConnectionError(e.what()); }
}

void ConnectionImpl::reopen()
{
    if (!reconnect) {
        throw qpid::messaging::TransportFailure("Failed to connect (reconnect disabled)");
    }
    open();
}


bool expired(const qpid::sys::AbsTime& start, int64_t timeout)
{
    if (timeout == 0) return true;
    if (timeout < 0) return false;
    qpid::sys::Duration used(start, qpid::sys::now());
    qpid::sys::Duration allowed = timeout * qpid::sys::TIME_SEC;
    return allowed < used;
}

void ConnectionImpl::connect(const qpid::sys::AbsTime& started)
{
    for (int64_t i = minReconnectInterval; !tryConnect(); i = std::min(i * 2, maxReconnectInterval)) {
        if (!reconnect) {
            throw qpid::messaging::TransportFailure("Failed to connect (reconnect disabled)");
        }
        if (limit >= 0 && retries++ >= limit) {
            throw qpid::messaging::TransportFailure("Failed to connect within reconnect limit");
        }
        if (expired(started, timeout)) {
            throw qpid::messaging::TransportFailure("Failed to connect within reconnect timeout");
        }
        else qpid::sys::sleep(i);
    }
    retries = 0;
}

void ConnectionImpl::mergeUrls(const std::vector<Url>& more, const sys::Mutex::ScopedLock&) {
    for (std::vector<Url>::const_iterator i = more.begin(); i != more.end(); ++i)
        merge(i->str(), urls);
    QPID_LOG(debug, "Added known-hosts, reconnect-urls=" << asString(urls));
}

bool ConnectionImpl::tryConnect()
{
    sys::Mutex::ScopedLock l(lock);
    for (std::vector<std::string>::const_iterator i = urls.begin(); i != urls.end(); ++i) {
        try {
            QPID_LOG(info, "Trying to connect to " << *i << "...");
            Url url(*i);
            if (url.getUser().size()) settings.username = url.getUser();
            if (url.getPass().size()) settings.password = url.getPass();
            connection.open(url, settings);
            QPID_LOG(info, "Connected to " << *i);
            mergeUrls(connection.getInitialBrokers(), l);
            return resetSessions(l);
        } catch (const qpid::TransportFailure& e) {
            QPID_LOG(info, "Failed to connect to " << *i << ": " << e.what());
        }
    }
    return false;
}

bool ConnectionImpl::resetSessions(const sys::Mutex::ScopedLock& )
{
    try {
        qpid::sys::Mutex::ScopedLock l(lock);
        for (Sessions::iterator i = sessions.begin(); i != sessions.end(); ++i) {
            getImplPtr(i->second)->setSession(connection.newSession(i->first));
        }
        return true;
    } catch (const qpid::TransportFailure&) {
        QPID_LOG(debug, "Connection failed while re-initialising sessions");
        return false;
    } catch (const qpid::framing::ResourceLimitExceededException& e) {
        if (reconnectOnLimitExceeded) {
            QPID_LOG(debug, "Detaching and reconnecting due to: " << e.what());
            detach();
            return false;
        } else {
            throw qpid::messaging::TargetCapacityExceeded(e.what());
        }
    }
}

bool ConnectionImpl::backoff()
{
    if (reconnectOnLimitExceeded) {
        detach();
        open();
        return true;
    } else {
        return false;
    }
}
std::string ConnectionImpl::getAuthenticatedUsername()
{
    return connection.getNegotiatedSettings().username;
}

}}} // namespace qpid::client::amqp0_10

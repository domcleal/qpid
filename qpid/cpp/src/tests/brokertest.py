#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

# Support library for tests that start multiple brokers, e.g. cluster
# or federation

import os, signal, string, tempfile, subprocess, socket, threading, time, imp, re
import qpid, traceback, signal
from qpid import connection, messaging, util
from qpid.compat import format_exc
from qpid.harness import Skipped
from unittest import TestCase
from copy import copy
from threading import Thread, Lock, Condition
from logging import getLogger
import qmf.console

log = getLogger("qpid.brokertest")

# Values for expected outcome of process at end of test
EXPECT_EXIT_OK=1           # Expect to exit with 0 status before end of test.
EXPECT_EXIT_FAIL=2         # Expect to exit with non-0 status before end of test.
EXPECT_RUNNING=3           # Expect to still be running at end of test
EXPECT_UNKNOWN=4            # No expectation, don't check exit status.

def find_exe(program):
    """Find an executable in the system PATH"""
    def is_exe(fpath):
        return os.path.isfile(fpath) and os.access(fpath, os.X_OK)
    mydir, name = os.path.split(program)
    if mydir:
        if is_exe(program): return program
    else:
        for path in os.environ["PATH"].split(os.pathsep):
            exe_file = os.path.join(path, program)
            if is_exe(exe_file): return exe_file
    return None

def is_running(pid):
    try:
        os.kill(pid, 0)
        return True
    except:
        return False

class BadProcessStatus(Exception):
    pass

def error_line(filename, n=1):
    """Get the last n line(s) of filename for error messages"""
    result = []
    try:
        f = open(filename)
        try:
            for l in f:
                if len(result) == n:  result.pop(0)
                result.append("    "+l)
        finally:
            f.close()
    except: return ""
    return ":\n" + "".join(result)

def retry(function, timeout=10, delay=.01):
    """Call function until it returns True or timeout expires.
    Double the delay for each retry. Return True if function
    returns true, False if timeout expires."""
    deadline = time.time() + timeout
    while not function():
        remaining = deadline - time.time()
        if remaining <= 0: return False
        delay = min(delay, remaining)
        time.sleep(delay)
        delay *= 2
    return True

class AtomicCounter:
    def __init__(self):
        self.count = 0
        self.lock = Lock()

    def next(self):
        self.lock.acquire();
        ret = self.count
        self.count += 1
        self.lock.release();
        return ret

_popen_id = AtomicCounter() # Popen identifier for use in output file names.

# Constants for file descriptor arguments to Popen
FILE = "FILE"                       # Write to file named after process
PIPE = subprocess.PIPE

class Popen(subprocess.Popen):
    """
    Can set and verify expectation of process status at end of test.
    Dumps command line, stdout, stderr to data dir for debugging.
    """

    def __init__(self, cmd, expect=EXPECT_EXIT_OK, stdin=None, stdout=FILE, stderr=FILE):
        """Run cmd (should be a list of program and arguments)
        expect - if set verify expectation at end of test.
        stdout, stderr - can have the same values as for subprocess.Popen as well as
          FILE (the default) which means write to a file named after the process.
        stdin - like subprocess.Popen but defauts to PIPE
        """
        self._clean = False
        self._clean_lock = Lock()
        assert find_exe(cmd[0]), "executable not found: "+cmd[0]
        if type(cmd) is type(""): cmd = [cmd] # Make it a list.
        self.cmd  = [ str(x) for x in cmd ]
        self.expect = expect
        self.id = _popen_id.next()
        self.pname = "%s-%d" % (os.path.split(self.cmd[0])[1], self.id)
        if stdout == FILE: stdout = open(self.outfile("out"), "w")
        if stderr == FILE: stderr = open(self.outfile("err"), "w")
        try:
            subprocess.Popen.__init__(self, self.cmd, bufsize=0, executable=None,
                                      stdin=stdin, stdout=stdout, stderr=stderr,
                                      close_fds=True)
        except ValueError: # Windows can't do close_fds
            subprocess.Popen.__init__(self, self.cmd, bufsize=0, executable=None,
                                      stdin=stdin, stdout=stdout, stderr=stderr)

        f = open(self.outfile("cmd"), "w")
        try: f.write("%s\n%d"%(self.cmd_str(), self.pid))
        finally: f.close()
        log.debug("Started process %s: %s" % (self.pname, " ".join(self.cmd)))

    def __str__(self): return "Popen<%s>"%(self.pname)

    def outfile(self, ext): return "%s.%s" % (self.pname, ext)

    def unexpected(self,msg):
        err = error_line(self.outfile("err")) or error_line(self.outfile("out"))
        raise BadProcessStatus("%s %s%s" % (self.pname, msg, err))

    def stop(self):                  # Clean up at end of test.
        try:
            if self.expect == EXPECT_UNKNOWN:
                try: self.kill()            # Just make sure its dead
                except: pass
            elif self.expect == EXPECT_RUNNING:
                    if self.poll() != None:
                        self.unexpected("expected running, exit code %d" % self.returncode)
                    else:
                        try:
                            self.kill()
                        except Exception,e:
                            self.unexpected("exception from kill: %s" % str(e))
            else:
                retry(lambda: self.poll() is not None)
                if self.returncode is None: # Still haven't stopped
                    self.kill()
                    self.unexpected("still running")
                elif self.expect == EXPECT_EXIT_OK and self.returncode != 0:
                    self.unexpected("exit code %d" % self.returncode)
                elif self.expect == EXPECT_EXIT_FAIL and self.returncode == 0:
                    self.unexpected("expected error")
        finally:
            self.wait()                 # Clean up the process.

    def communicate(self, input=None):
        ret = subprocess.Popen.communicate(self, input)
        self.cleanup()
        return ret

    def is_running(self): return self.poll() is None

    def assert_running(self):
        if not self.is_running(): self.unexpected("Exit code %d" % self.returncode)

    def wait(self):
        ret = subprocess.Popen.wait(self)
        self._cleanup()
        return ret

    def terminate(self):
        try: subprocess.Popen.terminate(self)
        except AttributeError:          # No terminate method
            try:
                os.kill( self.pid , signal.SIGTERM)
            except AttributeError: # no os.kill, using taskkill.. (Windows only)
                os.popen('TASKKILL /PID ' +str(self.pid) + ' /F')
        self._cleanup()

    def kill(self):
        try: subprocess.Popen.kill(self)
        except AttributeError:          # No terminate method
            try:
                os.kill( self.pid , signal.SIGKILL)
            except AttributeError: # no os.kill, using taskkill.. (Windows only)
                os.popen('TASKKILL /PID ' +str(self.pid) + ' /F')
        self._cleanup()

    def _cleanup(self):
        """Clean up after a dead process"""
        self._clean_lock.acquire()
        if not self._clean:
            self._clean = True
            try: self.stdin.close()
            except: pass
            try: self.stdout.close()
            except: pass
            try: self.stderr.close()
            except: pass
        self._clean_lock.release()

    def cmd_str(self): return " ".join([str(s) for s in self.cmd])

def checkenv(name):
    value = os.getenv(name)
    if not value: raise Exception("Environment variable %s is not set" % name)
    return value

def find_in_file(str, filename):
    if not os.path.exists(filename): return False
    f = open(filename)
    try: return str in f.read()
    finally: f.close()

class Broker(Popen):
    "A broker process. Takes care of start, stop and logging."
    _broker_count = 0

    def __str__(self): return "Broker<%s %s>"%(self.name, self.pname)

    def find_log(self):
        self.log = "%s.log" % self.name
        i = 1
        while (os.path.exists(self.log)):
            self.log = "%s-%d.log" % (self.name, i)
            i += 1

    def get_log(self):
        return os.path.abspath(self.log)

    def __init__(self, test, args=[], name=None, expect=EXPECT_RUNNING, port=0, log_level=None, wait=None, show_cmd=False):
        """Start a broker daemon. name determines the data-dir and log
        file names."""

        self.test = test
        self._port=port
        if BrokerTest.store_lib:
            args = args + ['--load-module', BrokerTest.store_lib]
            if BrokerTest.sql_store_lib:
                args = args + ['--load-module', BrokerTest.sql_store_lib]
                args = args + ['--catalog', BrokerTest.sql_catalog]
            if BrokerTest.sql_clfs_store_lib:
                args = args + ['--load-module', BrokerTest.sql_clfs_store_lib]
                args = args + ['--catalog', BrokerTest.sql_catalog]
        cmd = [BrokerTest.qpidd_exec, "--port", port, "--no-module-dir"] + args
        if not "--auth" in args: cmd.append("--auth=no")
        if wait != None:
            cmd += ["--wait", str(wait)]
        if name: self.name = name
        else:
            self.name = "broker%d" % Broker._broker_count
            Broker._broker_count += 1
        self.find_log()
        cmd += ["--log-to-file", self.log]
        cmd += ["--log-to-stderr=no"]
        if log_level != None:
            cmd += ["--log-enable=%s" % log_level]
        self.datadir = self.name
        cmd += ["--data-dir", self.datadir]
        if show_cmd: print cmd
        Popen.__init__(self, cmd, expect, stdout=PIPE)
        test.cleanup_stop(self)
        self._host = "127.0.0.1"
        log.debug("Started broker %s (%s, %s)" % (self.name, self.pname, self.log))
        self._log_ready = False

    def startQmf(self, handler=None):
        self.qmf_session = qmf.console.Session(handler)
        self.qmf_broker = self.qmf_session.addBroker("%s:%s" % (self.host(), self.port()))

    def host(self): return self._host

    def port(self):
        # Read port from broker process stdout if not already read.
        if (self._port == 0):
            try: self._port = int(self.stdout.readline())
            except ValueError:
                raise Exception("Can't get port for broker %s (%s)%s" %
                                (self.name, self.pname, error_line(self.log,5)))
        return self._port

    def unexpected(self,msg):
        raise BadProcessStatus("%s: %s (%s)" % (msg, self.name, self.pname))

    def connect(self, **kwargs):
        """New API connection to the broker."""
        return messaging.Connection.establish(self.host_port(), **kwargs)

    def connect_old(self):
        """Old API connection to the broker."""
        socket = qpid.util.connect(self.host(),self.port())
        connection = qpid.connection.Connection (sock=socket)
        connection.start()
        return connection;

    def declare_queue(self, queue):
        c = self.connect_old()
        s = c.session(str(qpid.datatypes.uuid4()))
        s.queue_declare(queue=queue)
        c.close()

    def _prep_sender(self, queue, durable, xprops):
        s = queue + "; {create:always, node:{durable:" + str(durable)
        if xprops != None: s += ", x-declare:{" + xprops + "}"
        return s + "}}"

    def send_message(self, queue, message, durable=True, xprops=None, session=None):
        if session == None:
            s = self.connect().session()
        else:
            s = session
        s.sender(self._prep_sender(queue, durable, xprops)).send(message)
        if session == None:
            s.connection.close()

    def send_messages(self, queue, messages, durable=True, xprops=None, session=None):
        if session == None:
            s = self.connect().session()
        else:
            s = session
        sender = s.sender(self._prep_sender(queue, durable, xprops))
        for m in messages: sender.send(m)
        if session == None:
            s.connection.close()

    def get_message(self, queue):
        s = self.connect().session()
        m = s.receiver(queue+"; {create:always}", capacity=1).fetch(timeout=1)
        s.acknowledge()
        s.connection.close()
        return m

    def get_messages(self, queue, n):
        s = self.connect().session()
        receiver = s.receiver(queue+"; {create:always}", capacity=n)
        m = [receiver.fetch(timeout=1) for i in range(n)]
        s.acknowledge()
        s.connection.close()
        return m

    def host_port(self): return "%s:%s" % (self.host(), self.port())

    def log_ready(self):
        """Return true if the log file exists and contains a broker ready message"""
        if not self._log_ready:
            self._log_ready = find_in_file("notice Broker running", self.log)
        return self._log_ready

    def ready(self, **kwargs):
        """Wait till broker is ready to serve clients"""
        # First make sure the broker is listening by checking the log.
        if not retry(self.log_ready, timeout=60):
            raise Exception(
                "Timed out waiting for broker %s%s"%(self.name, error_line(self.log,5)))
        # Create a connection and a session. For a cluster broker this will
        # return after cluster init has finished.
        try:
            c = self.connect(**kwargs)
            try: c.session()
            finally: c.close()
        except Exception,e: raise RethrownException(
            "Broker %s not responding: (%s)%s"%(self.name,e,error_line(self.log, 5)))

    def store_state(self):
        f = open(os.path.join(self.datadir, "cluster", "store.status"))
        try: uuids = f.readlines()
        finally: f.close()
        null_uuid="00000000-0000-0000-0000-000000000000\n"
        if len(uuids) < 2: return "unknown" # we looked while the file was being updated.
        if uuids[0] == null_uuid: return "empty"
        if uuids[1] == null_uuid: return "dirty"
        return "clean"

class Cluster:
    """A cluster of brokers in a test."""
    # Client connection options for use in failover tests.
    CONNECTION_OPTIONS = "reconnect:true,reconnect-timeout:10,reconnect-urls-replace:true"

    _cluster_count = 0

    def __init__(self, test, count=0, args=[], expect=EXPECT_RUNNING, wait=True, show_cmd=False):
        self.test = test
        self._brokers=[]
        self.name = "cluster%d" % Cluster._cluster_count
        Cluster._cluster_count += 1
        # Use unique cluster name
        self.args = copy(args)
        self.args += [ "--cluster-name", "%s-%s:%d" % (self.name, socket.gethostname(), os.getpid()) ]
        self.args += [ "--log-enable=info+", "--log-enable=debug+:cluster"]
        assert BrokerTest.cluster_lib, "Cannot locate cluster plug-in"
        self.args += [ "--load-module", BrokerTest.cluster_lib ]
        self.start_n(count, expect=expect, wait=wait, show_cmd=show_cmd)

    def start(self, name=None, expect=EXPECT_RUNNING, wait=True, args=[], port=0, show_cmd=False):
        """Add a broker to the cluster. Returns the index of the new broker."""
        if not name: name="%s-%d" % (self.name, len(self._brokers))
        self._brokers.append(self.test.broker(self.args+args, name, expect, wait, port=port, show_cmd=show_cmd))
        return self._brokers[-1]

    def ready(self):
        for b in self: b.ready()

    def start_n(self, count, expect=EXPECT_RUNNING, wait=True, args=[], show_cmd=False):
        for i in range(count): self.start(expect=expect, wait=wait, args=args, show_cmd=show_cmd)

    # Behave like a list of brokers.
    def __len__(self): return len(self._brokers)
    def __getitem__(self,index): return self._brokers[index]
    def __iter__(self): return self._brokers.__iter__()

class BrokerTest(TestCase):
    """
    Tracks processes started by test and kills at end of test.
    Provides a well-known working directory for each test.
    """

    # Environment settings.
    qpidd_exec = os.path.abspath(checkenv("QPIDD_EXEC"))
    cluster_lib = os.getenv("CLUSTER_LIB")
    xml_lib = os.getenv("XML_LIB")
    qpid_config_exec = os.getenv("QPID_CONFIG_EXEC")
    qpid_route_exec = os.getenv("QPID_ROUTE_EXEC")
    receiver_exec = os.getenv("RECEIVER_EXEC")
    sender_exec = os.getenv("SENDER_EXEC")
    sql_store_lib = os.getenv("STORE_SQL_LIB")
    sql_clfs_store_lib = os.getenv("STORE_SQL_CLFS_LIB")
    sql_catalog = os.getenv("STORE_CATALOG")
    store_lib = os.getenv("STORE_LIB")
    test_store_lib = os.getenv("TEST_STORE_LIB")
    rootdir = os.getcwd()

    def configure(self, config): self.config=config

    def setUp(self):
        outdir = self.config.defines.get("OUTDIR") or "brokertest.tmp"
        self.dir = os.path.join(self.rootdir, outdir, self.id())
        os.makedirs(self.dir)
        os.chdir(self.dir)
        self.stopem = []                # things to stop at end of test

    def tearDown(self):
        err = []
        for p in self.stopem:
            try: p.stop()
            except Exception, e: err.append(str(e))
        self.stopem = []                # reset in case more processes start
        os.chdir(self.rootdir)
        if err: raise Exception("Unexpected process status:\n    "+"\n    ".join(err))

    def cleanup_stop(self, stopable):
        """Call thing.stop at end of test"""
        self.stopem.append(stopable)

    def popen(self, cmd, expect=EXPECT_EXIT_OK, stdin=None, stdout=FILE, stderr=FILE):
        """Start a process that will be killed at end of test, in the test dir."""
        os.chdir(self.dir)
        p = Popen(cmd, expect, stdin=stdin, stdout=stdout, stderr=stderr)
        self.cleanup_stop(p)
        return p

    def broker(self, args=[], name=None, expect=EXPECT_RUNNING, wait=True, port=0, log_level=None, show_cmd=False):
        """Create and return a broker ready for use"""
        b = Broker(self, args=args, name=name, expect=expect, port=port, log_level=log_level, show_cmd=show_cmd)
        if (wait):
            try: b.ready()
            except Exception, e:
                raise RethrownException("Failed to start broker %s(%s): %s" % (b.name, b.log, e))
        return b

    def cluster(self, count=0, args=[], expect=EXPECT_RUNNING, wait=True, show_cmd=False):
        """Create and return a cluster ready for use"""
        cluster = Cluster(self, count, args, expect=expect, wait=wait, show_cmd=show_cmd)
        return cluster

    def browse(self, session, queue, timeout=0):
        """Return a list with the contents of each message on queue."""
        r = session.receiver("%s;{mode:browse}"%(queue))
        r.capacity = 100
        try:
            contents = []
            try:
                while True: contents.append(r.fetch(timeout=timeout).content)
            except messaging.Empty: pass
        finally: r.close()
        return contents

    def assert_browse(self, session, queue, expect_contents, timeout=0):
        """Assert that the contents of messages on queue (as retrieved
        using session and timeout) exactly match the strings in
        expect_contents"""
        actual_contents = self.browse(session, queue, timeout)
        self.assertEqual(expect_contents, actual_contents)

def join(thread, timeout=10):
    thread.join(timeout)
    if thread.isAlive(): raise Exception("Timed out joining thread %s"%thread)

class RethrownException(Exception):
    """Captures the stack trace of the current exception to be thrown later"""
    def __init__(self, msg=""):
        Exception.__init__(self, msg+"\n"+format_exc())

class StoppableThread(Thread):
    """
    Base class for threads that do something in a loop and periodically check
    to see if they have been stopped.
    """
    def __init__(self):
        self.stopped = False
        self.error = None
        Thread.__init__(self)

    def stop(self):
        self.stopped = True
        join(self)
        if self.error: raise self.error

class NumberedSender(Thread):
    """
    Thread to run a sender client and send numbered messages until stopped.
    """

    def __init__(self, broker, max_depth=None, queue="test-queue",
                 connection_options=Cluster.CONNECTION_OPTIONS):
        """
        max_depth: enable flow control, ensure sent - received <= max_depth.
        Requires self.notify_received(n) to be called each time messages are received.
        """
        Thread.__init__(self)
        self.sender = broker.test.popen(
            ["qpid-send",
             "--broker", "localhost:%s"%broker.port(),
             "--address", "%s;{create:always}"%queue,
             "--failover-updates",
             "--connection-options", "{%s}"%(connection_options),
             "--content-stdin"
             ],
            expect=EXPECT_RUNNING,
            stdin=PIPE)
        self.condition = Condition()
        self.max = max_depth
        self.received = 0
        self.stopped = False
        self.error = None

    def write_message(self, n):
        self.sender.stdin.write(str(n)+"\n")
        self.sender.stdin.flush()

    def run(self):
        try:
            self.sent = 0
            while not self.stopped:
                self.sender.assert_running()
                if self.max:
                    self.condition.acquire()
                    while not self.stopped and self.sent - self.received > self.max:
                        self.condition.wait()
                    self.condition.release()
                self.write_message(self.sent)
                self.sent += 1
        except Exception: self.error = RethrownException(self.sender.pname)

    def notify_received(self, count):
        """Called by receiver to enable flow control. count = messages received so far."""
        self.condition.acquire()
        self.received = count
        self.condition.notify()
        self.condition.release()

    def stop(self):
        self.condition.acquire()
        try:
            self.stopped = True
            self.condition.notify()
        finally: self.condition.release()
        join(self)
        self.write_message(-1)          # end-of-messages marker.
        if self.error: raise self.error

class NumberedReceiver(Thread):
    """
    Thread to run a receiver client and verify it receives
    sequentially numbered messages.
    """
    def __init__(self, broker, sender = None, queue="test-queue",
                 connection_options=Cluster.CONNECTION_OPTIONS):
        """
        sender: enable flow control. Call sender.received(n) for each message received.
        """
        Thread.__init__(self)
        self.test = broker.test
        self.receiver = self.test.popen(
            ["qpid-receive",
             "--broker", "localhost:%s"%broker.port(),
             "--address", "%s;{create:always}"%queue,
             "--failover-updates",
             "--connection-options", "{%s}"%(connection_options),
             "--forever"
             ],
            expect=EXPECT_RUNNING,
            stdout=PIPE)
        self.lock = Lock()
        self.error = None
        self.sender = sender
        self.received = 0

    def read_message(self):
        return int(self.receiver.stdout.readline())

    def run(self):
        try:
            m = self.read_message()
            while m != -1:
                self.receiver.assert_running()
                assert(m <= self.received) # Check for missing messages
                if (m == self.received): # Ignore duplicates
                    self.received += 1
                    if self.sender:
                        self.sender.notify_received(self.received)
                m = self.read_message()
        except Exception:
            self.error = RethrownException(self.receiver.pname)

    def stop(self):
        """Returns when termination message is received"""
        join(self)
        if self.error: raise self.error

class ErrorGenerator(StoppableThread):
    """
    Thread that continuously generates errors by trying to consume from
    a non-existent queue. For cluster regression tests, error handling
    caused issues in the past.
    """

    def __init__(self, broker):
        StoppableThread.__init__(self)
        self.broker=broker
        broker.test.cleanup_stop(self)
        self.start()

    def run(self):
        c = self.broker.connect_old()
        try:
            while not self.stopped:
                try:
                    c.session(str(qpid.datatypes.uuid4())).message_subscribe(
                        queue="non-existent-queue")
                    assert(False)
                except qpid.session.SessionException: pass
                time.sleep(0.01)
        except: pass                    # Normal if broker is killed.

def import_script(path):
    """
    Import executable script at path as a module.
    Requires some trickery as scripts are not in standard module format
    """
    f = open(path)
    try:
        name=os.path.split(path)[1].replace("-","_")
        return imp.load_module(name, f, path, ("", "r", imp.PY_SOURCE))
    finally: f.close()

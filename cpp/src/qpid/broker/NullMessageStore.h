/*
 *
 * Copyright (c) 2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
#ifndef _NullMessageStore_
#define _NullMessageStore_

#include "qpid/broker/Message.h"
#include "qpid/broker/MessageStore.h"
#include "qpid/broker/Queue.h"

namespace qpid {
    namespace broker {
        class Queue;
        class QueueRegistry;

        /**
         * A null implementation of the MessageStore interface
         */
        class NullMessageStore : public MessageStore{
        public:
            void create(const Queue& queue);
            void destroy(const Queue& queue);
            void recover(QueueRegistry& queues);
            void enqueue(TransactionContext* ctxt, Message::shared_ptr& msg, const Queue& queue, const string * const xid);
            void dequeue(TransactionContext* ctxt, Message::shared_ptr& msg, const Queue& queue, const string * const xid);
            void committed(const string * const xid);
            void aborted(const string * const xid);
            TransactionContext* begin();
            void commit(TransactionContext* ctxt);
            void abort(TransactionContext* ctxt);
            ~NullMessageStore(){}
        };
    }
}


#endif

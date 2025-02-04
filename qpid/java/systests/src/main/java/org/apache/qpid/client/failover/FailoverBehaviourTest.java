/*
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
 */
package org.apache.qpid.client.failover;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.TransactionRolledBackException;

import org.apache.qpid.client.AMQConnection;
import org.apache.qpid.jms.ConnectionListener;
import org.apache.qpid.test.utils.FailoverBaseCase;

/**
 * Test suite to test all possible failover corner cases
 */
public class FailoverBehaviourTest extends FailoverBaseCase implements ConnectionListener, ExceptionListener
{
    private static final String TEST_MESSAGE_FORMAT = "test message {0}";

    /** Indicates whether tests are run against clustered broker */
    private static boolean CLUSTERED = Boolean.getBoolean("profile.clustered");

    /** Default number of messages to send before failover */
    private static final int DEFAULT_NUMBER_OF_MESSAGES = 10;

    /** Actual number of messages to send before failover */
    protected int _messageNumber = Integer.getInteger("profile.failoverMsgCount", DEFAULT_NUMBER_OF_MESSAGES);

    /** Test connection */
    protected Connection _connection;

    /**
     * Failover completion latch is used to wait till connectivity to broker is
     * restored
     */
    private CountDownLatch _failoverComplete;

    /**
     * Consumer session
     */
    private Session _consumerSession;

    /**
     * Test destination
     */
    private Destination _destination;

    /**
     * Consumer
     */
    private MessageConsumer _consumer;

    /**
     * Producer session
     */
    private Session _producerSession;

    /**
     * Producer
     */
    private MessageProducer _producer;

    /**
     * Holds exception sent into {@link ExceptionListener} on failover
     */
    private JMSException _exceptionListenerException;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();

        _connection = getConnection();
        _connection.setExceptionListener(this);
        ((AMQConnection) _connection).setConnectionListener(this);
        _failoverComplete = new CountDownLatch(1);
    }

    /**
     * Test whether MessageProducer can successfully publish messages after
     * failover and rollback transaction
     */
    public void testMessageProducingAndRollbackAfterFailover() throws Exception
    {
        init(Session.SESSION_TRANSACTED, true);
        produceMessages();
        causeFailure();

        assertFailoverException();
        // producer should be able to send messages after failover
        _producer.send(_producerSession.createTextMessage("test message " + _messageNumber));

        // rollback after failover
        _producerSession.rollback();

        // tests whether sending and committing is working after failover
        produceMessages();
        _producerSession.commit();

        // tests whether receiving and committing is working after failover
        consumeMessages();
        _consumerSession.commit();
    }

    /**
     * Test whether {@link TransactionRolledBackException} is thrown on commit
     * of dirty transacted session after failover.
     * <p>
     * Verifies whether second after failover commit is successful.
     */
    public void testTransactionRolledBackExceptionThrownOnCommitAfterFailoverOnProducingMessages() throws Exception
    {
        init(Session.SESSION_TRANSACTED, true);
        produceMessages();
        causeFailure();

        assertFailoverException();

        // producer should be able to send messages after failover
        _producer.send(_producerSession.createTextMessage("test message " + _messageNumber));

        try
        {
            _producerSession.commit();
            fail("TransactionRolledBackException is expected on commit after failover with dirty session!");
        }
        catch (JMSException t)
        {
            assertTrue("Expected TransactionRolledBackException but thrown " + t,
                    t instanceof TransactionRolledBackException);
        }

        // simulate process of user replaying the transaction
        produceMessages("replayed test message {0}", _messageNumber, false);

        // no exception should be thrown
        _producerSession.commit();

        // only messages sent after rollback should be received
        consumeMessages("replayed test message {0}", _messageNumber);

        // no exception should be thrown
        _consumerSession.commit();
    }

    /**
     * Tests JMSException is not thrown on commit with a clean session after
     * failover
     */
    public void testNoJMSExceptionThrownOnCommitAfterFailoverWithCleanProducerSession() throws Exception
    {
        init(Session.SESSION_TRANSACTED, true);

        causeFailure();

        assertFailoverException();

        // should not throw an exception for a clean session
        _producerSession.commit();

        // tests whether sending and committing is working after failover
        produceMessages();
        _producerSession.commit();

        // tests whether receiving and committing is working after failover
        consumeMessages();
        _consumerSession.commit();
    }

    /**
     * Tests {@link TransactionRolledBackException} is thrown on commit of dirty
     * transacted session after failover.
     * <p>
     * Verifies whether second after failover commit is successful.
     */
    public void testTransactionRolledBackExceptionThrownOnCommitAfterFailoverOnMessageReceiving() throws Exception
    {
        init(Session.SESSION_TRANSACTED, true);
        produceMessages();
        _producerSession.commit();

        // receive messages but do not commit
        consumeMessages();

        causeFailure();

        assertFailoverException();

        try
        {
            // should throw TransactionRolledBackException
            _consumerSession.commit();
            fail("TransactionRolledBackException is expected on commit after failover");
        }
        catch (Exception t)
        {
            assertTrue("Expected TransactionRolledBackException but thrown " + t,
                    t instanceof TransactionRolledBackException);
        }

        resendMessagesIfNecessary();

        // consume messages successfully
        consumeMessages();
        _consumerSession.commit();
    }

    /**
     * Tests JMSException is not thrown on commit with a clean session after failover
     */
    public void testNoJMSExceptionThrownOnCommitAfterFailoverWithCleanConsumerSession() throws Exception
    {
        init(Session.SESSION_TRANSACTED, true);
        produceMessages();
        _producerSession.commit();

        consumeMessages();
        _consumerSession.commit();

        causeFailure();

        assertFailoverException();

        // should not throw an exception with a clean consumer session
        _consumerSession.commit();
    }

    /**
     * Test that TransactionRolledBackException is thrown on commit of
     * dirty session in asynchronous consumer after failover.
     */
    public void testTransactionRolledBackExceptionThrownOnCommitAfterFailoverOnReceivingMessagesAsynchronously()
    throws Exception
    {
        init(Session.SESSION_TRANSACTED, false);
        FailoverTestMessageListener ml = new FailoverTestMessageListener();
        _consumer.setMessageListener(ml);

        _connection.start();

        produceMessages();
        _producerSession.commit();

        // wait for message receiving
        ml.awaitForEnd();

        assertEquals("Received unexpected number of messages!", _messageNumber, ml.getMessageCounter());

        // assert messages
        int counter = 0;
        for (Message message : ml.getReceivedMessages())
        {
            assertReceivedMessage(message, TEST_MESSAGE_FORMAT, counter++);
        }
        ml.reset();

        causeFailure();
        assertFailoverException();


        try
        {
            _consumerSession.commit();
            fail("TransactionRolledBackException should be thrown!");
        }
        catch (TransactionRolledBackException e)
        {
            // that is what is expected
        }

        resendMessagesIfNecessary();

        // wait for message receiving
        ml.awaitForEnd();

        assertEquals("Received unexpected number of messages!", _messageNumber, ml.getMessageCounter());

        // assert messages
        counter = 0;
        for (Message message : ml.getReceivedMessages())
        {
            assertReceivedMessage(message, TEST_MESSAGE_FORMAT, counter++);
        }

        // commit again. It should be successful
        _consumerSession.commit();
    }

    /**
     * Test that {@link Session#rollback()} does not throw exception after failover
     * and that we are able to consume messages.
     */
    public void testRollbackAfterFailover() throws Exception
    {
        init(Session.SESSION_TRANSACTED, true);

        produceMessages();
        _producerSession.commit();

        consumeMessages();

        causeFailure();

        assertFailoverException();

        _consumerSession.rollback();

        resendMessagesIfNecessary();

        // tests whether receiving and committing is working after failover
        consumeMessages();
        _consumerSession.commit();
    }

    /**
     * Test that {@link Session#rollback()} does not throw exception after receiving further messages
     * after failover, and we can receive published messages after rollback.
     */
    public void testRollbackAfterReceivingAfterFailover() throws Exception
    {
        init(Session.SESSION_TRANSACTED, true);

        produceMessages();
        _producerSession.commit();

        consumeMessages();
        causeFailure();

        assertFailoverException();

        resendMessagesIfNecessary();

        consumeMessages();

        _consumerSession.rollback();

        // tests whether receiving and committing is working after failover
        consumeMessages();
        _consumerSession.commit();
    }

    /**
     * Test that {@link Session#recover()} does not throw an exception after failover
     * and that we can consume messages after recover.
     */
    public void testRecoverAfterFailover() throws Exception
    {
        init(Session.CLIENT_ACKNOWLEDGE, true);

        produceMessages();

        // consume messages but do not acknowledge them
        consumeMessages();

        causeFailure();

        assertFailoverException();

        _consumerSession.recover();

        resendMessagesIfNecessary();

        // tests whether receiving and acknowledgment is working after recover
        Message lastMessage = consumeMessages();
        lastMessage.acknowledge();
    }

    /**
     * Test that receiving more messages after failover and then calling
     * {@link Session#recover()} does not throw an exception
     * and that we can consume messages after recover.
     */
    public void testRecoverWithConsumedMessagesAfterFailover() throws Exception
    {
        init(Session.CLIENT_ACKNOWLEDGE, true);

        produceMessages();

        // consume messages but do not acknowledge them
        consumeMessages();

        causeFailure();

        assertFailoverException();

        // publishing should work after failover
        resendMessagesIfNecessary();

        // consume messages again on a dirty session
        consumeMessages();

        // recover should successfully restore session
        _consumerSession.recover();

        // tests whether receiving and acknowledgment is working after recover
        Message lastMessage = consumeMessages();
        lastMessage.acknowledge();
    }

    /**
     * Test that first call to {@link Message#acknowledge()} after failover
     * throws a JMSEXception if session is dirty.
     */
    public void testAcknowledgeAfterFailover() throws Exception
    {
        init(Session.CLIENT_ACKNOWLEDGE, true);

        produceMessages();

        // consume messages but do not acknowledge them
        Message lastMessage = consumeMessages();
        causeFailure();

        assertFailoverException();

        try
        {
            // an implicit recover performed when acknowledge throws an exception due to failover 
            lastMessage.acknowledge();
            fail("JMSException should be thrown");
        }
        catch (JMSException t)
        {
            // TODO: assert error code and/or expected exception type
        }

        resendMessagesIfNecessary();

        // tests whether receiving and acknowledgment is working after recover
        lastMessage = consumeMessages();
        lastMessage.acknowledge();
    }

    /**
     * Test that calling acknowledge before failover leaves the session
     * clean for use after failover.
     */
    public void testAcknowledgeBeforeFailover() throws Exception
    {
        init(Session.CLIENT_ACKNOWLEDGE, true);

        produceMessages();

        // consume messages and acknowledge them
        Message lastMessage = consumeMessages();
        lastMessage.acknowledge();

        causeFailure();

        assertFailoverException();

        produceMessages();

        // tests whether receiving and acknowledgment is working after recover
        lastMessage = consumeMessages();
        lastMessage.acknowledge();
    }

    /**
     * Test that receiving of messages after failover prior to calling
     * {@link Message#acknowledge()} still results in acknowledge throwing an exception.
     */
    public void testAcknowledgeAfterMessageReceivingAfterFailover() throws Exception
    {
        init(Session.CLIENT_ACKNOWLEDGE, true);

        produceMessages();

        // consume messages but do not acknowledge them
        consumeMessages();
        causeFailure();

        assertFailoverException();

        resendMessagesIfNecessary();

        // consume again on dirty session
        Message lastMessage = consumeMessages();
        try
        {
            // an implicit recover performed when acknowledge throws an exception due to failover 
            lastMessage.acknowledge();
            fail("JMSException should be thrown");
        }
        catch (JMSException t)
        {
            // TODO: assert error code and/or expected exception type
        }

        // tests whether receiving and acknowledgment is working on a clean session
        lastMessage = consumeMessages();
        lastMessage.acknowledge();
    }

    /**
     * Tests that call to {@link Message#acknowledge()} after failover throws an exception in asynchronous consumer
     * and we can consume messages after acknowledge.
     */
    public void testAcknowledgeAfterFailoverForAsynchronousConsumer() throws Exception
    {
        init(Session.CLIENT_ACKNOWLEDGE, false);
        FailoverTestMessageListener ml = new FailoverTestMessageListener();
        _consumer.setMessageListener(ml);
        _connection.start();

        produceMessages();

        // wait for message receiving
        ml.awaitForEnd();

        assertEquals("Received unexpected number of messages!", _messageNumber, ml.getMessageCounter());

        // assert messages
        int counter = 0;
        Message currentMessage = null;
        for (Message message : ml.getReceivedMessages())
        {
            assertReceivedMessage(message, TEST_MESSAGE_FORMAT, counter++);
            currentMessage = message;
        }
        ml.reset();

        causeFailure();
        assertFailoverException();


        try
        {
            currentMessage.acknowledge();
            fail("JMSException should be thrown!");
        }
        catch (JMSException e)
        {
            // TODO: assert error code and/or expected exception type
        }

        resendMessagesIfNecessary();

        // wait for message receiving
        ml.awaitForEnd();

        assertEquals("Received unexpected number of messages!", _messageNumber, ml.getMessageCounter());

        // assert messages
        counter = 0;
        for (Message message : ml.getReceivedMessages())
        {
            assertReceivedMessage(message, TEST_MESSAGE_FORMAT, counter++);
            currentMessage = message;
        }

        // acknowledge again. It should be successful
        currentMessage.acknowledge();
    }

    /**
     * Test whether {@link Session#recover()} works as expected after failover
     * in AA mode.
     */
    public void testRecoverAfterFailoverInAutoAcknowledgeMode() throws Exception
    {
        init(Session.AUTO_ACKNOWLEDGE, true);

        produceMessages();

        // receive first message in order to start a dispatcher thread
        Message receivedMessage = _consumer.receive(1000l);
        assertReceivedMessage(receivedMessage, TEST_MESSAGE_FORMAT, 0);

        causeFailure();

        assertFailoverException();

        _consumerSession.recover();

        resendMessagesIfNecessary();

        // tests whether receiving is working after recover
        consumeMessages();
    }

    public void testClientAcknowledgedSessionCloseAfterFailover() throws Exception
    {
        sessionCloseAfterFailoverImpl(Session.CLIENT_ACKNOWLEDGE);
    }

    public void testTransactedSessionCloseAfterFailover() throws Exception
    {
        sessionCloseAfterFailoverImpl(Session.SESSION_TRANSACTED);
    }

    public void testAutoAcknowledgedSessionCloseAfterFailover() throws Exception
    {
        sessionCloseAfterFailoverImpl(Session.AUTO_ACKNOWLEDGE);
    }

    /**
     * Tests {@link Session#close()} for session with given acknowledge mode 
     * to ensure that close works after failover.
     *
     * @param acknowledgeMode session acknowledge mode
     * @throws JMSException
     */
    private void sessionCloseAfterFailoverImpl(int acknowledgeMode) throws JMSException
    {
        init(acknowledgeMode, true);
        produceMessages(TEST_MESSAGE_FORMAT, _messageNumber, false);
        if (acknowledgeMode == Session.SESSION_TRANSACTED)
        {
            _producerSession.commit();
        }

        // intentionally receive message but do not commit or acknowledge it in
        // case of transacted or CLIENT_ACK session
        Message receivedMessage = _consumer.receive(1000l);
        assertReceivedMessage(receivedMessage, TEST_MESSAGE_FORMAT, 0);

        causeFailure();

        assertFailoverException();

        // for transacted/client_ack session
        // no exception should be thrown but transaction should be automatically
        // rolled back
        _consumerSession.close();
    }

    /**
     * A helper method to instantiate produce and consumer sessions, producer
     * and consumer.
     *
     * @param acknowledgeMode
     *            acknowledge mode
     * @param startConnection
     *            indicates whether connection should be started
     * @throws JMSException
     */
    private void init(int acknowledgeMode, boolean startConnection) throws JMSException
    {
        boolean isTransacted = acknowledgeMode == Session.SESSION_TRANSACTED ? true : false;

        _consumerSession = _connection.createSession(isTransacted, acknowledgeMode);
        _destination = _consumerSession.createQueue(getTestQueueName() + "_" + System.currentTimeMillis());
        _consumer = _consumerSession.createConsumer(_destination);

        if (startConnection)
        {
            _connection.start();
        }

        _producerSession = _connection.createSession(isTransacted, acknowledgeMode);
        _producer = _producerSession.createProducer(_destination);

    }

    /**
     * Resends messages if reconnected to a non-clustered broker
     *
     * @throws JMSException
     */
    private void resendMessagesIfNecessary() throws JMSException
    {
        if (!CLUSTERED)
        {
            // assert that a new broker does not have messages on a queue
            if (_consumer.getMessageListener() == null)
            {
                Message message = _consumer.receive(100l);
                assertNull("Received a message after failover with non-clustered broker!", message);
            }
            // re-sending messages if reconnected to a non-clustered broker
            produceMessages(true);
        }
    }

    /**
     * Produces a default number of messages with default text content into test
     * queue
     *
     * @throws JMSException
     */
    private void produceMessages() throws JMSException
    {
        produceMessages(false);
    }

    private void produceMessages(boolean seperateProducer) throws JMSException
    {
        produceMessages(TEST_MESSAGE_FORMAT, _messageNumber, seperateProducer);
    }

    /**
     * Consumes a default number of messages and asserts their content.
     *
     * @return last consumed message
     * @throws JMSException
     */
    private Message consumeMessages() throws JMSException
    {
        return consumeMessages(TEST_MESSAGE_FORMAT, _messageNumber);
    }

    /**
     * Produces given number of text messages with content matching given
     * content pattern
     *
     * @param messagePattern message content pattern
     * @param messageNumber  number of messages to send
     * @param standaloneProducer whether to use the existing producer or a new one.
     * @throws JMSException
     */
    private void produceMessages(String messagePattern, int messageNumber, boolean standaloneProducer) throws JMSException
    {
        Session producerSession;
        MessageProducer producer;

        if(standaloneProducer)
        {
            producerSession = _connection.createSession(true, Session.SESSION_TRANSACTED);
            producer = producerSession.createProducer(_destination);
        }
        else
        {
            producerSession = _producerSession;
            producer = _producer;
        }

        for (int i = 0; i < messageNumber; i++)
        {
            String text = MessageFormat.format(messagePattern, i);
            Message message = producerSession.createTextMessage(text);
            producer.send(message);
        }

        if(standaloneProducer)
        {
            producerSession.commit();
        }
    }

    /**
     * Consumes given number of text messages and asserts that their content
     * matches given pattern
     *
     * @param messagePattern
     *            messages content pattern
     * @param messageNumber
     *            message number to received
     * @return last consumed message
     * @throws JMSException
     */
    private Message consumeMessages(String messagePattern, int messageNumber) throws JMSException
    {
        Message receivedMesssage = null;
        for (int i = 0; i < messageNumber; i++)
        {
            receivedMesssage = _consumer.receive(1000l);
            assertReceivedMessage(receivedMesssage, messagePattern, i);
        }
        return receivedMesssage;
    }

    /**
     * Asserts received message
     *
     * @param receivedMessage
     *            received message
     * @param messagePattern
     *            messages content pattern
     * @param messageIndex
     *            message index
     */
    private void assertReceivedMessage(Message receivedMessage, String messagePattern, int messageIndex)
    {
        assertNotNull("Expected message [" + messageIndex + "] is not received!", receivedMessage);
        assertTrue("Failure to receive message [" + messageIndex + "], expected TextMessage but received "
                + receivedMessage, receivedMessage instanceof TextMessage);
        String expectedText = MessageFormat.format(messagePattern, messageIndex);
        String receivedText = null;
        try
        {
            receivedText = ((TextMessage) receivedMessage).getText();
        }
        catch (JMSException e)
        {
            fail("JMSException occured while getting message text:" + e.getMessage());
        }
        assertEquals("Failover is broken! Expected [" + expectedText + "] but got [" + receivedText + "]",
                expectedText, receivedText);
    }

    /**
     * Causes failover and waits till connection is re-established.
     */
    private void causeFailure()
    {
        causeFailure(getFailingPort(), DEFAULT_FAILOVER_TIME * 2);
    }

    /**
     * Causes failover by stopping broker on given port and waits till
     * connection is re-established during given time interval.
     *
     * @param port
     *            broker port
     * @param delay
     *            time interval to wait for connection re-establishement
     */
    private void causeFailure(int port, long delay)
    {
        failBroker(port);

        awaitForFailoverCompletion(delay);
    }

    private void awaitForFailoverCompletion(long delay)
    {
        _logger.info("Awaiting Failover completion..");
        try
        {
            if (!_failoverComplete.await(delay, TimeUnit.MILLISECONDS))
            {
                fail("Failover did not complete");
            }
        }
        catch (InterruptedException e)
        {
            fail("Test was interrupted:" + e.getMessage());
        }
    }

    private void assertFailoverException()
    {
        // TODO: assert exception is received (once implemented)
        // along with error code and/or expected exception type
    }

    @Override
    public void bytesSent(long count)
    {
    }

    @Override
    public void bytesReceived(long count)
    {
    }

    @Override
    public boolean preFailover(boolean redirect)
    {
        return true;
    }

    @Override
    public boolean preResubscribe()
    {
        return true;
    }

    @Override
    public void failoverComplete()
    {
        _failoverComplete.countDown();
    }

    @Override
    public void onException(JMSException e)
    {
        _exceptionListenerException = e;
    }

    private class FailoverTestMessageListener implements MessageListener
    {
        // message counter
        private AtomicInteger _counter = new AtomicInteger();

        private List<Message> _receivedMessage = new ArrayList<Message>();

        private volatile CountDownLatch _endLatch;

        public FailoverTestMessageListener() throws JMSException
        {
            _endLatch = new CountDownLatch(1);
        }

        @Override
        public void onMessage(Message message)
        {
            _receivedMessage.add(message);
            if (_counter.incrementAndGet() % _messageNumber == 0)
            {
                _endLatch.countDown();
            }
        }

        public void reset()
        {
            _receivedMessage.clear();
            _endLatch = new CountDownLatch(1);
            _counter.set(0);
        }

        public List<Message> getReceivedMessages()
        {
            return _receivedMessage;
        }

        public Object awaitForEnd() throws InterruptedException
        {
            return _endLatch.await((long) _messageNumber, TimeUnit.SECONDS);
        }

        public int getMessageCounter()
        {
            return _counter.get();
        }
    }
}

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
package org.apache.qpid.server.protocol;

import junit.framework.Assert;
import junit.framework.JUnit4TestAdapter;
import org.apache.qpid.codec.AMQDecoder;
import org.apache.qpid.codec.AMQEncoder;
import org.apache.qpid.framing.*;
import org.apache.mina.common.ByteBuffer;
import org.apache.mina.common.WriteFuture;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;
import org.apache.mina.filter.codec.support.SimpleProtocolDecoderOutput;
import org.junit.Before;
import org.junit.Test;

/**
 * This test suite tests the handling of protocol initiation frames and related issues.
 */
public class TestProtocolInitiation implements ProtocolVersionList
{
    private AMQPFastProtocolHandler _protocolHandler;

    private MockIoSession _mockIoSession;

    /**
     * We need to use the object encoder mechanism so to allow us to retrieve the
     * output (a bytebuffer) we define our own encoder output class. The encoder
     * writes the encoded data to this class, from where we can retrieve it during
     * the test run.
     */
    private class TestProtocolEncoderOutput implements ProtocolEncoderOutput
    {
        public ByteBuffer result;

        public void write(ByteBuffer buf)
        {
            result = buf;
        }

        public void mergeAll()
        {
            throw new UnsupportedOperationException();
        }

        public WriteFuture flush()
        {
            throw new UnsupportedOperationException();
        }
    }

    private class TestProtocolDecoderOutput implements ProtocolDecoderOutput
    {
        public Object result;

        public void write(Object buf)
        {
            result = buf;
        }

        public void flush()
        {
            throw new UnsupportedOperationException();
        }
    }

    @Before
    public void createCommonObjects()
    {
        _mockIoSession = new MockIoSession();
        _protocolHandler = new AMQPFastProtocolHandler(null, null);
    }


    /**
     * Tests that the AMQDecoder handles invalid protocol classes
     * @throws Exception
     */
    @Test(expected = AMQProtocolClassException.class)
    public void testDecoderValidateProtocolClass() throws Exception
    {
        ProtocolInitiation pi = createValidProtocolInitiation();
        pi.protocolClass = 2;
        decodePI(pi);
    }

    /**
     * Tests that the AMQDecoder handles invalid protocol instance numbers
     * @throws Exception
     */
    @Test(expected = AMQProtocolInstanceException.class)
    public void testDecoderValidatesProtocolInstance() throws Exception
    {
        ProtocolInitiation pi = createValidProtocolInitiation();
        pi.protocolInstance = 2;
        decodePI(pi);
    }

    /**
     * Tests that the AMQDecoder handles invalid protocol major
     * @throws Exception
     */
    @Test(expected = AMQProtocolVersionException.class)
    public void testDecoderValidatesProtocolMajor() throws Exception
    {
        ProtocolInitiation pi = createValidProtocolInitiation();
        pi.protocolMajor = 2;
        decodePI(pi);
    }

    /**
     * Tests that the AMQDecoder handles invalid protocol minor
     * @throws Exception
     */
    @Test(expected = AMQProtocolVersionException.class)
    public void testDecoderValidatesProtocolMinor() throws Exception
    {
        ProtocolInitiation pi = createValidProtocolInitiation();
        pi.protocolMinor = 99;
        decodePI(pi);
    }

    /**
     * Tests that the AMQDecoder accepts a valid PI
     * @throws Exception
     */
    @Test(expected = AMQProtocolHeaderException.class)
    public void testDecoderValidatesHeader() throws Exception
    {
        ProtocolInitiation pi = createValidProtocolInitiation();
        pi.header = new char[] {'P', 'Q', 'M', 'A' };
        decodePI(pi);
    }

    /**
     * Test that a valid header is passed by the decoder.
     * @throws Exception
     */
    @Test
    public void testDecoderAcceptsValidHeader() throws Exception
    {
        ProtocolInitiation pi = createValidProtocolInitiation();
        decodePI(pi);
    }

    /**
     * This test checks that an invalid protocol header results in the
     * connection being closed.
     */
    @Test
    public void testInvalidProtocolHeaderClosesConnection() throws Exception
    {
        AMQProtocolHeaderException pe = new AMQProtocolHeaderException("Test");
        _protocolHandler.exceptionCaught(_mockIoSession, pe);
        Assert.assertNotNull(_mockIoSession.getLastWrittenObject());
        Object piResponse = _mockIoSession.getLastWrittenObject();
        Assert.assertEquals(piResponse.getClass(), ProtocolInitiation.class);
        ProtocolInitiation pi = (ProtocolInitiation) piResponse;
        Assert.assertEquals("Protocol Initiation sent out was not the broker's expected header", pi,
                            createValidProtocolInitiation());
        Assert.assertTrue("Session has not been closed", _mockIoSession.isClosing());
    }

    private ProtocolInitiation createValidProtocolInitiation()
    {
        /* Find last protocol version in protocol version list. Make sure last protocol version
        listed in the build file (build-module.xml) is the latest version which will be used
        here. */
        int i = pv.length - 1;
        return new ProtocolInitiation(pv[i][PROTOCOL_MAJOR], pv[i][PROTOCOL_MINOR]);
    }

    /**
     * Helper that encodes a protocol initiation and attempts to decode it
     * @param pi
     * @throws Exception
     */
    private void decodePI(ProtocolInitiation pi) throws Exception
    {
        // we need to do this test at the level of the decoder since we initially only expect PI frames
        // so the protocol handler is not set up to know whether it should be expecting a PI frame or
        // a different type of frame
        AMQDecoder decoder = new AMQDecoder(true);
        AMQEncoder encoder = new AMQEncoder();
        TestProtocolEncoderOutput peo = new TestProtocolEncoderOutput();
        encoder.encode(_mockIoSession, pi, peo);
        TestProtocolDecoderOutput pdo = new TestProtocolDecoderOutput();
        decoder.decode(_mockIoSession, peo.result, pdo);
        ((ProtocolInitiation) pdo.result).checkVersion(this);
    }

    public static junit.framework.Test suite()
    {
        return new JUnit4TestAdapter(TestProtocolInitiation.class);
    }
}

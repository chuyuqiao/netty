/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http;


import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.CharsetUtil;
import org.junit.Test;

import java.util.List;

import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HttpRequestDecoderTest {
    private static final byte[] CONTENT_CRLF_DELIMITERS = createContent("\r\n");
    private static final byte[] CONTENT_LF_DELIMITERS = createContent("\n");
    private static final byte[] CONTENT_MIXED_DELIMITERS = createContent("\r\n", "\n");
    private static final int CONTENT_LENGTH = 8;

    private static byte[] createContent(String... lineDelimiters) {
        String lineDelimiter;
        String lineDelimiter2;
        if (lineDelimiters.length == 2) {
            lineDelimiter = lineDelimiters[0];
            lineDelimiter2 = lineDelimiters[1];
        } else {
            lineDelimiter = lineDelimiters[0];
            lineDelimiter2 = lineDelimiters[0];
        }
        return ("GET /some/path?foo=bar&wibble=eek HTTP/1.1" + "\r\n" +
                "Upgrade: WebSocket" + lineDelimiter2 +
                "Connection: Upgrade" + lineDelimiter +
                "Host: localhost" + lineDelimiter2 +
                "Origin: http://localhost:8080" + lineDelimiter +
                "Sec-WebSocket-Key1: 10  28 8V7 8 48     0" + lineDelimiter2 +
                "Sec-WebSocket-Key2: 8 Xt754O3Q3QW 0   _60" + lineDelimiter +
                "Content-Length: " + CONTENT_LENGTH + lineDelimiter2 +
                "\r\n"  +
                "12345678").getBytes(CharsetUtil.US_ASCII);
    }

    @Test
    public void testDecodeWholeRequestAtOnceCRLFDelimiters() {
        testDecodeWholeRequestAtOnce(CONTENT_CRLF_DELIMITERS);
    }

    @Test
    public void testDecodeWholeRequestAtOnceLFDelimiters() {
        testDecodeWholeRequestAtOnce(CONTENT_LF_DELIMITERS);
    }

    @Test
    public void testDecodeWholeRequestAtOnceMixedDelimiters() {
        testDecodeWholeRequestAtOnce(CONTENT_MIXED_DELIMITERS);
    }

    private static void testDecodeWholeRequestAtOnce(byte[] content) {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder());
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(content)));
        HttpRequest req = (HttpRequest) channel.readInbound();
        assertNotNull(req);
        checkHeaders(req.headers());
        LastHttpContent c = (LastHttpContent) channel.readInbound();
        assertEquals(CONTENT_LENGTH, c.content().readableBytes());
        assertEquals(
                Unpooled.wrappedBuffer(content, content.length - CONTENT_LENGTH, CONTENT_LENGTH),
                c.content().readSlice(CONTENT_LENGTH));
        c.release();

        assertFalse(channel.finish());
        assertNull(channel.readInbound());
    }

    private static void checkHeaders(HttpHeaders headers) {
        assertEquals(7, headers.names().size());
        checkHeader(headers, "Upgrade", "WebSocket");
        checkHeader(headers, "Connection", "Upgrade");
        checkHeader(headers, "Host", "localhost");
        checkHeader(headers, "Origin", "http://localhost:8080");
        checkHeader(headers, "Sec-WebSocket-Key1", "10  28 8V7 8 48     0");
        checkHeader(headers, "Sec-WebSocket-Key2", "8 Xt754O3Q3QW 0   _60");
        checkHeader(headers, "Content-Length", String.valueOf(CONTENT_LENGTH));
    }

    private static void checkHeader(HttpHeaders headers, String name, String value) {
        List<String> header1 = headers.getAll(name);
        assertEquals(1, header1.size());
        assertEquals(value, header1.get(0));
    }

    @Test
    public void testDecodeWholeRequestInMultipleStepsCRLFDelimiters() {
        testDecodeWholeRequestInMultipleSteps(CONTENT_CRLF_DELIMITERS);
    }

    @Test
    public void testDecodeWholeRequestInMultipleStepsLFDelimiters() {
        testDecodeWholeRequestInMultipleSteps(CONTENT_LF_DELIMITERS);
    }

    @Test
    public void testDecodeWholeRequestInMultipleStepsMixedDelimiters() {
        testDecodeWholeRequestInMultipleSteps(CONTENT_MIXED_DELIMITERS);
    }

    private static void testDecodeWholeRequestInMultipleSteps(byte[] content) {
        for (int i = 1; i < content.length; i++) {
            testDecodeWholeRequestInMultipleSteps(content, i);
        }
    }

    private static void testDecodeWholeRequestInMultipleSteps(byte[] content, int fragmentSize) {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder());
        int headerLength = content.length - CONTENT_LENGTH;

        // split up the header
        for (int a = 0; a < headerLength;) {
            int amount = fragmentSize;
            if (a + amount > headerLength) {
                amount = headerLength -  a;
            }

            // if header is done it should produce a HttpRequest
            channel.writeInbound(Unpooled.wrappedBuffer(content, a, amount));
            a += amount;
        }

        for (int i = CONTENT_LENGTH; i > 0; i --) {
            // Should produce HttpContent
            channel.writeInbound(Unpooled.wrappedBuffer(content, content.length - i, 1));
        }

        HttpRequest req = (HttpRequest) channel.readInbound();
        assertNotNull(req);
        checkHeaders(req.headers());

        for (int i = CONTENT_LENGTH; i > 1; i --) {
            HttpContent c = (HttpContent) channel.readInbound();
            assertEquals(1, c.content().readableBytes());
            assertEquals(content[content.length - i], c.content().readByte());
            c.release();
        }

        LastHttpContent c = (LastHttpContent) channel.readInbound();
        assertEquals(1, c.content().readableBytes());
        assertEquals(content[content.length - 1], c.content().readByte());
        c.release();

        assertFalse(channel.finish());
        assertNull(channel.readInbound());
    }

    @Test
    public void testMultiLineHeader() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder());
        String crlf = "\r\n";
        String request =  "GET /some/path HTTP/1.1" + crlf +
                "Host: localhost" + crlf +
                "MyTestHeader: part1" + crlf +
                "              newLinePart2" + crlf +
                "MyTestHeader2: part21" + crlf +
                "\t            newLinePart22"
                + crlf + crlf;
        assertTrue(channel.writeInbound(Unpooled.copiedBuffer(request, CharsetUtil.US_ASCII)));
        HttpRequest req = (HttpRequest) channel.readInbound();
        assertEquals("part1 newLinePart2", req.headers().get("MyTestHeader"));
        assertEquals("part21 newLinePart22", req.headers().get("MyTestHeader2"));

        LastHttpContent c = (LastHttpContent) channel.readInbound();
        c.release();

        assertFalse(channel.finish());
        assertNull(channel.readInbound());
    }

    @Test
    public void testEmptyHeaderValue() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder());
        String crlf = "\r\n";
        String request = "GET /some/path HTTP/1.1" + crlf +
                "Host: localhost" + crlf +
                "EmptyHeader:" + crlf + crlf;
        channel.writeInbound(Unpooled.copiedBuffer(request, CharsetUtil.US_ASCII));
        HttpRequest req = (HttpRequest) channel.readInbound();
        assertEquals("", req.headers().get("EmptyHeader"));
    }

    @Test
    public void testMessagesSplitBetweenMultipleBuffers() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder());
        String crlf = "\r\n";
        String str1 = "GET /some/path HTTP/1.1" + crlf +
                "Host: localhost1" + crlf + crlf +
                "GET /some/other/path HTTP/1.0" + crlf +
                "Hos";
        String str2 = "t: localhost2" + crlf +
                "content-length: 0" + crlf + crlf;
        channel.writeInbound(Unpooled.copiedBuffer(str1, CharsetUtil.US_ASCII));
        HttpRequest req = (HttpRequest) channel.readInbound();
        assertEquals(HttpVersion.HTTP_1_1, req.getProtocolVersion());
        assertEquals("/some/path", req.getUri());
        assertFalse(req.headers().isEmpty());
        assertTrue("localhost1".equalsIgnoreCase(req.headers().get(HOST)));
        LastHttpContent cnt = (LastHttpContent) channel.readInbound();
        cnt.release();

        channel.writeInbound(Unpooled.copiedBuffer(str2, CharsetUtil.US_ASCII));
        req = (HttpRequest) channel.readInbound();
        assertEquals(HttpVersion.HTTP_1_0, req.getProtocolVersion());
        assertEquals("/some/other/path", req.getUri());
        assertFalse(req.headers().isEmpty());

        assertTrue("localhost2".equalsIgnoreCase(req.headers().get(HOST)));
        assertTrue("0".equalsIgnoreCase(req.headers().get(HttpHeaders.Names.CONTENT_LENGTH)));
        cnt = (LastHttpContent) channel.readInbound();
        cnt.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    public void testTooLargeInitialLine() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder(10, 1024, 1024));
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Host: localhost1\r\n\r\n";

        assertTrue(channel.writeInbound(Unpooled.copiedBuffer(requestStr, CharsetUtil.US_ASCII)));
        HttpRequest request = (HttpRequest) channel.readInbound();
        assertTrue(request.getDecoderResult().isFailure());
        assertTrue(request.getDecoderResult().cause() instanceof TooLongFrameException);
        assertFalse(channel.finish());
    }

    @Test
    public void testTooLargeHeaders() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder(1024, 10, 1024));
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Host: localhost1\r\n\r\n";

        assertTrue(channel.writeInbound(Unpooled.copiedBuffer(requestStr, CharsetUtil.US_ASCII)));
        HttpRequest request = (HttpRequest) channel.readInbound();
        assertTrue(request.getDecoderResult().isFailure());
        assertTrue(request.getDecoderResult().cause() instanceof TooLongFrameException);
        assertFalse(channel.finish());
    }
}

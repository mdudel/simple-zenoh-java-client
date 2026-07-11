/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.transport;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Stream-transport framing helpers for Zenoh 1.x over stream-oriented
 * links (TCP, TLS, WSS).
 *
 * <p>Because TCP does not preserve message boundaries, Zenoh prepends
 * every batch on the wire with a 16-bit little-endian length header
 * indicating how many bytes of payload follow. This matches
 * {@code BatchSize = u16} in
 * {@code commons/zenoh-protocol/src/transport/mod.rs} and the read
 * loop in {@code io/zenoh-transport/src/unicast/link.rs} which reads
 * the two length bytes then a body of exactly that many bytes.</p>
 *
 * <pre>
 * +---------------+---------------+~~~~~~~~~~~~~~~~~~~~~+
 * |  len_lo (u8)  |  len_hi (u8)  |  payload (len bytes)|
 * +---------------+---------------+~~~~~~~~~~~~~~~~~~~~~+
 *  \_______ 2-byte little-endian __/
 * </pre>
 *
 * <p>{@link #MAX_FRAME_BYTES} = 65 535 is a hard wire cap; payloads
 * above that must be split into {@code FRAGMENT} messages (deferred
 * to a later turn).</p>
 *
 * <p>All methods are static and stateless. This class does not own
 * the streams and does not close them; that is the transport's job.</p>
 */
public final class StreamFramer {

    /** Maximum bytes of payload in a single framed batch, per the {@code BatchSize=u16} wire cap. */
    public static final int MAX_FRAME_BYTES = 0xFFFF;

    /** Byte length of the length prefix itself (2). */
    public static final int LENGTH_PREFIX_BYTES = 2;

    private StreamFramer() {}

    /**
     * Write one framed batch (length-prefix + payload) to the stream.
     * Does <b>not</b> flush; caller decides when to flush.
     *
     * @throws IllegalArgumentException if {@code payload.length} exceeds {@link #MAX_FRAME_BYTES}
     * @throws IOException              on write failure
     */
    public static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException(
                    "payload exceeds " + MAX_FRAME_BYTES + " bytes: " + payload.length);
        }
        int len = payload.length;
        // Little-endian u16 length prefix
        out.write(len & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(payload);
    }

    /**
     * Read one framed batch from the stream. Blocks until either the
     * full frame is available or the stream ends / errors.
     *
     * @return the payload bytes (without the length prefix)
     * @throws EOFException        if the stream ends before a full frame is available.
     *                             A clean end (EOF on the very first read of the length
     *                             prefix) is signalled with the message
     *                             "clean EOF at frame boundary"; a partial-frame EOF
     *                             is signalled with a message including how many bytes
     *                             were expected vs read.
     * @throws IOException         on read failure
     */
    public static byte[] readFrame(InputStream in) throws IOException {
        int lo = in.read();
        if (lo < 0) {
            throw new EOFException("clean EOF at frame boundary");
        }
        int hi = in.read();
        if (hi < 0) {
            throw new EOFException(
                    "EOF after 1 byte of 2-byte length prefix (unclean close)");
        }
        int len = (lo & 0xFF) | ((hi & 0xFF) << 8);
        byte[] payload = new byte[len];
        readFully(in, payload, 0, len);
        return payload;
    }

    /**
     * Read exactly {@code len} bytes into {@code buf[off..off+len]},
     * looping over short reads. Package-private for reuse by transports
     * that need the same primitive.
     *
     * @throws EOFException if EOF hits before {@code len} bytes are read
     */
    static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n < 0) {
                throw new EOFException(
                        "EOF after " + read + " of " + len + " expected payload bytes");
            }
            read += n;
        }
    }
}

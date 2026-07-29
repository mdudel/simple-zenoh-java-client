/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Read-side byte buffer with a cursor. Mirror of {@link WBuf}.
 *
 * <p>All primitives throw {@link EOFException}-typed
 * {@link IllegalArgumentException} (kept unchecked to make codec code
 * readable) if the buffer is too short.</p>
 *
 * <p>Not thread-safe.</p>
 */
public final class RBuf {

    private final byte[] data;
    private int pos;
    private final int end;

    /** Wrap {@code data} entirely. */
    public RBuf(byte[] data) { this(data, 0, data.length); }

    /** Wrap the {@code [offset, offset+length)} slice of {@code data}. */
    public RBuf(byte[] data, int offset, int length) {
        this.data = Objects.requireNonNull(data);
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException(
                    "RBuf slice out of range: offset=" + offset
                            + " length=" + length
                            + " data.length=" + data.length);
        }
        this.pos = offset;
        this.end = offset + length;
    }

    public int  position()  { return pos; }
    public int  remaining() { return end - pos; }
    public boolean hasMore(){ return pos < end; }

    private void ensure(int n) {
        if (remaining() < n) {
            throw new IllegalArgumentException(
                    "RBuf underflow: needed " + n
                            + " byte(s), have " + remaining());
        }
    }

    // --- primitives ------------------------------------------------------

    /** Read an unsigned byte as {@code int} in {@code [0, 255]}. */
    public int u8() {
        ensure(1);
        return data[pos++] & 0xFF;
    }

    /** Peek the next unsigned byte without consuming it. */
    public int peekU8() {
        ensure(1);
        return data[pos] & 0xFF;
    }

    /** Read a little-endian unsigned 16-bit integer. */
    public int u16le() {
        ensure(2);
        int lo = data[pos++] & 0xFF;
        int hi = data[pos++] & 0xFF;
        return (hi << 8) | lo;
    }

    /**
     * Read an unsigned LEB128 varint. See {@link VarInt}.
     *
     * @throws IllegalArgumentException on truncated or malformed encoding
     */
    public long varInt() {
        VarInt.Decoded d = VarInt.decode(data, pos);
        pos += d.bytesRead();
        return d.value();
    }

    /** Read {@code n} raw bytes into a fresh array. */
    public byte[] bytes(int n) {
        if (n < 0) throw new IllegalArgumentException("RBuf.bytes(negative): " + n);
        ensure(n);
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    // --- length-prefixed forms -------------------------------------------

    /** Length-prefixed byte slice: varint length then that many raw bytes. */
    public byte[] lenBytes() {
        long len = varInt();
        if (len < 0 || len > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "RBuf.lenBytes: length out of int range: " + len);
        }
        return bytes((int) len);
    }

    /** Length-prefixed UTF-8 string. */
    public String lenString() {
        return new String(lenBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Consume up to {@code n} bytes and return a new {@code RBuf} viewing
     * that slice. The parent cursor advances by {@code n}.
     */
    public RBuf slice(int n) {
        ensure(n);
        RBuf sub = new RBuf(data, pos, n);
        pos += n;
        return sub;
    }
}

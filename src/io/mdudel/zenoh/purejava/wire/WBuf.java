/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of a clean-room pure-Java implementation of the Eclipse
 * Zenoh 1.x wire protocol. It is not a copy of any Zenoh source code.
 */
package io.mdudel.zenoh.purejava.wire;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Growable write-side byte buffer used by the codec. Deliberately trivial -
 * wraps {@link ByteArrayOutputStream} and adds the Zenoh-specific write
 * primitives every message layer needs (little-endian u16, unsigned LEB128
 * varints, length-prefixed byte slices, length-prefixed UTF-8 strings).
 *
 * <p>Not thread-safe. Callers own synchronisation.</p>
 */
public final class WBuf {

    private final ByteArrayOutputStream out;

    public WBuf() { this.out = new ByteArrayOutputStream(); }
    public WBuf(int initialCapacity) { this.out = new ByteArrayOutputStream(initialCapacity); }

    public int size() { return out.size(); }
    public byte[] toByteArray() { return out.toByteArray(); }

    // --- primitives ------------------------------------------------------

    /** Write a single unsigned byte (low 8 bits of {@code b}). */
    public WBuf u8(int b) {
        out.write(b & 0xFF);
        return this;
    }

    /** Write a little-endian unsigned 16-bit integer. */
    public WBuf u16le(int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        return this;
    }

    /** Write an unsigned LEB128 varint. See {@link VarInt}. */
    public WBuf varInt(long v) {
        try { VarInt.encode(v, out); } catch (IOException e) { throw new AssertionError(e); }
        return this;
    }

    /** Raw byte copy. */
    public WBuf bytes(byte[] b) {
        out.write(b, 0, b.length);
        return this;
    }

    public WBuf bytes(byte[] b, int off, int len) {
        out.write(b, off, len);
        return this;
    }

    /** Concatenate another WBuf's payload verbatim. */
    public WBuf append(WBuf other) {
        try { out.write(other.toByteArray()); } catch (IOException e) { throw new AssertionError(e); }
        return this;
    }

    // --- length-prefixed forms -------------------------------------------

    /**
     * Length-prefixed byte slice using a varint length. This is the standard
     * {@code <u8;zN>} form Zenoh uses for opaque byte fields such as cookies.
     */
    public WBuf lenBytes(byte[] b) {
        varInt(b.length);
        bytes(b);
        return this;
    }

    /**
     * UTF-8 encoded length-prefixed string using a varint length. Used for
     * key expression suffixes and similar Zenoh string fields.
     */
    public WBuf lenString(String s) {
        return lenBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}

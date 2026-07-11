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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Unsigned LEB128 variable-length integer codec used pervasively by the Zenoh
 * 1.x wire protocol.
 *
 * <p>Encoding: the integer is split into 7-bit groups from LSB. Each byte
 * carries 7 payload bits in its low 7 bits; the high bit is a continuation
 * flag (1 = more bytes follow, 0 = last byte).</p>
 *
 * <p>Values 0..127 encode as one byte, 128..16383 as two bytes, and so on up
 * to a hard cap of 10 bytes for a 64-bit unsigned value.</p>
 *
 * <p>This class is deliberately dependency-free and side-effect-free apart
 * from writing to the caller-supplied stream. Threading is the caller's
 * responsibility.</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>Round trip: {@code decode(encode(v)) == v} for all non-negative
 *       {@code long} values.</li>
 *   <li>Negative values throw {@link IllegalArgumentException} on encode.</li>
 *   <li>A varint that would require more than 10 bytes on the wire (i.e. a
 *       malformed 11+ byte encoding) throws on decode.</li>
 *   <li>An unterminated varint (stream EOF before the continuation bit
 *       clears) throws {@link EOFException} on decode.</li>
 * </ul>
 */
public final class VarInt {

    /** Maximum encoded length in bytes for a 64-bit unsigned value. */
    public static final int MAX_BYTES = 10;

    private VarInt() {}

    // ----- encode ---------------------------------------------------------

    /**
     * Encode {@code value} as an unsigned LEB128 varint and return the bytes.
     * Convenience wrapper around {@link #encode(long, OutputStream)}.
     *
     * @throws IllegalArgumentException if {@code value < 0}
     */
    public static byte[] encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "VarInt.encode: negative values not supported: " + value);
        }
        // Compute exact size to avoid over-allocating.
        int size = sizeOf(value);
        byte[] out = new byte[size];
        long v = value;
        for (int i = 0; i < size - 1; i++) {
            out[i] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[size - 1] = (byte) (v & 0x7F);
        return out;
    }

    /**
     * Encode {@code value} as an unsigned LEB128 varint directly to
     * {@code out}. Returns the number of bytes written.
     *
     * @throws IllegalArgumentException if {@code value < 0}
     * @throws IOException              if {@code out.write} throws
     */
    public static int encode(long value, OutputStream out) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "VarInt.encode: negative values not supported: " + value);
        }
        int written = 0;
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
            written++;
        }
        out.write((int) v);
        return written + 1;
    }

    /** Number of bytes {@link #encode} would produce for {@code value}. */
    public static int sizeOf(long value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "VarInt.sizeOf: negative values not supported: " + value);
        }
        int n = 1;
        long v = value;
        while ((v & ~0x7FL) != 0) {
            v >>>= 7;
            n++;
        }
        return n;
    }

    // ----- decode ---------------------------------------------------------

    /** Result of a {@link #decode(byte[], int)} call: the value, and where the cursor stopped. */
    public record Decoded(long value, int bytesRead) {}

    /**
     * Decode an unsigned LEB128 varint starting at {@code offset} in {@code src}.
     *
     * @throws IllegalArgumentException if the encoding runs off the end of the
     *         array, exceeds {@link #MAX_BYTES}, or would overflow 64 bits.
     */
    public static Decoded decode(byte[] src, int offset) {
        long value = 0;
        int shift = 0;
        int i = offset;
        int bytesRead = 0;
        while (bytesRead < MAX_BYTES) {
            if (i >= src.length) {
                throw new IllegalArgumentException(
                        "VarInt.decode: truncated varint at offset " + offset
                                + " (need more bytes)");
            }
            byte b = src[i++];
            bytesRead++;
            value |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return new Decoded(value, bytesRead);
            }
            shift += 7;
        }
        throw new IllegalArgumentException(
                "VarInt.decode: varint longer than " + MAX_BYTES
                        + " bytes (malformed) at offset " + offset);
    }

    /**
     * Decode an unsigned LEB128 varint from {@code in}. Returns the decoded
     * value.
     *
     * @throws EOFException             if the stream ends before the last
     *                                  continuation bit clears
     * @throws IOException              on other stream errors
     * @throws IllegalArgumentException on malformed encoding (11+ bytes)
     */
    public static long decode(InputStream in) throws IOException {
        long value = 0;
        int shift = 0;
        for (int bytesRead = 0; bytesRead < MAX_BYTES; bytesRead++) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException(
                        "VarInt.decode: EOF after " + bytesRead + " byte(s)");
            }
            value |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException(
                "VarInt.decode: varint longer than " + MAX_BYTES
                        + " bytes (malformed)");
    }
}

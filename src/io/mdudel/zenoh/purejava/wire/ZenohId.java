/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Zenoh node identifier.
 *
 * <p>Per the spec, a ZID is between 1 and 16 bytes. In INIT and OPEN
 * transport messages the length is encoded in the high nibble of the byte
 * that also carries the WhatAmI, as {@code zid_len|x|x|wai}, where the
 * on-wire encoded length is one less than the true length:
 * {@code encoded = actual - 1}.</p>
 *
 * <p>Two ZIDs are equal iff their byte arrays are equal.</p>
 */
public final class ZenohId {

    /** Minimum ZID length (bytes). */
    public static final int MIN_LEN = 1;
    /** Maximum ZID length (bytes). */
    public static final int MAX_LEN = 16;

    private final byte[] bytes;

    /**
     * Wrap the given byte array. Defensive-copies. Rejects lengths outside
     * {@link #MIN_LEN}..{@link #MAX_LEN}.
     */
    public ZenohId(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < MIN_LEN || bytes.length > MAX_LEN) {
            throw new IllegalArgumentException(
                    "ZenohId length must be " + MIN_LEN + ".." + MAX_LEN
                            + ", got " + bytes.length);
        }
        this.bytes = bytes.clone();
    }

    /** Length in bytes (1..16). */
    public int length() { return bytes.length; }

    /** Defensive copy of the underlying bytes. */
    public byte[] bytes() { return bytes.clone(); }

    /**
     * The value that goes into the high nibble of the length/WhatAmI byte:
     * {@code actual_length - 1}, always in {@code 0..15}.
     */
    public int encodedLenNibble() { return bytes.length - 1; }

    /**
     * Generate a random ZID of the given length using {@link SecureRandom}.
     * Suitable for the ZID a client generates for its own INIT SYN.
     */
    public static ZenohId random(int length) {
        if (length < MIN_LEN || length > MAX_LEN) {
            throw new IllegalArgumentException(
                    "ZenohId.random: length must be " + MIN_LEN + ".." + MAX_LEN
                            + ", got " + length);
        }
        byte[] b = new byte[length];
        new SecureRandom().nextBytes(b);
        return new ZenohId(b);
    }

    /** Generate a random 16-byte ZID (the common default for clients). */
    public static ZenohId random() { return random(MAX_LEN); }

    /**
     * Reconstruct a ZID from the encoded high-nibble length + the bytes that
     * follow on the wire.
     *
     * @param encodedLenNibble the raw 4-bit value from the wire
     *                         (actual_length - 1, so 0..15)
     * @param source           the buffer positioned at the start of the ZID
     *                         bytes
     */
    public static ZenohId readAfterLenNibble(int encodedLenNibble, RBuf source) {
        if (encodedLenNibble < 0 || encodedLenNibble > 15) {
            throw new IllegalArgumentException(
                    "ZID encoded length nibble out of range: " + encodedLenNibble);
        }
        int actual = encodedLenNibble + 1;
        return new ZenohId(source.bytes(actual));
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ZenohId other)) return false;
        return Arrays.equals(bytes, other.bytes);
    }

    @Override public int hashCode() { return Arrays.hashCode(bytes); }

    /** Lowercase hex, no separators. */
    @Override public String toString() { return HexFormat.of().formatHex(bytes); }
}

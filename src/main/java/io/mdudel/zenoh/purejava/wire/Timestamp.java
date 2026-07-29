/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * A Zenoh HLC (Hybrid Logical Clock) timestamp carried inside a Put
 * or as a network-level Timestamp extension.
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-codec/src/core/timestamp.rs}):</p>
 * <pre>
 * +----------------+
 * ~  time: uvarint ~   NTP64 u64 (whole 64-bit HLC value)
 * +----------------+
 * ~ id: <u8; z8>   ~   varint-length-prefixed ZenohId (little-endian, 1..16 B)
 * +----------------+
 * </pre>
 *
 * <p>The {@code time} field is an {@code NTP64}: the high 32 bits are
 * NTP seconds since 1900-01-01 UTC, the low 32 bits are a fixed-point
 * fraction (2^32 == 1 second). Publishers that only need "wall-clock
 * now" can call {@link #now(ZenohId)} which handles the epoch shift
 * (NTP epoch = UNIX epoch &minus; 2208988800 seconds).</p>
 *
 * <p>The {@code id} is a distinct-per-source disambiguator, typically
 * the same {@link ZenohId} the publisher used at handshake time.</p>
 */
public record Timestamp(long ntp64, ZenohId id) {

    /** Seconds between NTP epoch (1900-01-01) and UNIX epoch (1970-01-01). */
    public static final long NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L;

    public Timestamp {
        Objects.requireNonNull(id, "id must not be null");
    }

    /**
     * Build a timestamp for "now" with sub-second precision derived from
     * {@link System#currentTimeMillis()}. The fractional part is a
     * millisecond-resolution conversion; a real HLC needs a monotonic
     * counter to break ties. Use this only when a monotonically
     * increasing timestamp is not required.
     */
    public static Timestamp now(ZenohId id) {
        return fromInstant(Instant.now(), id);
    }

    /** Build a timestamp from an {@link Instant} and a source id. */
    public static Timestamp fromInstant(Instant when, ZenohId id) {
        long unixSeconds = when.getEpochSecond();
        long ntpSeconds  = unixSeconds + NTP_UNIX_OFFSET_SECONDS;
        int  nanos       = when.getNano();
        // fraction = nanos * 2^32 / 1e9
        long fraction = (long) ((double) nanos * 4_294_967_296.0 / 1_000_000_000.0);
        long ntp64    = (ntpSeconds << 32) | (fraction & 0xFFFFFFFFL);
        return new Timestamp(ntp64, id);
    }

    /** Convert this timestamp's HLC value to a UNIX {@link Instant}, discarding sub-nanosecond precision. */
    public Instant toInstant() {
        long ntpSeconds = (ntp64 >>> 32) & 0xFFFFFFFFL;
        long fraction   = ntp64 & 0xFFFFFFFFL;
        long unixSecs   = ntpSeconds - NTP_UNIX_OFFSET_SECONDS;
        long nanos      = (long) ((double) fraction * 1_000_000_000.0 / 4_294_967_296.0);
        return Instant.ofEpochSecond(unixSecs, nanos);
    }

    /** Encode this timestamp into the supplied buffer. */
    public void encode(WBuf w) {
        w.varInt(ntp64);
        w.lenBytes(id.bytes());
    }

    /** Decode a timestamp from the supplied buffer. */
    public static Timestamp decode(RBuf r) {
        long ntp = r.varInt();
        byte[] idBytes = r.lenBytes();
        return new Timestamp(ntp, new ZenohId(idBytes));
    }

    @Override public String toString() {
        return "Timestamp{" + toInstant() + " (0x" + Long.toHexString(ntp64) + "), id=" + id + "}";
    }

    // Records with byte[] don't hash/equal by content by default, but ZenohId is a proper record
    // wrapping an int[] equivalent — its equals/hashCode should be content-based. Timestamp itself
    // has no byte[] field, so the compiler-synthesised equals/hashCode over (ntp64, id) is correct
    // as long as ZenohId.equals is content-based.

    // Sanity: assert length here would be nice but ZenohId enforces 1..16 already.

    @SuppressWarnings("unused")
    private static final int UNUSED = 0; // keep record layout stable
}


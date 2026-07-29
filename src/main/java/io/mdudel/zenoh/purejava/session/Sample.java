/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.session;

import io.mdudel.zenoh.purejava.wire.Encoding;
import io.mdudel.zenoh.purejava.wire.Timestamp;
import io.mdudel.zenoh.purejava.wire.ZenohId;

import java.util.Objects;

/**
 * One received message delivered to a {@link Subscription}. The result
 * of decoding an inbound PUSH containing a PUT payload.
 *
 * @param key            the concrete key the publisher used (never
 *                       contains wildcards; wildcards live in the
 *                       subscription's own KeyExpr).
 * @param payload        the raw PUT payload bytes. Owned by the caller
 *                       after {@code take()} / {@code poll()}.
 * @param encoding       the PUT's declared {@link Encoding}, or
 *                       {@link Encoding#EMPTY} if the publisher didn't
 *                       set one.
 * @param timestamp      the PUT's HLC timestamp, or {@code null} if
 *                       the publisher didn't set one.
 * @param sourceId       the publisher's {@link ZenohId} if the PUSH
 *                       carried a NodeId extension, else {@code null}.
 *                       Not all routers propagate this.
 * @param receivedNanos  {@link System#nanoTime()} at the moment our
 *                       session reader thread pulled this batch off
 *                       the wire. Useful for local latency measurement;
 *                       do NOT compare against nanos from a different
 *                       JVM (System.nanoTime is not portable across
 *                       processes).
 */
public record Sample(
        String    key,
        byte[]    payload,
        Encoding  encoding,
        Timestamp timestamp,     // nullable
        ZenohId   sourceId,      // nullable
        long      receivedNanos) {

    public Sample {
        Objects.requireNonNull(key,      "key");
        Objects.requireNonNull(payload,  "payload");
        Objects.requireNonNull(encoding, "encoding");
        payload = payload.clone();      // defensive copy so caller can't mutate
    }

    /** Convenience: no timestamp, no source id, "now" as the receive stamp. */
    public static Sample bare(String key, byte[] payload, Encoding encoding) {
        return new Sample(key, payload, encoding, null, null, System.nanoTime());
    }

    /** Convenience: interpret payload as UTF-8 for the common string-payload case. */
    public String payloadAsString() {
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override public byte[] payload() { return payload.clone(); }

    @Override public String toString() {
        return "Sample{key=" + key
                + ", payload=" + payload.length + "B"
                + (timestamp == null ? "" : ", ts=" + timestamp)
                + (sourceId  == null ? "" : ", src=" + sourceId)
                + ", encoding=" + encoding
                + "}";
    }
}

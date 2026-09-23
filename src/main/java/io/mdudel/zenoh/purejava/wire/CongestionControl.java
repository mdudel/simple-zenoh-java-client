/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire;

/**
 * Congestion control strategy carried in the QoS extension byte
 * (bit 3, {@code D_FLAG = 0b00001000}).
 *
 * <p>Mirrors the two stable Rust {@code CongestionControl} variants
 * (Rust {@code BlockFirst} is behind the unstable feature flag and is
 * not exposed here).</p>
 */
public enum CongestionControl {

    /**
     * The router MAY drop the message if downstream queues are full.
     * This is the Zenoh default and is safe for streams where latest
     * value matters more than every value (sensor snapshots, positions).
     */
    DROP,

    /**
     * The router will block the sender until queue space is available.
     * Use with care: a single slow subscriber can stall the entire
     * publisher pipeline.
     */
    BLOCK;

    /** The Zenoh default congestion control ({@link #DROP}). */
    public static final CongestionControl DEFAULT = DROP;
}

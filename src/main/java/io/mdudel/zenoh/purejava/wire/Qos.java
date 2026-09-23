/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire;

import java.util.Objects;

/**
 * Zenoh QoS descriptor: {@link Priority} + {@link CongestionControl} +
 * an Express flag. Serialised as a single byte carried in a Z64
 * network extension at extension id {@code 0x01} on {@code PUSH},
 * {@code FRAME}, and similar messages.
 *
 * <p>Wire byte layout (from
 * {@code commons/zenoh-protocol/src/network/mod.rs} {@code QoSType}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |0|r|F|E|D|prio |
 * +-+-+-+-+-+-+---+
 * </pre>
 * <ul>
 *   <li>bits 0..2 ({@code P_MASK 0b00000111}): {@link Priority} (0..7).</li>
 *   <li>bit 3     ({@code D_FLAG 0b00001000}): don't drop -
 *       {@link CongestionControl#BLOCK}.</li>
 *   <li>bit 4     ({@code E_FLAG 0b00010000}): Express (don't batch).</li>
 *   <li>bit 5     ({@code F_FLAG 0b00100000}): don't-drop-first
 *       (unstable BlockFirst in Rust; not exposed here).</li>
 *   <li>bit 6:    reserved.</li>
 *   <li>bit 7:    reserved.</li>
 * </ul>
 *
 * <p><b>NOTE:</b> the QoS byte does NOT carry a reliability bit.
 * Reliability is a per-subscription concern signalled on the
 * transport {@link io.mdudel.zenoh.purejava.wire.messages.Frame} R flag
 * and on {@code DeclareSubscriber}; do not conflate the two.</p>
 *
 * <p>{@link #DEFAULT} is {@code (Priority.DATA, CongestionControl.DROP,
 * express=false)}, matching the Rust
 * {@code QoSType::DEFAULT}. When a caller passes {@link #DEFAULT},
 * publishers SHOULD omit the QoS extension entirely so the wire
 * output is byte-identical to the pre-QoS behaviour of this client.</p>
 */
public record Qos(Priority priority, CongestionControl congestionControl, boolean express) {

    /** {@code P_MASK}: priority bits 0..2. */
    public static final int P_MASK = 0b00000111;
    /** {@code D_FLAG}: don't drop (BLOCK). */
    public static final int D_FLAG = 0b00001000;
    /** {@code E_FLAG}: express. */
    public static final int E_FLAG = 0b00010000;
    /** {@code F_FLAG}: don't-drop-first (unstable, not exposed). */
    public static final int F_FLAG = 0b00100000;

    /**
     * Zenoh default QoS: {@link Priority#DATA} + {@link CongestionControl#DROP}
     * + {@code express=false}. Serialises to byte {@code 0x05}.
     */
    public static final Qos DEFAULT = new Qos(Priority.DATA, CongestionControl.DROP, false);

    public Qos {
        Objects.requireNonNull(priority,          "priority");
        Objects.requireNonNull(congestionControl, "congestionControl");
    }

    /** Convenience: express-off, drop-on, only priority varies. */
    public static Qos of(Priority p) {
        return new Qos(p, CongestionControl.DROP, false);
    }

    /** Encode this QoS descriptor into its single wire byte (0..255). */
    public int toWireByte() {
        int b = priority.wireValue() & P_MASK;
        if (congestionControl == CongestionControl.BLOCK) b |= D_FLAG;
        if (express)                                       b |= E_FLAG;
        return b & 0xFF;
    }

    /**
     * Decode a Qos descriptor from its single wire byte. Ignores the
     * unstable {@code F_FLAG} bit and any reserved bits.
     */
    public static Qos fromWireByte(int b) {
        Priority p = Priority.fromWire(b & P_MASK);
        CongestionControl cc = ((b & D_FLAG) != 0)
                ? CongestionControl.BLOCK
                : CongestionControl.DROP;
        boolean express = (b & E_FLAG) != 0;
        return new Qos(p, cc, express);
    }

    /** True iff this Qos equals {@link #DEFAULT}. */
    public boolean isDefault() {
        return this.equals(DEFAULT);
    }
}

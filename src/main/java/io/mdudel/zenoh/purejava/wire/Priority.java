/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire;

/**
 * Zenoh network-level priority class carried in the QoS extension byte
 * (bits 0..2, {@code P_MASK = 0b00000111}). Mirrors the Rust
 * {@code commons/zenoh-protocol/src/core/mod.rs} {@code Priority} enum
 * one-for-one.
 *
 * <p>Values (all part of the stable Zenoh 1.x protocol):</p>
 * <ul>
 *   <li>{@link #CONTROL} = 0 - highest priority; router control-plane
 *       traffic. Applications should not normally use this for user data.</li>
 *   <li>{@link #REAL_TIME} = 1 - hard real-time streams (safety-of-flight,
 *       weapons control, primary sensor feeds).</li>
 *   <li>{@link #INTERACTIVE_HIGH} = 2 - user-facing traffic that must not
 *       stall (e.g. an operator's manual-track injection).</li>
 *   <li>{@link #INTERACTIVE_LOW} = 3 - user-facing traffic that tolerates
 *       small latency (map pans, dialog RPCs).</li>
 *   <li>{@link #DATA_HIGH} = 4 - important non-interactive data streams
 *       (e.g. bulk track updates that other subsystems depend on).</li>
 *   <li>{@link #DATA} = 5 - <b>default</b>; general-purpose data traffic.</li>
 *   <li>{@link #DATA_LOW} = 6 - low-importance data streams (logs,
 *       secondary telemetry).</li>
 *   <li>{@link #BACKGROUND} = 7 - lowest priority; only sent when nothing
 *       else is queued (bulk transfer, dumps, snapshots).</li>
 * </ul>
 *
 * <p>The wire value is the enum ordinal (0..7). Rust source:
 * {@code core::Priority::Control = 0, RealTime = 1, InteractiveHigh = 2,
 * InteractiveLow = 3, DataHigh = 4, Data = 5, DataLow = 6, Background = 7}.
 * The {@code Data} variant is the Rust {@code #[default]}.</p>
 */
public enum Priority {

    CONTROL           (0, "control"),
    REAL_TIME         (1, "real-time"),
    INTERACTIVE_HIGH  (2, "interactive-high"),
    INTERACTIVE_LOW   (3, "interactive-low"),
    DATA_HIGH         (4, "data-high"),
    DATA              (5, "data"),
    DATA_LOW          (6, "data-low"),
    BACKGROUND        (7, "background");

    /** The Zenoh default priority class ({@link #DATA}). */
    public static final Priority DEFAULT = DATA;

    private final int    wireValue;
    private final String displayName;

    Priority(int wireValue, String displayName) {
        this.wireValue   = wireValue;
        this.displayName = displayName;
    }

    /** Wire value (0..7) packed into bits 0..2 of the QoS byte. */
    public int wireValue() { return wireValue; }

    /** Zenoh Display-format name (e.g. {@code "real-time"}, {@code "data-high"}). */
    public String displayName() { return displayName; }

    /**
     * Look up a priority by its wire value (0..7).
     *
     * @throws IllegalArgumentException if the value is out of range
     */
    public static Priority fromWire(int wireValue) {
        if (wireValue < 0 || wireValue > 7) {
            throw new IllegalArgumentException(
                    "Priority.fromWire: value must be 0..7, got " + wireValue);
        }
        return values()[wireValue];
    }

    /**
     * Parse a priority by name. Accepts both the Zenoh Display form
     * ({@code "real-time"}, {@code "data-high"}) and the Java enum form
     * ({@code "REAL_TIME"}, {@code "DATA_HIGH"}), case-insensitive.
     *
     * @throws IllegalArgumentException if the name does not match any priority
     */
    public static Priority parse(String s) {
        if (s == null) {
            throw new IllegalArgumentException("Priority.parse: null");
        }
        String norm = s.trim().toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-');
        for (Priority p : values()) {
            if (p.displayName.equals(norm)) return p;
        }
        throw new IllegalArgumentException(
                "Priority.parse: unknown priority '" + s + "'; expected one of "
                        + java.util.Arrays.toString(values()));
    }
}

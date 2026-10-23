/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.session;

/**
 * The lifecycle states of a {@link ZenohSession}.
 *
 * <pre>
 * CREATED &rarr; CONNECTING &rarr; OPENING &rarr; OPEN &harr; RECONNECTING
 *    &darr;         &darr;          &darr;         &darr;          &darr;
 *    &there4;         &there4;          &there4;      CLOSING       &there4;
 *    &there4;         &there4;          &there4;         &darr;          &there4;
 *   CLOSED     CLOSED      CLOSED    CLOSED      CLOSED
 * </pre>
 *
 * When {@link ZenohSession.Builder#autoReconnect(boolean)} is enabled,
 * an unexpected disconnect or lease expiry transitions from {@link #OPEN}
 * to {@link #RECONNECTING}, re-establishes the transport and handshake,
 * re-declares active subscriptions and interests, and returns to
 * {@link #OPEN}.
 */
public enum SessionState {

    /** Freshly built, no I/O has happened yet. */
    CREATED,

    /** Transport {@code connect()} is in flight. */
    CONNECTING,

    /** Transport is up; INIT / OPEN handshake in progress. */
    OPENING,

    /** Handshake complete; {@code publish()} is allowed and KEEP_ALIVE is scheduled. */
    OPEN,

    /**
     * Unexpected disconnect or lease expiry occurred while {@code autoReconnect}
     * is enabled; background reconnect and state re-declaration in progress.
     */
    RECONNECTING,

    /** {@code close()} has been called or a lease expired; CLOSE frame is being emitted. */
    CLOSING,

    /** Terminal. Transport is down. */
    CLOSED
}

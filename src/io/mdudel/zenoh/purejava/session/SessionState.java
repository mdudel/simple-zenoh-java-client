/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.session;

/**
 * The lifecycle states of a {@link ZenohSession}. Transitions are
 * strictly forward-only:
 *
 * <pre>
 * CREATED &rarr; CONNECTING &rarr; OPENING &rarr; OPEN &rarr; CLOSING &rarr; CLOSED
 *    &darr;         &darr;          &darr;         &darr;        &darr;
 *    &there4;         &there4;          &there4;         &there4;        &there4;
 *   CLOSED     CLOSED      CLOSED    CLOSED   CLOSED
 * </pre>
 *
 * Any state can transition to {@link #CLOSED} on error; there is no
 * "reopen" &mdash; construct a new session instead.
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

    /** {@code close()} has been called or a lease expired; CLOSE frame is being emitted. */
    CLOSING,

    /** Terminal. Transport is down. */
    CLOSED
}

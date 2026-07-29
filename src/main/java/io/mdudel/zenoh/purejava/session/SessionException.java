/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.session;

/**
 * Checked exception for session-layer failures (handshake failure,
 * unexpected remote reply, publish on non-OPEN session, close-timeout,
 * lease expired).
 */
public class SessionException extends Exception {

    private static final long serialVersionUID = 1L;

    public SessionException(String message) {
        super(message);
    }

    public SessionException(String message, Throwable cause) {
        super(message, cause);
    }
}

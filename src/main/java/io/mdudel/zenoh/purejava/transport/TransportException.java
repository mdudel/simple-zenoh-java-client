/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.transport;

import java.io.IOException;

/**
 * Checked exception for transport-layer failures (connect refused, read
 * timeout, malformed stream framing, oversized frame, remote close).
 *
 * <p>Wraps the underlying {@link IOException} where relevant so callers
 * can still switch on the JDK cause if they want to.</p>
 */
public class TransportException extends IOException {

    private static final long serialVersionUID = 1L;

    public TransportException(String message) {
        super(message);
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}

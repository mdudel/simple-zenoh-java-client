/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.transport;

import java.util.concurrent.TimeUnit;

/**
 * Minimal transport SPI for the publisher.
 *
 * <p>A {@code Transport} is one full-duplex framed link to a Zenoh
 * router: send a batch of bytes with {@link #send(byte[])}, receive
 * the next batch of bytes with {@link #receive(long, TimeUnit)}.
 * The framing (length-prefix on stream transports, message-per-datagram
 * on datagram transports) is entirely the transport's business; the
 * caller sees whole batches in and out.</p>
 *
 * <p>Threading contract:</p>
 * <ul>
 *   <li>{@link #send(byte[])} may be called from any thread and is
 *       expected to serialise internally (per-transport lock).</li>
 *   <li>{@link #receive(long, TimeUnit)} should be called from a
 *       single dedicated reader thread inside the session, but is
 *       safe to call from any thread as long as calls do not
 *       overlap.</li>
 *   <li>{@link #close()} may be called from any thread and unblocks
 *       in-flight {@link #receive(long, TimeUnit)} callers with
 *       either {@code null} (clean shutdown) or a
 *       {@link TransportException} (dirty).</li>
 * </ul>
 *
 * <p>Blocking-I/O implementation only; no async / NIO abstraction on
 * purpose (see design constraints in the module README).</p>
 */
public interface Transport extends AutoCloseable {

    /**
     * Open the link. After this returns, {@link #send(byte[])} and
     * {@link #receive(long, TimeUnit)} become usable. May only be
     * called once per instance.
     *
     * @throws TransportException on connect failure
     */
    void connect() throws TransportException;

    /**
     * Enqueue one batch of bytes to be written to the peer. Blocks
     * until the batch has been fully handed to the OS write buffer.
     *
     * @throws TransportException if the link is closed, or the write fails,
     *                            or the batch exceeds the transport's frame cap
     */
    void send(byte[] batch) throws TransportException;

    /**
     * Take the next received batch from the reader inbox. Blocks up to
     * the given timeout.
     *
     * @return the next batch, or {@code null} if the timeout elapsed
     *         with no batch available
     * @throws TransportException if the reader ended with an error
     *                            (peer close is signalled by returning
     *                            {@code null} <b>and</b> {@link #isOpen()}
     *                            transitioning to {@code false})
     * @throws InterruptedException if the caller thread was interrupted
     */
    byte[] receive(long timeout, TimeUnit unit)
            throws TransportException, InterruptedException;

    /** {@code true} between successful {@link #connect()} and any close (clean or dirty). */
    boolean isOpen();

    /**
     * Close the link. Idempotent: multiple calls are legal and only the
     * first has effect. Unblocks any in-flight {@link #receive(long, TimeUnit)}
     * caller (with {@code null} for clean shutdown, or by propagating
     * the underlying error for dirty).
     */
    @Override
    void close();

    /** Human-readable description of the peer endpoint, e.g. {@code "tcp/10.0.0.1:7447"}. */
    String describe();
}

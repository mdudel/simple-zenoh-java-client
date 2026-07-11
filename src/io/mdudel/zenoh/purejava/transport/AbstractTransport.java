/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.transport;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle skeleton shared by every {@link Transport} implementation,
 * regardless of whether the underlying protocol is stream-oriented
 * (TCP, TLS) or message-oriented (WebSocket).
 *
 * <p>Owns:</p>
 * <ul>
 *   <li>The opened / closed atomic flags and the {@link #isOpen()}
 *       computation.</li>
 *   <li>The inbound queue that {@link #receive(long, TimeUnit)} polls.</li>
 *   <li>The reader-error slot that {@link #receive(long, TimeUnit)}
 *       surfaces on timeout.</li>
 *   <li>The idempotent {@link #close()} entry point.</li>
 * </ul>
 *
 * <p>Concrete transports implement {@link #doConnect()} (open the
 * underlying link, arrange for received batches to arrive at
 * {@link #deliver(byte[])}), {@link #doSend(byte[])} (write one batch
 * synchronously), and {@link #doClose()} (release resources; called
 * exactly once even under concurrent close attempts).</p>
 */
public abstract class AbstractTransport implements Transport {

    private static final Logger LOG = System.getLogger(AbstractTransport.class.getName());

    private final AtomicBoolean opened = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final LinkedBlockingQueue<byte[]> inbox       = new LinkedBlockingQueue<>();
    private final AtomicReference<Throwable>  readerError = new AtomicReference<>();

    /**
     * Open the underlying link. Called exactly once by {@link #connect()}
     * under the once-only guard. Subclasses arrange for received batches
     * to be delivered via {@link #deliver(byte[])}, and for asynchronous
     * failures to be reported via {@link #reportReaderError(Throwable)}.
     *
     * @throws TransportException on connect failure (opened flag is
     *                            cleared by {@link #connect()} before rethrow)
     */
    protected abstract void doConnect() throws TransportException;

    /**
     * Write one batch synchronously. Called under the caller thread; the
     * caller does NOT hold any internal lock at this point. Subclasses
     * may serialise on their own writer or rely on their transport
     * primitive being thread-safe.
     */
    protected abstract void doSend(byte[] batch) throws TransportException;

    /**
     * Release resources. Called at most once; the atomic-CAS in
     * {@link #close()} guarantees no re-entrance.
     */
    protected abstract void doClose();

    /**
     * Deliver a received batch to the inbox. Called from the reader
     * thread / async callback.
     */
    protected final void deliver(byte[] batch) {
        inbox.offer(batch);
    }

    /**
     * Report an asynchronous reader error. First call wins; subsequent
     * calls are ignored. {@code EOFException} is the well-known
     * clean-close sentinel.
     */
    protected final void reportReaderError(Throwable t) {
        readerError.compareAndSet(null, t);
    }

    @Override
    public final void connect() throws TransportException {
        if (!opened.compareAndSet(false, true)) {
            throw new IllegalStateException("connect() may only be called once per instance");
        }
        try {
            doConnect();
            LOG.log(Level.DEBUG, () -> "connected " + describe());
        } catch (TransportException | RuntimeException e) {
            opened.set(false);
            throw e;
        }
    }

    @Override
    public final void send(byte[] batch) throws TransportException {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        if (!isOpen()) {
            throw new TransportException("send on closed transport " + describe());
        }
        if (batch.length > StreamFramer.MAX_FRAME_BYTES) {
            throw new TransportException(
                    "batch exceeds " + StreamFramer.MAX_FRAME_BYTES
                            + "-byte wire cap: " + batch.length);
        }
        doSend(batch);
    }

    @Override
    public final byte[] receive(long timeout, TimeUnit unit)
            throws TransportException, InterruptedException {
        byte[] batch = inbox.poll(timeout, unit);
        if (batch != null) return batch;
        Throwable err = readerError.get();
        if (err != null) {
            if (err instanceof java.io.EOFException) {
                // Clean remote close: null + isOpen() flips false.
                return null;
            }
            throw new TransportException(
                    "reader ended with error on " + describe() + ": " + err.getMessage(), err);
        }
        return null;
    }

    @Override public final boolean isOpen() {
        return opened.get() && !closed.get();
    }

    @Override public final void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { doClose(); }
        catch (RuntimeException re) {
            LOG.log(Level.DEBUG, () -> "doClose threw on " + describe() + ": " + re);
        }
    }
}

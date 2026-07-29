/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.session;

import io.mdudel.zenoh.purejava.wire.KeyExpr;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A live subscription handle. Returned by
 * {@link ZenohSession#declareSubscriber(KeyExpr)}.
 *
 * <p>Threading:</p>
 * <ul>
 *   <li>{@link #take()} / {@link #poll(long, TimeUnit)} are safe from
 *       any thread. They block on an internal queue that the session's
 *       reader thread feeds via {@link #offer(Sample)}.</li>
 *   <li>{@link #close()} is idempotent from any thread. First call
 *       emits {@code UndeclareSubscriber} and unregisters; subsequent
 *       calls are no-ops.</li>
 *   <li>{@link #forEach(Consumer)} attaches a callback that runs on a
 *       fresh daemon thread; it does NOT block the caller. The thread
 *       exits when the subscription closes. Only one callback per
 *       subscription; second call throws.</li>
 * </ul>
 *
 * <p>Backpressure: the internal queue is unbounded (matching the
 * transport's inbox). If the consumer is slow enough to fall behind,
 * memory grows. This is the accreditation-friendly default &mdash; loud
 * OOM beats silent drop &mdash; but a future bounded-queue overload can
 * be added without changing the SPI.</p>
 */
public final class Subscription implements AutoCloseable {

    private final long                             id;
    private final KeyExpr                          keyExpr;
    private final ZenohSession                     session;
    private final LinkedBlockingQueue<Sample>      inbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean                    closed = new AtomicBoolean(false);
    private final AtomicLong                       received = new AtomicLong(0);
    private volatile Thread                        forEachThread;

    Subscription(long id, KeyExpr keyExpr, ZenohSession session) {
        this.id      = id;
        this.keyExpr = Objects.requireNonNull(keyExpr, "keyExpr");
        this.session = Objects.requireNonNull(session, "session");
    }

    /** The subscriber id sent to the router in DECLARE. */
    public long id() { return id; }

    /** The key expression this subscription filters on (may contain wildcards). */
    public KeyExpr keyExpr() { return keyExpr; }

    /** Count of samples enqueued so far (successful + still-in-queue). */
    public long receivedCount() { return received.get(); }

    /** Alive iff the subscription is still registered with the session. */
    public boolean isOpen() { return !closed.get(); }

    // ---- consumer API -----------------------------------------------

    /**
     * Block until a sample is available or the subscription closes.
     * Returns {@code null} on close; otherwise the next {@link Sample}.
     *
     * <p>Wakes up promptly on {@link #close()} because the internal wait
     * is a {@code poll(100 ms)} loop that also checks {@link #isOpen()}.
     * If a caller genuinely needs push-style blocking with an
     * {@link InterruptedException}, wrap this in a caller thread that
     * you {@code interrupt()} directly &mdash; the loop honours the
     * interrupt.</p>
     *
     * @throws InterruptedException if the caller thread is interrupted
     */
    public Sample take() throws InterruptedException {
        while (isOpen()) {
            Sample s = inbox.poll(100, TimeUnit.MILLISECONDS);
            if (s != null) return s;
        }
        return null;
    }

    /**
     * Wait up to {@code timeout} for a sample. Returns {@code null} on
     * timeout or if the subscription closed.
     */
    public Sample poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (closed.get()) return null;
        long deadlineNs = System.nanoTime() + unit.toNanos(timeout);
        while (isOpen()) {
            long remainNs = deadlineNs - System.nanoTime();
            if (remainNs <= 0) return null;
            long stepMs = Math.min(100, TimeUnit.NANOSECONDS.toMillis(remainNs));
            Sample s = inbox.poll(stepMs, TimeUnit.MILLISECONDS);
            if (s != null) return s;
        }
        return null;
    }

    /**
     * Attach a consumer callback. Spawns a daemon thread that loops on
     * {@link #take()} and hands each sample to the callback. Returns
     * immediately.
     *
     * <p>The callback runs on the delivery thread; keep it fast. If the
     * callback throws, the exception is logged and delivery continues
     * with the next sample.</p>
     *
     * <p>Only one callback per subscription.</p>
     */
    public synchronized void forEach(Consumer<Sample> callback) {
        Objects.requireNonNull(callback, "callback");
        if (forEachThread != null) {
            throw new IllegalStateException("forEach callback already attached");
        }
        Thread t = new Thread(() -> {
            while (isOpen()) {
                Sample s;
                try { s = take(); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (s == null) return;   // subscription closed
                try { callback.accept(s); }
                catch (RuntimeException re) {
                    // Absorb; a bad callback should not tear down the delivery loop.
                    System.getLogger(Subscription.class.getName())
                            .log(System.Logger.Level.WARNING,
                                    "Subscription callback threw: " + re, re);
                }
            }
        }, "zenoh-sub-cb-" + Long.toHexString(id));
        t.setDaemon(true);
        this.forEachThread = t;
        t.start();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        session.undeclareSubscriberInternal(this);
        // Interrupt the callback thread so its take() unblocks and it can exit.
        Thread t = forEachThread;
        if (t != null && t != Thread.currentThread()) t.interrupt();
    }

    // ---- session-internal ------------------------------------------

    /** Called by ZenohSession's reader when an inbound sample matches this subscription. */
    void offer(Sample sample) {
        if (closed.get()) return;
        inbox.offer(sample);
        received.incrementAndGet();
    }

    @Override public String toString() {
        return "Subscription{id=" + id + ", key=" + keyExpr
                + ", received=" + received.get()
                + ", state=" + (isOpen() ? "OPEN" : "CLOSED") + "}";
    }
}

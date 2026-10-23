/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava;

import io.mdudel.zenoh.purejava.session.Subscription;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A live, self-updating view of the topics visible on a Zenoh network.
 *
 * <p>Merges every available discovery channel into one keyed set. See
 * {@link TopicSource} for what each channel proves. Two channels feed
 * this registry:</p>
 *
 * <ul>
 *   <li>An INTEREST sent to the router, which replays the current
 *       declarations (subscribers, queryables, liveliness tokens) and
 *       then streams changes as they happen.</li>
 *   <li>Optionally a wildcard subscription, which records the key of
 *       every message that actually arrives.</li>
 * </ul>
 *
 * <p>Obtain one from
 * {@link PureJavaZenohSubscriber#topics(String, boolean)}, read it with
 * {@link #snapshot()} whenever you want the current picture, and
 * {@link #close()} it when finished. The pull API deliberately mirrors
 * {@code PureJavaZenohScout.snapshot()}, which solves the same shape of
 * problem for router discovery.</p>
 *
 * <h2>Live versus cumulative</h2>
 * <p>A registry is one of two things, fixed at construction:</p>
 * <ul>
 *   <li><b>Live</b> (the default): a picture of what exists right now. A
 *       topic drops out of the snapshot once every declaration behind it
 *       is withdrawn.</li>
 *   <li><b>Cumulative</b>: an accumulating record of everything seen since
 *       the registry started. Nothing is ever removed. A topic that has
 *       gone away is still reported, with
 *       {@link TopicInfo#active()} false and
 *       {@link TopicInfo#activeSources()} empty. This is the mode behind
 *       {@link PureJavaZenohSubscriber#recordedTopics()}.</li>
 * </ul>
 * <p>Observed traffic is sticky in both modes. Once a message has been
 * seen on a key, that key stays in the snapshot, because a message really
 * having arrived is not something a later event can retract.</p>
 *
 * <h2>What this is not</h2>
 * <p>This is not an authoritative topic list, because Zenoh does not have
 * one. It is the union of what several imperfect channels could see. A
 * topic absent from a snapshot may still exist. Treat the result as a
 * lower bound, never as proof of absence.</p>
 *
 * <h2>Threading</h2>
 * <p>Safe from any thread. Updates arrive on the session reader thread;
 * {@link #snapshot()} may be called concurrently from anywhere and never
 * blocks the reader.</p>
 */
public final class TopicRegistry implements AutoCloseable {

    /**
     * Identifies one declaration. Declaration ids are allocated per kind
     * by the declaring peer, so subscriber id 5 and token id 5 are
     * unrelated. The source must be part of the key or undeclaring one
     * would wrongly evict the other.
     */
    private record DeclRef(TopicSource source, long id) { }

    /** Mutable accumulator; converted to an immutable TopicInfo on snapshot. */
    private static final class Entry {
        final String keyExpr;
        final EnumMap<TopicSource, Set<Long>> declIds = new EnumMap<>(TopicSource.class);
        /** Every source that has ever seen this key. Never shrinks. */
        final Set<TopicSource> everSources = EnumSet.noneOf(TopicSource.class);
        long sampleCount;
        long firstSeenMs;
        long lastSeenMs;

        Entry(String keyExpr, long nowMs) {
            this.keyExpr = keyExpr;
            this.firstSeenMs = nowMs;
            this.lastSeenMs = nowMs;
        }

        /** Sources currently backed by evidence. Caller must hold this monitor. */
        Set<TopicSource> liveSources() {
            Set<TopicSource> out = EnumSet.noneOf(TopicSource.class);
            for (Map.Entry<TopicSource, Set<Long>> e : declIds.entrySet()) {
                if (!e.getValue().isEmpty()) out.add(e.getKey());
            }
            if (sampleCount > 0) out.add(TopicSource.OBSERVED_TRAFFIC);
            return out;
        }
    }

    private final ConcurrentHashMap<String, Entry>   entries   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<DeclRef, String> declIndex = new ConcurrentHashMap<>();
    private final CountDownLatch currentComplete = new CountDownLatch(1);
    private final AtomicBoolean  closed          = new AtomicBoolean(false);

    private final String  pattern;
    private final boolean cumulative;

    // Set once by the owning subscriber immediately after construction.
    private volatile Runnable     interestCloser;
    private volatile Subscription trafficSub;

    /** A live registry: topics disappear when their last evidence does. */
    TopicRegistry(String pattern) {
        this(pattern, false);
    }

    TopicRegistry(String pattern, boolean cumulative) {
        this.pattern    = Objects.requireNonNull(pattern, "pattern");
        this.cumulative = cumulative;
    }

    // ----- wiring, called by PureJavaZenohSubscriber ---------------------

    void attachInterest(Runnable closer) { this.interestCloser = closer; }
    void attachTrafficSubscription(Subscription sub) { this.trafficSub = sub; }

    // ----- mutators, called from the session reader thread ---------------

    /** Record a declaration. Idempotent for a repeated (source, id) pair. */
    void recordDeclaration(TopicSource source, long id, String keyExpr) {
        long now = System.currentTimeMillis();
        declIndex.put(new DeclRef(source, id), keyExpr);
        Entry e = entries.computeIfAbsent(keyExpr, k -> new Entry(k, now));
        synchronized (e) {
            e.declIds.computeIfAbsent(source, s -> new HashSet<>()).add(id);
            e.everSources.add(source);
            e.lastSeenMs = now;
        }
    }

    /**
     * Record an undeclaration. The wire message carries only the id, so
     * the key is recovered from the index built at declare time. An id we
     * never saw declared is ignored, which happens legitimately when the
     * registry is started after a peer declared.
     *
     * <p>Does not advance {@code lastSeenMs}: a withdrawal is negative
     * evidence, and the field is meant to answer "when was this last
     * actually there".</p>
     */
    void recordUndeclaration(TopicSource source, long id) {
        String keyExpr = declIndex.remove(new DeclRef(source, id));
        if (keyExpr == null) return;
        Entry e = entries.get(keyExpr);
        if (e == null) return;
        synchronized (e) {
            Set<Long> ids = e.declIds.get(source);
            if (ids != null) ids.remove(id);
            // A cumulative registry keeps everything it ever saw; the entry
            // simply stops being active. A live one drops the entry once
            // nothing is left to justify it. Observed traffic is sticky in
            // both modes: having once seen a message on a key is not
            // something a later withdrawal can retract.
            if (!cumulative && e.sampleCount == 0 && e.liveSources().isEmpty()) {
                entries.remove(keyExpr, e);
            }
        }
    }

    /** Record one observed message on a key. */
    void recordSample(String keyExpr) {
        long now = System.currentTimeMillis();
        Entry e = entries.computeIfAbsent(keyExpr, k -> new Entry(k, now));
        synchronized (e) {
            e.sampleCount++;
            e.everSources.add(TopicSource.OBSERVED_TRAFFIC);
            e.lastSeenMs = now;
        }
    }

    /** Called when the router signals the end of the current-state replay. */
    void markCurrentComplete() {
        currentComplete.countDown();
    }

    // ----- read API ------------------------------------------------------

    /**
     * An immutable snapshot of everything known, sorted by key expression.
     *
     * <p>In a live registry every entry is active. In a cumulative one the
     * result also includes topics that have since gone away, flagged with
     * {@link TopicInfo#active()} false; filter with {@link #activeOnly()}
     * if you only want what is there now.</p>
     *
     * <p>Never blocks and never throws. Calling it before
     * {@link #awaitCurrent(Duration)} returns is legal and simply yields a
     * partial picture.</p>
     */
    public List<TopicInfo> snapshot() {
        List<TopicInfo> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            synchronized (e) {
                if (e.everSources.isEmpty()) continue;   // cannot happen; cheap guard
                out.add(new TopicInfo(e.keyExpr, e.everSources, e.liveSources(),
                        e.firstSeenMs, e.lastSeenMs, e.sampleCount));
            }
        }
        out.sort(null);   // TopicInfo is Comparable by keyExpr
        return List.copyOf(out);
    }

    /**
     * {@link #snapshot()} filtered to topics whose evidence still stands.
     *
     * <p>On a live registry this is the same as {@code snapshot()}, since
     * nothing inactive is retained there.</p>
     */
    public List<TopicInfo> activeOnly() {
        return snapshot().stream().filter(TopicInfo::active).toList();
    }

    /**
     * True if this registry accumulates rather than tracking live state, so
     * a topic once seen is reported for the rest of the run.
     */
    public boolean isCumulative() {
        return cumulative;
    }

    /**
     * Block until the router finishes replaying current declarations, or
     * the timeout expires.
     *
     * <p>This covers the declaration channel only. Observed traffic has no
     * completion signal by nature, since there is no way to know a silent
     * topic will stay silent, so a caller wanting traffic must simply wait
     * as long as it is willing to.</p>
     *
     * @return true if the replay completed, false on timeout
     */
    public boolean awaitCurrent(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        return currentComplete.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The key expression pattern this registry was opened with. */
    public String pattern() { return pattern; }

    /** Number of topics currently known. */
    public int size() { return entries.size(); }

    /** True until {@link #close()} is called. */
    public boolean isOpen() { return !closed.get(); }

    /**
     * Stop discovery. Sends a FINAL INTEREST and closes the traffic
     * subscription if one was opened. Idempotent. Any snapshot already
     * taken remains valid, and {@link #snapshot()} keeps working after
     * close, simply frozen at the last known state.
     */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Subscription sub = trafficSub;
        if (sub != null) {
            try { sub.close(); } catch (RuntimeException ignored) { /* best effort */ }
        }
        Runnable closer = interestCloser;
        if (closer != null) {
            try { closer.run(); } catch (RuntimeException ignored) { /* best effort */ }
        }
        // Release anyone parked in awaitCurrent so close() cannot strand a
        // caller that is waiting for a replay which will now never finish.
        currentComplete.countDown();
    }

    @Override public String toString() {
        return "TopicRegistry{pattern='" + pattern + "', topics=" + entries.size()
                + ", mode=" + (cumulative ? "cumulative" : "live")
                + ", open=" + isOpen() + "}";
    }
}

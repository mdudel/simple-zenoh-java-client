/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of a clean-room pure-Java implementation of the Eclipse
 * Zenoh 1.x wire protocol. It is not a copy of any Zenoh source code.
 */
package io.mdudel.zenoh.purejava;

import io.mdudel.zenoh.purejava.session.Sample;
import io.mdudel.zenoh.purejava.session.SessionException;
import io.mdudel.zenoh.purejava.session.SessionState;
import io.mdudel.zenoh.purejava.session.Subscription;
import io.mdudel.zenoh.purejava.session.ZenohSession;
import io.mdudel.zenoh.purejava.transport.TcpTransport;
import io.mdudel.zenoh.purejava.transport.TlsConfig;
import io.mdudel.zenoh.purejava.transport.TlsTransport;
import io.mdudel.zenoh.purejava.transport.Transport;
import io.mdudel.zenoh.purejava.transport.WsTransport;
import io.mdudel.zenoh.purejava.wire.KeyExpr;
import io.mdudel.zenoh.purejava.wire.messages.Declare;
import io.mdudel.zenoh.purejava.wire.messages.DeclareQueryable;
import io.mdudel.zenoh.purejava.wire.messages.DeclareSubscriber;
import io.mdudel.zenoh.purejava.wire.messages.DeclareToken;
import io.mdudel.zenoh.purejava.wire.messages.Interest;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Pure-Java Zenoh 1.x subscriber client. Sibling of
 * {@link PureJavaZenohPublisher}: same builder shape, same endpoint parser,
 * same TLS options. No JNI. No native binaries. Zero runtime dependencies
 * beyond JDK 17.
 *
 * <h2>Two ways to consume samples</h2>
 * <ul>
 * <li><b>Pull</b> &mdash; call {@link #subscribe(String)}, then
 * {@link Subscription#take()} / {@link Subscription#poll(long, java.util.concurrent.TimeUnit)}
 * on the returned handle. Caller controls threading, backpressure is safe.</li>
 * <li><b>Push (callback)</b> &mdash; call
 * {@link #subscribeAndConsume(String, Consumer)} or
 * {@link Subscription#forEach(Consumer)} on the returned handle. Callback runs
 * on a fresh daemon thread; caller returns immediately.</li>
 * </ul>
 *
 * <p>
 * Under the hood both use the same {@link Subscription} primitive; the callback
 * form is a thin wrapper around the pull queue.</p>
 *
 * <h2>Finding out what topics exist</h2>
 * <p>
 * Zenoh has no topic registry, so there is no authoritative list to fetch.
 * What this client can do is merge several partial views, and it reports
 * where each topic came from so a caller can weigh the evidence; see
 * {@link TopicSource}. Three ways in, in rough order of usefulness:</p>
 * <ul>
 * <li><b>Record continuously.</b> Set {@code recordTopics(true)} on the
 * builder, then call {@link #recordedTopics()} whenever you like. The
 * answer covers everything seen since {@link #start()}, including topics
 * that have since gone away, and it never blocks.</li>
 * <li><b>Ask once.</b> {@link #listTopics(String, java.time.Duration)}
 * blocks for a bounded window and returns what it found in that window
 * only.</li>
 * <li><b>Poll a live view.</b> {@link #topics(String, boolean)} returns a
 * registry reflecting what exists right now, which you read with
 * {@link TopicRegistry#snapshot()}.</li>
 * </ul>
 * <p>The older {@link #discoverTopics(String, TopicListener)} remains for
 * callers that want the raw subscriber-declaration callbacks.</p>
 *
 * <h2>Endpoint syntax</h2>
 * <p>
 * Identical to {@link PureJavaZenohPublisher}. Accepts both Zenoh classic
 * {@code proto/host:port} form and URI form. Auto-detects PEM vs PKCS12 by file
 * extension for TLS.</p>
 *
 * <h2>Threading</h2>
 * <ul>
 * <li>{@link #start()} single-threaded (call once).</li>
 * <li>{@link #subscribe(String)} safe from any thread &mdash; delegates to
 * {@link ZenohSession#declareSubscriber(KeyExpr)}.</li>
 * <li>{@link #close()} idempotent from any thread; closes all live
 * subscriptions in the order they were created.</li>
 * </ul>
 */
public final class PureJavaZenohSubscriber implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PureJavaZenohSubscriber.class.getName());

    // ----- config (immutable) --------------------------------------------
    private final String connectEndpoint;
    private final String org;
    private final String rootCaCertPath;
    private final String clientCertPath;
    private final String clientKeyPath;
    private final char[] keyStorePassword;
    private final boolean verifyHostname;
    private final long leaseMs;
    private final boolean recordTopics;
    private final String recordTopicsPattern;
    private final boolean recordTopicTraffic;

    // ----- runtime state -------------------------------------------------
    private volatile ZenohSession session;
    private volatile Transport transport;
    private volatile String lastError;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    /** Always-on cumulative registry; null unless recording was started. */
    private volatile TopicRegistry recorder;

    private PureJavaZenohSubscriber(Builder b) {
        this.connectEndpoint = nz(b.connectEndpoint);
        this.org = nz(b.org);
        this.rootCaCertPath = nz(b.rootCaCertPath);
        this.clientCertPath = nz(b.clientCertPath);
        this.clientKeyPath = nz(b.clientKeyPath);
        this.keyStorePassword = b.keyStorePassword;
        this.verifyHostname = b.verifyHostname;
        this.leaseMs = b.leaseMs;
        this.recordTopics = b.recordTopics;
        this.recordTopicsPattern = nz(b.recordTopicsPattern).isEmpty()
                ? "**" : b.recordTopicsPattern;
        this.recordTopicTraffic = b.recordTopicTraffic;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ----- accessors -----------------------------------------------------
    public String getConnectEndpoint() {
        return connectEndpoint;
    }

    public String getOrg() {
        return org;
    }

    public boolean isActive() {
        return session != null && session.state() == SessionState.OPEN;
    }

    /**
     * Cumulative count of samples delivered across ALL live subscriptions on
     * this instance.
     */
    public long getReceivedCount() {
        long total = 0;
        for (Subscription sub : subscriptions) {
            total += sub.receivedCount();
        }
        return total;
    }

    public String getLastError() {
        return lastError;
    }

    // ----- lifecycle -----------------------------------------------------
    /**
     * Build the transport, run the INIT/OPEN handshake, be ready to subscribe.
     */
    public void start() throws IOException {
        if (isActive()) {
            return;
        }
        LOG.log(Level.INFO,
                "PureJavaZenohSubscriber.start() endpoint={0} org={1} verifyHostname={2} lease={3}ms",
                connectEndpoint, org, verifyHostname, leaseMs);
        Transport t;
        try {
            t = buildTransport();
        } catch (RuntimeException | IOException e) {
            lastError = "transport build failed: " + e.getMessage();
            throw wrapAsIo(e, lastError);
        }
        ZenohSession s = ZenohSession.builder(t)
                .leaseMs(leaseMs)
                .autoConnect(true)
                .build();
        try {
            s.open();
        } catch (SessionException e) {
            lastError = "session open failed: " + e.getMessage();
            s.close();
            t.close();
            throw new IOException(lastError, e);
        }
        this.transport = t;
        this.session = s;

        if (recordTopics) {
            // Deliberately not fatal. A router may allow the session but
            // refuse the discovery interest or the wildcard subscription,
            // and that should not stop the caller subscribing to the keys
            // they actually came for.
            try {
                startRecordingTopics();
            } catch (IOException | RuntimeException e) {
                lastError = "topic recording failed to start: " + e.getMessage();
                LOG.log(Level.WARNING, lastError
                        + "; continuing without it (recordedTopics() will throw)");
            }
        }
    }

    /**
     * Close all subscriptions, then the session, then the transport.
     * Idempotent.
     */
    public void stop() {
        // Before the subscriptions, so the recorder gets a chance to send
        // its FINAL interest while the session is still open.
        stopRecordingTopics();
        for (Subscription sub : subscriptions) {
            try {
                sub.close();
            } catch (RuntimeException ignored) {
            }
        }
        subscriptions.clear();
        ZenohSession s = session;
        if (s != null) {
            s.close();
            session = null;
        }
        transport = null;
        LOG.log(Level.INFO, "PureJavaZenohSubscriber.stop()");
    }

    @Override
    public void close() {
        stop();
    }

    // ----- subscribe -----------------------------------------------------
    /**
     * Declare a subscription and return the handle. Key expression may contain
     * wildcards; see {@link KeyExpr}. If {@code org} is set on the builder, it
     * is prepended to {@code keyExpr} using
     * {@link KeyExpr#resolveKey(String, String)} for symmetry with the
     * publisher facade.
     */
    public Subscription subscribe(String keyExpr) throws IOException {
        Objects.requireNonNull(keyExpr, "keyExpr");
        ZenohSession s = session;
        if (s == null || s.state() != SessionState.OPEN) {
            throw new IOException("PureJavaZenohSubscriber is not started");
        }
        String effective = KeyExpr.resolveKey(org, keyExpr);
        try {
            Subscription sub = s.declareSubscriber(KeyExpr.of(effective));
            subscriptions.add(sub);
            return sub;
        } catch (SessionException e) {
            lastError = "subscribe failed: " + e.getMessage();
            throw new IOException(lastError, e);
        }
    }

    /**
     * Sugar: subscribe and attach a callback in one call. The callback runs on
     * a fresh daemon thread and does not block the caller; caller keeps the
     * returned {@link Subscription} to close it later.
     */
    public Subscription subscribeAndConsume(String keyExpr, Consumer<Sample> onSample) throws IOException {
        Subscription sub = subscribe(keyExpr);
        sub.forEach(onSample);
        return sub;
    }

    // ----- topic discovery ---------------------------------------------
    /**
     * A running topic-discovery handle. Returned by
     * {@link #discoverTopics(String, TopicListener)}. Call {@link #close()} to
     * send a FINAL INTEREST and stop receiving updates.
     */
    public final class TopicDiscovery implements AutoCloseable {

        private final long interestId;
        private final java.util.concurrent.atomic.AtomicBoolean closed
                = new java.util.concurrent.atomic.AtomicBoolean(false);

        TopicDiscovery(long id) {
            this.interestId = id;
        }

        public long interestId() {
            return interestId;
        }

        public boolean isOpen() {
            return !closed.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ZenohSession s = session;
            if (s == null) {
                return;
            }
            try {
                s.finalInterest(interestId);
            } catch (SessionException e) {
                LOG.log(Level.DEBUG, () -> "finalInterest failed: " + e.getMessage());
            }
        }
    }

    /**
     * Callback surface for topic discovery. Fires once per DECLARE record the
     * router returns matching an INTEREST.
     *
     * <ul>
     * <li>{@link #onTopicDeclared(String, long)} — a new subscription exists in
     * the network for the given key expression.</li>
     * <li>{@link #onTopicUndeclared(long)} — a previously-seen subscription is
     * gone.</li>
     * <li>{@link #onDiscoveryComplete()} — the router finished replaying
     * current state; further callbacks (if any) are live updates. Only fires
     * for CURRENT / CURRENT_FUTURE modes.</li>
     * </ul>
     *
     * <p>
     * Default {@code onTopicUndeclared} and {@code onDiscoveryComplete} are
     * no-ops so simple use cases can just implement
     * {@code onTopicDeclared}.</p>
     */
    public interface TopicListener {

        void onTopicDeclared(String keyExpr, long declaredById);

        default void onTopicUndeclared(long declaredById) {
        }

        default void onDiscoveryComplete() {
        }
    }

    /**
     * Discover subscriber topics matching a key expression pattern. Sends a
     * CURRENT_FUTURE INTEREST to the router; the listener fires once per
     * existing DeclareSubscriber matching the pattern, then
     * {@link TopicListener#onDiscoveryComplete()}, then for future
     * subscriptions as they come and go.
     *
     * <p>
     * Note: this returns declarations SEEN BY THE ROUTER, not necessarily the
     * set of keys anyone has ever published under. If there is no subscriber
     * for a key, that key does not appear here even if there is an active
     * publisher for it. To catch publishers directly, subscribe to {@code **}
     * with {@link #subscribeAndConsume} and observe {@link Sample#key()}.</p>
     *
     * @param keyExprPattern KeyExpr pattern to filter on (wildcards welcome;
     * use {@code "**"} to see everything).
     */
    public TopicDiscovery discoverTopics(String keyExprPattern, TopicListener listener) throws IOException {
        Objects.requireNonNull(keyExprPattern, "keyExprPattern");
        Objects.requireNonNull(listener, "listener");
        ZenohSession s = session;
        if (s == null || s.state() != SessionState.OPEN) {
            throw new IOException("PureJavaZenohSubscriber is not started");
        }
        try {
            long id = s.declareInterest(Interest.Mode.CURRENT_FUTURE,
                    Interest.OPT_SUBSCRIBERS, keyExprPattern,
                    (declare, isFinal) -> {
                        if (isFinal) {
                            try {
                                listener.onDiscoveryComplete();
                            } catch (RuntimeException re) {
                                /* absorb */ }
                            return;
                        }
                        Declare.Body body = declare.body();
                        if (body.kind() == Declare.Body.BodyKind.DECLARE_SUBSCRIBER) {
                            DeclareSubscriber ds = body.asDeclareSubscriber();
                            // The wire carries the key as a (scope, suffix)
                            // pair. A nonzero scope references a keyexpr
                            // alias the router interned earlier, so the
                            // suffix alone is only a fragment. Resolve it
                            // rather than reporting a truncated key.
                            String k = s.resolveRemoteKey(ds.keyScope(), ds.keySuffix());
                            if (k == null) {
                                // Alias we have no mapping for. Reporting the
                                // bare suffix here would be worse than
                                // reporting nothing, because the caller
                                // cannot tell a fragment from a real key.
                                LOG.log(Level.WARNING,
                                        "discoverTopics: unresolved keyexpr alias scope={0}"
                                        + " for subscriber id={1}; topic omitted",
                                        ds.keyScope(), ds.id());
                            } else {
                                try {
                                    listener.onTopicDeclared(k, ds.id());
                                } catch (RuntimeException re) {
                                    /* absorb */ }
                            }
                        } else if (body.kind() == Declare.Body.BodyKind.UNDECLARE_SUBSCRIBER) {
                            try {
                                listener.onTopicUndeclared(body.asUndeclareSubscriber().id());
                            } catch (RuntimeException re) {
                                /* absorb */ }
                        }
                    });
            return new TopicDiscovery(id);
        } catch (SessionException e) {
            lastError = "discoverTopics failed: " + e.getMessage();
            throw new IOException(lastError, e);
        }
    }

    /**
     * Sugar: {@code discoverTopics("**", listener)}. Watches for every
     * subscription the router knows about.
     */
    public TopicDiscovery discoverAllTopics(TopicListener listener) throws IOException {
        return discoverTopics("**", listener);
    }

    // ----- topic registry ------------------------------------------------
    /**
     * Open a live {@link TopicRegistry} over every discovery channel this
     * client can reach.
     *
     * <p>Where {@link #discoverTopics(String, TopicListener)} is a raw
     * callback over subscriber declarations alone, this merges four sources
     * into one queryable set: declared subscribers, declared queryables,
     * liveliness tokens, and optionally the keys of messages that actually
     * arrive. Each entry records which sources saw it, so the caller can
     * tell "someone is listening here" from "data is flowing here". See
     * {@link TopicSource} for what each one proves.</p>
     *
     * <p>If {@code org} is set on the builder it is prepended to the
     * pattern, matching {@link #subscribe(String)}. Both channels use the
     * same resulting pattern.</p>
     *
     * <p>Setting {@code observeTraffic} declares a real subscription on the
     * pattern. On a busy network with a broad pattern that can mean a great
     * deal of inbound data, all of which is discarded after its key is
     * noted. It may also be refused by an ACL that allows discovery but not
     * subscription. Pass {@code false} to stay purely passive.</p>
     *
     * <p>The caller owns the returned registry and must {@link
     * TopicRegistry#close()} it. Closing this subscriber also closes any
     * traffic subscription the registry opened.</p>
     *
     * @param keyExprPattern key expression pattern to watch; {@code "**"}
     *                       for everything
     * @param observeTraffic true to also subscribe and record observed keys
     */
    public TopicRegistry topics(String keyExprPattern, boolean observeTraffic) throws IOException {
        return topics(keyExprPattern, observeTraffic, false);
    }

    /**
     * As {@link #topics(String, boolean)}, but with a choice of live or
     * cumulative bookkeeping.
     *
     * <p>A live registry answers "what is out there now" and drops a topic
     * once its last declaration is withdrawn. A cumulative one answers
     * "what has been out there since I started" and never drops anything;
     * departed topics stay in the snapshot with {@link TopicInfo#active()}
     * false. Observed traffic is sticky either way.</p>
     *
     * <p>For a cumulative registry that lives as long as the subscriber
     * does, prefer {@link #startRecordingTopics()} or the
     * {@code recordTopics} builder flag, which own the lifecycle for
     * you.</p>
     *
     * @param cumulative true to accumulate, false to track live state
     */
    public TopicRegistry topics(String keyExprPattern, boolean observeTraffic,
            boolean cumulative) throws IOException {
        Objects.requireNonNull(keyExprPattern, "keyExprPattern");
        ZenohSession s = session;
        if (s == null || s.state() != SessionState.OPEN) {
            throw new IOException("PureJavaZenohSubscriber is not started");
        }
        String effective = KeyExpr.resolveKey(org, keyExprPattern);
        TopicRegistry reg = new TopicRegistry(effective, cumulative);

        long id;
        try {
            id = s.declareInterest(Interest.Mode.CURRENT_FUTURE,
                    Interest.OPT_SUBSCRIBERS | Interest.OPT_QUERYABLES | Interest.OPT_TOKENS,
                    effective,
                    (declare, isFinal) -> {
                        if (isFinal) {
                            reg.markCurrentComplete();
                            return;
                        }
                        Declare.Body body = declare.body();
                        switch (body.kind()) {
                            case DECLARE_SUBSCRIBER -> {
                                DeclareSubscriber d = body.asDeclareSubscriber();
                                record(reg, s, TopicSource.DECLARED_SUBSCRIBER,
                                        d.id(), d.keyScope(), d.keySuffix());
                            }
                            case UNDECLARE_SUBSCRIBER ->
                                reg.recordUndeclaration(TopicSource.DECLARED_SUBSCRIBER,
                                        body.asUndeclareSubscriber().id());
                            case DECLARE_QUERYABLE -> {
                                DeclareQueryable d = body.asDeclareQueryable();
                                record(reg, s, TopicSource.DECLARED_QUERYABLE,
                                        d.id(), d.keyScope(), d.keySuffix());
                            }
                            case UNDECLARE_QUERYABLE ->
                                reg.recordUndeclaration(TopicSource.DECLARED_QUERYABLE,
                                        body.asUndeclareQueryable().id());
                            case DECLARE_TOKEN -> {
                                DeclareToken d = body.asDeclareToken();
                                record(reg, s, TopicSource.LIVENESS_TOKEN,
                                        d.id(), d.keyScope(), d.keySuffix());
                            }
                            case UNDECLARE_TOKEN ->
                                reg.recordUndeclaration(TopicSource.LIVENESS_TOKEN,
                                        body.asUndeclareToken().id());
                            default -> {
                                // DeclareKeyExpr and the RAW fallback (which
                                // still carries DeclareFinal, id 0x1A) say
                                // nothing about topics. The session applies
                                // keyexpr mappings itself before we see this.
                            }
                        }
                    });
        } catch (SessionException e) {
            lastError = "topics() interest failed: " + e.getMessage();
            throw new IOException(lastError, e);
        }
        reg.attachInterest(() -> {
            try {
                s.finalInterest(id);
            } catch (SessionException e) {
                LOG.log(Level.DEBUG, () -> "finalInterest failed: " + e.getMessage());
            }
        });

        if (observeTraffic) {
            try {
                // Deliberately not subscribe(): org is already folded into
                // 'effective', and resolveKey is not idempotent for a
                // non-empty org.
                Subscription sub = s.declareSubscriber(KeyExpr.of(effective));
                subscriptions.add(sub);
                sub.forEach(sample -> reg.recordSample(sample.key()));
                reg.attachTrafficSubscription(sub);
            } catch (SessionException e) {
                // Do not leak the interest we just declared.
                reg.close();
                lastError = "topics() traffic subscription failed: " + e.getMessage();
                throw new IOException(lastError, e);
            }
        }
        return reg;
    }

    /**
     * Resolve a wire key and hand it to the registry, or log and drop it.
     *
     * <p>A nonzero scope names a keyexpr alias the router interned earlier.
     * If this session never saw the mapping, the suffix on its own is a
     * fragment, and reporting a fragment is worse than reporting nothing
     * because the caller cannot tell the two apart.</p>
     */
    private static void record(TopicRegistry reg, ZenohSession s, TopicSource source,
            long declId, int keyScope, String keySuffix) {
        String k = s.resolveRemoteKey(keyScope, keySuffix);
        if (k == null) {
            LOG.log(Level.WARNING,
                    "topics: unresolved keyexpr alias scope={0} for {1} id={2}; topic omitted",
                    keyScope, source, declId);
            return;
        }
        reg.recordDeclaration(source, declId, k);
    }

    /**
     * Blocking convenience over {@link #topics(String, boolean)}: collect
     * topics for up to {@code timeout}, then return the merged, sorted list.
     *
     * <p>Waits for the router to finish replaying current declarations, then
     * spends whatever remains of the budget observing live traffic, then
     * closes everything. Traffic observation is always on here, since a
     * caller wanting declarations only has no reason to block.</p>
     *
     * <p>The timeout is mandatory and there is no "complete" state to wait
     * for instead. The declaration replay does finish, but traffic
     * observation cannot: a topic that stays silent is indistinguishable
     * from one that does not exist. Longer timeouts therefore find strictly
     * more, and the result is always a lower bound.</p>
     *
     * <p>If the calling thread is interrupted the wait ends early, the
     * interrupt flag is restored, and whatever was gathered so far is
     * returned rather than discarded.</p>
     *
     * @param keyExprPattern key expression pattern; {@code "**"} for everything
     * @param timeout        how long to gather; must be positive
     * @return topics sorted by key expression, never null, possibly empty
     */
    public List<TopicInfo> listTopics(String keyExprPattern, Duration timeout) throws IOException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
        try (TopicRegistry reg = topics(keyExprPattern, true)) {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            try {
                reg.awaitCurrent(timeout);
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs > 0) {
                    Thread.sleep(remainingMs);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return reg.snapshot();
        }
    }

    /**
     * Sugar: {@code listTopics("**", timeout)}. Every topic reachable by any
     * channel.
     */
    public List<TopicInfo> listAllTopics(Duration timeout) throws IOException {
        return listTopics("**", timeout);
    }

    // ----- always-on topic recorder --------------------------------------
    /**
     * Start accumulating topics for the life of this subscriber, so a later
     * {@link #recordedTopics()} reports everything seen since recording
     * began rather than only what happens to be there at that instant.
     *
     * <p>Uses the pattern and traffic setting from the builder
     * ({@code recordTopicsPattern}, default {@code "**"}, and
     * {@code recordTopicTraffic}, default true). Setting the
     * {@code recordTopics} builder flag calls this automatically from
     * {@link #start()}, which is the usual way in.</p>
     *
     * <p>Idempotent: calling it again while recording returns the running
     * registry rather than opening a second one. The subscriber owns the
     * registry and closes it in {@link #stop()}, so callers should not
     * close it themselves.</p>
     *
     * @return the running cumulative registry
     * @throws IOException if the subscriber is not started, or the router
     *                     refuses the interest or the traffic subscription
     */
    public TopicRegistry startRecordingTopics() throws IOException {
        return startRecordingTopics(recordTopicsPattern, recordTopicTraffic);
    }

    /**
     * As {@link #startRecordingTopics()} but with an explicit pattern and
     * traffic setting, overriding the builder values.
     *
     * <p>If a recorder is already running, the existing one is returned
     * unchanged and these arguments are ignored. Stop and restart the
     * subscriber to record a different pattern.</p>
     */
    public synchronized TopicRegistry startRecordingTopics(String keyExprPattern,
            boolean observeTraffic) throws IOException {
        TopicRegistry running = recorder;
        if (running != null && running.isOpen()) {
            return running;
        }
        TopicRegistry reg = topics(keyExprPattern, observeTraffic, true);
        recorder = reg;
        LOG.log(Level.INFO,
                "topic recording started: pattern={0} observeTraffic={1}",
                reg.pattern(), observeTraffic);
        return reg;
    }

    /** Stop the always-on recorder. Idempotent; no-op if not recording. */
    public synchronized void stopRecordingTopics() {
        TopicRegistry reg = recorder;
        recorder = null;
        if (reg != null) {
            reg.close();
            LOG.log(Level.INFO, "topic recording stopped");
        }
    }

    /** True if the always-on recorder is running. */
    public boolean isRecordingTopics() {
        TopicRegistry reg = recorder;
        return reg != null && reg.isOpen();
    }

    /**
     * The always-on recorder, or null if recording was never started.
     *
     * <p>Exposed for callers wanting {@link TopicRegistry#awaitCurrent} or
     * {@link TopicRegistry#activeOnly()}. Do not close it; use
     * {@link #stopRecordingTopics()}.</p>
     */
    public TopicRegistry topicRegistry() {
        return recorder;
    }

    /**
     * Every topic seen since recording started, sorted by key expression.
     *
     * <p>Unlike {@link #listTopics(String, Duration)} this does not block
     * and does not open anything: the recorder has been listening all
     * along, so the answer is already there. Topics that have since gone
     * away are included with {@link TopicInfo#active()} false. Use
     * {@link TopicRegistry#activeOnly()} on {@link #topicRegistry()} for
     * only what is still there.</p>
     *
     * <p>The longer the subscriber has been running, the more complete
     * this is, which is the point: a topic that was busy an hour ago and
     * silent now still shows up.</p>
     *
     * @throws IllegalStateException if recording was never started, since
     *         an empty list would be indistinguishable from "found nothing"
     */
    public List<TopicInfo> recordedTopics() {
        TopicRegistry reg = recorder;
        if (reg == null) {
            throw new IllegalStateException(
                    "topic recording is not enabled; set recordTopics(true) on the "
                    + "builder or call startRecordingTopics() first");
        }
        return reg.snapshot();
    }

    // ----- endpoint parsing + transport factory --------------------------
    // (Same logic as PureJavaZenohPublisher; kept in sync deliberately.
    //  A shared TransportFactory helper would be nicer -- see followup below.)
    Transport buildTransport() throws IOException {
        if (connectEndpoint.isEmpty()) {
            throw new IOException("connectEndpoint is required");
        }
        String scheme;
        String host;
        int port;
        String path = "";

        if (connectEndpoint.contains("://")) {
            URI uri = URI.create(connectEndpoint);
            scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            host = uri.getHost();
            port = uri.getPort();
            path = uri.getRawPath() == null ? "" : uri.getRawPath();
        } else {
            int slash = connectEndpoint.indexOf('/');
            if (slash <= 0) {
                throw new IOException("connectEndpoint must be proto/host:port or a URI: "
                        + connectEndpoint);
            }
            scheme = connectEndpoint.substring(0, slash).toLowerCase();
            String hostPort = connectEndpoint.substring(slash + 1);
            int colon = hostPort.indexOf(':');
            if (colon <= 0 || colon == hostPort.length() - 1) {
                throw new IOException("connectEndpoint must contain host:port: "
                        + connectEndpoint);
            }
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException nfe) {
                throw new IOException("invalid port in endpoint: " + connectEndpoint);
            }
        }
        if (host == null || host.isEmpty()) {
            throw new IOException("host missing from endpoint: " + connectEndpoint);
        }
        if (port < 1 || port > 65535) {
            throw new IOException("port out of range in endpoint: " + connectEndpoint);
        }

        return switch (scheme) {
            case "tcp" ->
                new TcpTransport(host, port);
            case "tls" ->
                new TlsTransport(host, port, buildTlsConfig());
            case "ws" ->
                new WsTransport(URI.create("ws://" + host + ":" + port + path), null);
            case "wss" ->
                new WsTransport(
                URI.create("wss://" + host + ":" + port + path), buildTlsConfig());
            default ->
                throw new IOException("unsupported endpoint scheme '" + scheme
                        + "'; expected one of tcp, tls, ws, wss");
        };
    }

    private TlsConfig buildTlsConfig() throws IOException {
        TlsConfig.Builder tb = TlsConfig.builder().verifyHostname(verifyHostname);

        if (!rootCaCertPath.isEmpty()) {
            Path p = requireReadable(rootCaCertPath, "rootCaCertPath");
            if (isPem(p)) {
                tb.trustStorePem(p);
            } else if (isPkcs12(p)) {
                tb.trustStore(p, keyStorePassword);
            } else {
                throw new IOException(
                        "rootCaCertPath must end in .pem/.crt/.cer (PEM) or .p12/.pfx (PKCS12): "
                        + rootCaCertPath);
            }
        } else {
            tb.trustSystem();
        }

        if (!clientCertPath.isEmpty() && !clientKeyPath.isEmpty()) {
            Path cert = requireReadable(clientCertPath, "clientCertPath");
            Path key = requireReadable(clientKeyPath, "clientKeyPath");
            if (isPem(cert) && isPem(key)) {
                tb.keyStorePem(cert, key);
            } else if (isPkcs12(cert) && cert.equals(key)) {
                tb.keyStore(cert, keyStorePassword, keyStorePassword);
            } else if (isPkcs12(cert) || isPkcs12(key)) {
                throw new IOException(
                        "for PKCS12 client keystore, set clientCertPath and clientKeyPath "
                        + "to the SAME .p12 file (or leave one empty); PEM keys need "
                        + "BOTH clientCertPath (.pem) AND clientKeyPath (.key/.pem) set");
            } else {
                throw new IOException(
                        "clientCertPath / clientKeyPath must be PEM pair (.pem/.crt/.cer + .pem/.key) "
                        + "or PKCS12 (.p12/.pfx)");
            }
        } else if (!clientCertPath.isEmpty() || !clientKeyPath.isEmpty()) {
            String path = !clientCertPath.isEmpty() ? clientCertPath : clientKeyPath;
            Path p = requireReadable(path,
                    !clientCertPath.isEmpty() ? "clientCertPath" : "clientKeyPath");
            if (isPkcs12(p)) {
                tb.keyStore(p, keyStorePassword, keyStorePassword);
            } else {
                throw new IOException(
                        "PEM client authentication requires BOTH clientCertPath (.pem) AND "
                        + "clientKeyPath (.key or .pem) to be set; got only " + path);
            }
        }
        return tb.build();
    }

    private static Path requireReadable(String pathStr, String argName) throws IOException {
        Path p = Paths.get(pathStr);
        if (!Files.isReadable(p)) {
            throw new IOException(argName + " not readable: " + pathStr);
        }
        return p;
    }

    private static boolean isPem(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".pem") || n.endsWith(".crt") || n.endsWith(".cer") || n.endsWith(".key");
    }

    private static boolean isPkcs12(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".p12") || n.endsWith(".pfx");
    }

    private static IOException wrapAsIo(Throwable e, String message) {
        if (e instanceof IOException io) {
            return io;
        }
        return new IOException(message, e);
    }

    // ----- builder -------------------------------------------------------
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder mirroring {@link PureJavaZenohPublisher.Builder} so
     * switching between the two facades at a call site is a one-line change.
     */
    public static final class Builder {

        private String connectEndpoint = "";
        private String org = "";
        private String rootCaCertPath = "";
        private String clientCertPath = "";
        private String clientKeyPath = "";
        private char[] keyStorePassword = "changeit".toCharArray();
        private boolean verifyHostname = true;
        private long leaseMs = ZenohSession.DEFAULT_LEASE_MS;
        private boolean recordTopics = false;
        private String recordTopicsPattern = "**";
        private boolean recordTopicTraffic = true;

        public Builder connectEndpoint(String v) {
            this.connectEndpoint = v;
            return this;
        }

        public Builder org(String v) {
            this.org = v;
            return this;
        }

        public Builder rootCaCertPath(String v) {
            this.rootCaCertPath = v;
            return this;
        }

        public Builder clientCertPath(String v) {
            this.clientCertPath = v;
            return this;
        }

        public Builder clientKeyPath(String v) {
            this.clientKeyPath = v;
            return this;
        }

        public Builder keyStorePassword(char[] v) {
            this.keyStorePassword = v;
            return this;
        }

        public Builder verifyHostname(boolean v) {
            this.verifyHostname = v;
            return this;
        }

        public Builder leaseMs(long v) {
            this.leaseMs = v;
            return this;
        }

        /**
         * Record every topic seen, for as long as this subscriber runs.
         *
         * <p>{@link #start()} opens a cumulative {@link TopicRegistry} and
         * leaves it running, so {@link #recordedTopics()} can answer at any
         * time with everything seen since start, including topics that
         * have since gone away. Default false.</p>
         *
         * <p>If the registry cannot be opened, start() logs a WARNING and
         * carries on rather than failing, since ordinary subscribing
         * should not depend on discovery being permitted.</p>
         */
        public Builder recordTopics(boolean v) {
            this.recordTopics = v;
            return this;
        }

        /**
         * Key expression pattern the recorder watches. Default {@code "**"}
         * (everything). Narrow it to cut the traffic volume on a busy
         * network. Ignored unless {@link #recordTopics(boolean)} is set.
         */
        public Builder recordTopicsPattern(String v) {
            this.recordTopicsPattern = v;
            return this;
        }

        /**
         * Whether the recorder also subscribes to its pattern to catch keys
         * that carry data but that nobody declared. Default true.
         *
         * <p>Set false to stay passive, which avoids pulling in every
         * matching message and works where an ACL permits discovery but
         * not subscription. The cost is losing the one source that proves
         * data is actually flowing. Ignored unless
         * {@link #recordTopics(boolean)} is set.</p>
         */
        public Builder recordTopicTraffic(boolean v) {
            this.recordTopicTraffic = v;
            return this;
        }

        public PureJavaZenohSubscriber build() {
            return new PureJavaZenohSubscriber(this);
        }
    }

    // ----- CLI ----------------------------------------------------------
    /**
     * CLI: subscribe to a key expression and print each received message as a
     * line on stdout. Runs until interrupted (Ctrl-C) or until an optional
     * {@code timeoutSeconds} elapses.
     *
     * <p>
     * Usage:</p>
     * <pre>
     * java -jar pure-java-simple-subscriber.jar
     * java -jar pure-java-simple-subscriber.jar tcp/router:7447 demo/**
     * java -jar pure-java-simple-subscriber.jar tcp/router:7447 demo/** 30
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String keyExpr = args.length > 1 ? args[1] : "demo/**";
        long timeoutSeconds = args.length > 2 ? Long.parseLong(args[2]) : 0;   // 0 = forever

        System.out.println("[pure-java-subscriber] endpoint=" + endpoint + " key=" + keyExpr
                + (timeoutSeconds > 0 ? " timeout=" + timeoutSeconds + "s" : " (Ctrl-C to stop)"));

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));

        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint(endpoint)
                .build()) {
            sub.start();
            System.out.println("[pure-java-subscriber] session OPEN");
            sub.subscribeAndConsume(keyExpr, sample
                    -> System.out.println("[pure-java-subscriber] "
                            + sample.key() + " -> " + sample.payloadAsString()));
            if (timeoutSeconds > 0) {
                stop.await(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                stop.await();
            }
            System.out.println("[pure-java-subscriber] shutting down"
                    + " (received=" + sub.getReceivedCount() + ")");
        }
    }

}

/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One discovered topic, as reported by {@link TopicRegistry#snapshot()}.
 *
 * <p>An immutable point-in-time view. Taking a second snapshot produces
 * fresh instances rather than mutating these, so a snapshot can be held,
 * sorted, or serialised without worrying about the registry changing
 * underneath it.</p>
 *
 * <h2>Ever seen versus still there</h2>
 * <p>{@link #sources()} is cumulative and never shrinks: it is every
 * channel that has observed this topic since the registry picked it up.
 * {@link #activeSources()} is the subset whose evidence is still standing
 * right now. The two differ once a declaration is withdrawn, which only
 * becomes visible in a cumulative registry, because a live one drops the
 * entry outright. See {@link TopicRegistry} for that distinction.</p>
 *
 * @param keyExpr       the fully resolved key expression, with any router
 *                      keyexpr alias already expanded
 * @param sources       every observation channel that has seen this topic;
 *                      never empty, and see {@link TopicSource} for what
 *                      each one does and does not prove
 * @param activeSources the subset of {@code sources} whose evidence still
 *                      stands. Empty means the topic was seen earlier in
 *                      the run and has since gone away
 * @param firstSeenMs   wall-clock time this topic was first seen, in
 *                      milliseconds since the epoch
 * @param lastSeenMs    wall-clock time of the most recent positive
 *                      evidence, in milliseconds since the epoch. A
 *                      withdrawal does not advance it, so on an inactive
 *                      topic this is when it was last actually there
 * @param sampleCount   how many messages were actually observed on this
 *                      key. Zero when the topic is known only from
 *                      declarations, which is the common case for a topic
 *                      nobody published to during the window
 */
public record TopicInfo(
        String           keyExpr,
        Set<TopicSource> sources,
        Set<TopicSource> activeSources,
        long             firstSeenMs,
        long             lastSeenMs,
        long             sampleCount) implements Comparable<TopicInfo> {

    public TopicInfo {
        Objects.requireNonNull(keyExpr, "keyExpr");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(activeSources, "activeSources");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "a TopicInfo must carry at least one source: " + keyExpr);
        }
        if (!sources.containsAll(activeSources)) {
            throw new IllegalArgumentException(
                    "activeSources must be a subset of sources: " + keyExpr
                    + " active=" + activeSources + " all=" + sources);
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must not be negative: " + sampleCount);
        }
        // Defensive immutable copies. EnumSet keeps iteration in declaration
        // order, which makes toString() and any rendered output stable.
        sources       = Collections.unmodifiableSet(EnumSet.copyOf(sources));
        activeSources = activeSources.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(TopicSource.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(activeSources));
    }

    /**
     * Convenience for a topic whose evidence is all current, which is every
     * topic a non-cumulative registry ever reports.
     */
    public static TopicInfo active(String keyExpr, Set<TopicSource> sources,
            long firstSeenMs, long lastSeenMs, long sampleCount) {
        return new TopicInfo(keyExpr, sources, sources,
                firstSeenMs, lastSeenMs, sampleCount);
    }

    /**
     * True if any evidence for this topic still stands.
     *
     * <p>Equivalently: true if a live (non-cumulative) registry would still
     * be reporting this topic. False only happens in a cumulative registry,
     * and means every declaration has been withdrawn and no message was
     * ever observed on the key.</p>
     */
    public boolean active() {
        return !activeSources.isEmpty();
    }

    /** True if a message was actually observed on this key. */
    public boolean hasTraffic() {
        return sources.contains(TopicSource.OBSERVED_TRAFFIC);
    }

    /**
     * True if this topic is known only because something declared an
     * interest in it, with no message ever observed. Useful for telling
     * "a subscriber is waiting here" apart from "data is flowing here".
     */
    public boolean isDeclarationOnly() {
        return !hasTraffic();
    }

    /** Sorts by key expression, so snapshots render in a stable order. */
    @Override public int compareTo(TopicInfo other) {
        return this.keyExpr.compareTo(other.keyExpr);
    }

    @Override public String toString() {
        return "TopicInfo{" + keyExpr
                + ", sources=" + sources
                + (active() ? "" : " (gone)")
                + ", samples=" + sampleCount
                + "}";
    }
}

/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.scouting;

/**
 * Callback interface for {@link PureJavaZenohScout} discovery events.
 *
 * <p>All three methods are default no-ops; implementers override only what
 * they need. Callbacks run on the scout's reader threads (one per network
 * interface). Do not block them for long-running work; hand off to an
 * executor if needed.</p>
 *
 * <p>All callbacks are invoked under the scout's internal registry lock, so
 * a listener sees a totally ordered view per ZID
 * (onDiscover &rarr; onUpdate&hellip; &rarr; onExpire), never interleaved.</p>
 */
public interface ScoutListener {

    /** First HELLO ever seen for this ZID. */
    default void onDiscover(DiscoveredNode node) {}

    /**
     * Later HELLO for a ZID we already know. {@code prev} is the last
     * snapshot; {@code now} carries the refreshed {@link
     * DiscoveredNode#lastSeen()} and, if the sender changed locators
     * mid-run, updated {@link DiscoveredNode#locators()}.
     */
    default void onUpdate(DiscoveredNode prev, DiscoveredNode now) {}

    /**
     * The staleness sweeper has fired for this node - no HELLO within
     * the configured stale window. The registry has already dropped it.
     * A subsequent HELLO would fire {@link #onDiscover} again.
     */
    default void onExpire(DiscoveredNode node) {}
}

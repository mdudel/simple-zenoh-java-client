/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.scouting;

import io.mdudel.zenoh.purejava.wire.WhatAmI;
import io.mdudel.zenoh.purejava.wire.ZenohId;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A Zenoh node observed on the local multicast segment via HELLO messages.
 *
 * <p>Immutable snapshot. {@link PureJavaZenohScout} maintains a live
 * registry keyed by {@link #zid()}; each new HELLO for a known ZID
 * produces a fresh {@code DiscoveredNode} with an updated
 * {@link #lastSeen()} timestamp and (possibly) refreshed locators, and
 * fires {@link ScoutListener#onUpdate}.</p>
 *
 * <p>{@link #locators()} may be empty. That means the source HELLO had its
 * {@code L} flag clear, and the caller should treat {@link #source()} as
 * the implicit single locator for this node.</p>
 */
public record DiscoveredNode(
        ZenohId zid,
        WhatAmI role,
        List<String> locators,
        InetSocketAddress source,
        int protocolVersion,
        Instant firstSeen,
        Instant lastSeen) {

    public DiscoveredNode {
        Objects.requireNonNull(zid,       "zid");
        Objects.requireNonNull(role,      "role");
        Objects.requireNonNull(source,    "source");
        Objects.requireNonNull(firstSeen, "firstSeen");
        Objects.requireNonNull(lastSeen,  "lastSeen");
        locators = locators == null ? List.of() : List.copyOf(locators);
    }

    /**
     * The best locator to use when opening a session to this node.
     * Returns the first explicit locator if any, else a synthesised
     * {@code tcp/<source-host>:<default-port>} string built from
     * {@link #source()} that assumes the default Zenoh TCP port 7447.
     * Non-normative; callers may prefer their own selection strategy.
     */
    public String bestLocator() {
        if (!locators.isEmpty()) return locators.get(0);
        return "tcp/" + source.getHostString() + ":7447";
    }
}

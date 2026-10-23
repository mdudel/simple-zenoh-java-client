/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava;

/**
 * How a topic came to be known to a {@link TopicRegistry}.
 *
 * <p>Zenoh has no topic registry of its own, so there is no single
 * authoritative answer to "what topics exist". Each source below observes
 * a different, partially-overlapping slice of the network, and each has a
 * different failure mode. A {@link TopicInfo} therefore carries the set of
 * sources that saw it, so callers can judge how much to trust an entry
 * rather than being handed an undifferentiated list of strings.</p>
 */
public enum TopicSource {

    /**
     * Some peer has declared a subscription matching this key.
     *
     * <p>Tells you someone is listening. It does NOT tell you anyone is
     * publishing. A key with an active publisher and no subscribers is
     * invisible to this source.</p>
     */
    DECLARED_SUBSCRIBER,

    /**
     * Some peer has declared a queryable on this key, meaning it answers
     * queries there.
     *
     * <p>This client cannot itself issue queries, because Request and
     * Response messages are not implemented, but it can still observe
     * that queryables exist.</p>
     */
    DECLARED_QUERYABLE,

    /**
     * Some peer holds a liveliness token on this key.
     *
     * <p>The highest-confidence source. A token is an explicit assertion
     * of "I am alive at this key", and the router withdraws it
     * automatically when the holder's session drops. Unlike the other
     * declaration sources it reflects deliberate intent rather than an
     * inference, but it only works for applications that bother to
     * declare tokens.</p>
     */
    LIVENESS_TOKEN,

    /**
     * A message was actually received on this key during the observation
     * window.
     *
     * <p>The only source that proves data is really flowing. Its weakness
     * is the mirror image of the declaration sources: a topic that stays
     * silent for the whole window never appears, however busy it may be
     * at other times. It also requires permission to subscribe to the
     * pattern being watched, which an ACL may refuse.</p>
     */
    OBSERVED_TRAFFIC
}

/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.RBuf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Round-trip coverage for the declaration bodies that topic discovery
 * depends on: queryables and liveliness tokens, alongside the subscriber
 * bodies they were modelled on.
 *
 * <p>These bodies are decode-only in practice, since this client never
 * declares a queryable or a token itself. They are still tested through
 * encode so a decoder change cannot silently drift from the layout, and
 * so the byte-identical round-trip property holds for anything this
 * client forwards.</p>
 */
class DeclareBodyRoundTripTest {

    private static Declare roundTrip(Declare.Body body) {
        Declare out = new Declare(null, List.of(), body);
        byte[] bytes = out.encode();
        Declare back = Declare.decode(new RBuf(bytes));
        // Re-encoding the decoded form must reproduce the original bytes.
        assertArrayEquals(bytes, back.encode(), "byte-identical round trip");
        return back;
    }

    @Test void declareQueryableRoundTrips() {
        DeclareQueryable in = new DeclareQueryable(11L, 0, "svc/api", false, List.of());
        Declare back = roundTrip(Declare.Body.of(in));
        assertEquals(Declare.Body.BodyKind.DECLARE_QUERYABLE, back.body().kind());
        assertEquals(in, back.body().asDeclareQueryable());
    }

    @Test void declareQueryableWithScopeAndMappingRoundTrips() {
        // Nonzero scope means the router interned the prefix earlier, so the
        // suffix alone is a fragment. The wire layer must preserve both.
        DeclareQueryable in = new DeclareQueryable(12L, 7, "/tail", true, List.of());
        Declare back = roundTrip(Declare.Body.of(in));
        DeclareQueryable out = back.body().asDeclareQueryable();
        assertEquals(7, out.keyScope());
        assertEquals("/tail", out.keySuffix());
        assertEquals(true, out.senderMapping());
    }

    @Test void undeclareQueryableRoundTrips() {
        Declare back = roundTrip(Declare.Body.of(UndeclareQueryable.of(11L)));
        assertEquals(Declare.Body.BodyKind.UNDECLARE_QUERYABLE, back.body().kind());
        assertEquals(11L, back.body().asUndeclareQueryable().id());
    }

    @Test void declareTokenRoundTrips() {
        DeclareToken in = new DeclareToken(21L, 0, "live/svc/node-1", false, List.of());
        Declare back = roundTrip(Declare.Body.of(in));
        assertEquals(Declare.Body.BodyKind.DECLARE_TOKEN, back.body().kind());
        assertEquals(in, back.body().asDeclareToken());
    }

    @Test void undeclareTokenRoundTrips() {
        Declare back = roundTrip(Declare.Body.of(UndeclareToken.of(21L)));
        assertEquals(Declare.Body.BodyKind.UNDECLARE_TOKEN, back.body().kind());
        assertEquals(21L, back.body().asUndeclareToken().id());
    }

    @Test void subscriberBodiesStillRoundTrip() {
        Declare d = roundTrip(Declare.Body.of(
                new DeclareSubscriber(31L, 0, "s/k", false, List.of())));
        assertEquals(Declare.Body.BodyKind.DECLARE_SUBSCRIBER, d.body().kind());

        Declare u = roundTrip(Declare.Body.of(UndeclareSubscriber.of(31L)));
        assertEquals(Declare.Body.BodyKind.UNDECLARE_SUBSCRIBER, u.body().kind());
    }

    @Test void queryableAndTokenIdsAreIndependentSpaces() {
        // Same numeric id, different body kinds. Nothing in the wire layer
        // ties them together; the registry must not either.
        Declare q = roundTrip(Declare.Body.of(UndeclareQueryable.of(5L)));
        Declare t = roundTrip(Declare.Body.of(UndeclareToken.of(5L)));
        assertEquals(5L, q.body().asUndeclareQueryable().id());
        assertEquals(5L, t.body().asUndeclareToken().id());
        org.junit.jupiter.api.Assertions.assertNotEquals(q.body().kind(), t.body().kind());
    }

    @Test void declarationIdsMustFitU32() {
        assertThrows(IllegalArgumentException.class,
                () -> UndeclareToken.of(0x1_0000_0000L));
        assertThrows(IllegalArgumentException.class,
                () -> UndeclareQueryable.of(-1L));
    }

    @Test void accessorsReturnNullForTheWrongBodyKind() {
        // Matches the pre-existing accessors on this class: they are null
        // on a mismatch rather than throwing, so callers must switch on
        // kind() first. Pinned here so the new accessors cannot drift from
        // the old ones.
        Declare.Body sub = Declare.Body.of(
                new DeclareSubscriber(1L, 0, "a", false, List.of()));
        assertNull(sub.asDeclareToken());
        assertNull(sub.asDeclareQueryable());
        assertNull(sub.asUndeclareToken());
        assertNotNull(sub.asDeclareSubscriber());
    }
}

/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registry bookkeeping, driven directly rather than over a live session.
 * The mutators are package-private and fed by the session reader thread in
 * production, so this test stands in for the router.
 */
class TopicRegistryTest {

    private static TopicInfo find(TopicRegistry r, String key) {
        return r.snapshot().stream()
                .filter(t -> t.keyExpr().equals(key))
                .findFirst().orElse(null);
    }

    @Test void startsEmptyAndOpen() {
        TopicRegistry r = new TopicRegistry("**");
        assertTrue(r.isOpen());
        assertEquals(0, r.size());
        assertTrue(r.snapshot().isEmpty());
        assertEquals("**", r.pattern());
    }

    @Test void snapshotIsSortedByKeyExpr() {
        TopicRegistry r = new TopicRegistry("**");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "zzz/last");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 2L, "aaa/first");
        r.recordDeclaration(TopicSource.DECLARED_QUERYABLE, 7L, "mid/q");

        List<TopicInfo> snap = r.snapshot();
        assertEquals(List.of("aaa/first", "mid/q", "zzz/last"),
                snap.stream().map(TopicInfo::keyExpr).toList());
    }

    @Test void multipleSourcesMergeOnOneKey() {
        TopicRegistry r = new TopicRegistry("**");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "shared");
        r.recordDeclaration(TopicSource.LIVENESS_TOKEN, 1L, "shared");

        assertEquals(1, r.size());
        assertEquals(EnumSet.of(TopicSource.DECLARED_SUBSCRIBER, TopicSource.LIVENESS_TOKEN),
                find(r, "shared").sources());
    }

    @Test void undeclareIsScopedToItsOwnIdSpace() {
        // Subscriber id 1 and token id 1 are unrelated: each kind allocates
        // its own ids. Withdrawing one must not evict the other.
        TopicRegistry r = new TopicRegistry("**");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "subs/key");
        r.recordDeclaration(TopicSource.LIVENESS_TOKEN, 1L, "token/key");

        r.recordUndeclaration(TopicSource.LIVENESS_TOKEN, 1L);

        assertNotNull(find(r, "subs/key"), "subscriber id 1 survived token id 1");
        org.junit.jupiter.api.Assertions.assertNull(find(r, "token/key"));
    }

    @Test void entryDropsOnlyWhenLastEvidenceGoes() {
        TopicRegistry r = new TopicRegistry("**");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "k");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 2L, "k");

        r.recordUndeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L);
        assertNotNull(find(r, "k"), "second subscriber still holds the key");

        r.recordUndeclaration(TopicSource.DECLARED_SUBSCRIBER, 2L);
        org.junit.jupiter.api.Assertions.assertNull(find(r, "k"));
    }

    @Test void observedTrafficIsSticky() {
        // Having once seen a message on a key is permanent evidence for the
        // life of the registry, even after the declaration that led us
        // there is withdrawn.
        TopicRegistry r = new TopicRegistry("**");
        r.recordDeclaration(TopicSource.DECLARED_QUERYABLE, 7L, "busy");
        r.recordSample("busy");
        r.recordSample("busy");
        r.recordUndeclaration(TopicSource.DECLARED_QUERYABLE, 7L);

        TopicInfo t = find(r, "busy");
        assertNotNull(t);
        assertEquals(2L, t.sampleCount());
        assertTrue(t.hasTraffic());
        assertFalse(t.isDeclarationOnly());
        assertTrue(t.active());
        // Provenance covers the entry's whole lifetime, so the withdrawn
        // queryable is still listed. Only activeSources() narrows.
        assertEquals(EnumSet.of(TopicSource.DECLARED_QUERYABLE, TopicSource.OBSERVED_TRAFFIC),
                t.sources());
        assertEquals(EnumSet.of(TopicSource.OBSERVED_TRAFFIC), t.activeSources());
    }

    @Test void declarationOnlyTopicReportsNoTraffic() {
        TopicRegistry r = new TopicRegistry("**");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "quiet");
        TopicInfo t = find(r, "quiet");
        assertEquals(0L, t.sampleCount());
        assertTrue(t.isDeclarationOnly());
    }

    @Test void repeatedDeclarationOfSameIdIsIdempotent() {
        TopicRegistry r = new TopicRegistry("**");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 42L, "dup");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 42L, "dup");
        r.recordUndeclaration(TopicSource.DECLARED_SUBSCRIBER, 42L);
        org.junit.jupiter.api.Assertions.assertNull(find(r, "dup"),
                "one undeclare clears a duplicated declare");
    }

    @Test void unknownUndeclareIsIgnored() {
        // Legitimate whenever the registry starts after a peer declared.
        TopicRegistry r = new TopicRegistry("**");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "k");
        r.recordUndeclaration(TopicSource.DECLARED_SUBSCRIBER, 999L);
        assertEquals(1, r.size());
    }

    @Test void awaitCurrentBlocksUntilFinal() throws Exception {
        TopicRegistry r = new TopicRegistry("**");
        assertFalse(r.awaitCurrent(Duration.ofMillis(20)), "no final yet");
        r.markCurrentComplete();
        assertTrue(r.awaitCurrent(Duration.ofMillis(20)));
    }

    @Test void closeIsIdempotentAndRunsTheInterestCloser() throws Exception {
        TopicRegistry r = new TopicRegistry("a/**");
        int[] runs = {0};
        r.attachInterest(() -> runs[0]++);

        r.close();
        r.close();

        assertEquals(1, runs[0], "closer ran exactly once");
        assertFalse(r.isOpen());
        // A caller parked in awaitCurrent must not be stranded by close().
        assertTrue(r.awaitCurrent(Duration.ofMillis(20)));
    }

    @Test void snapshotSurvivesClose() {
        TopicRegistry r = new TopicRegistry("**");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "k");
        r.close();
        assertEquals(1, r.snapshot().size(), "frozen, not cleared");
    }

    @Test void topicInfoRejectsAnEmptySourceSet() {
        assertThrows(IllegalArgumentException.class,
                () -> TopicInfo.active("x", EnumSet.noneOf(TopicSource.class), 0, 0, 0));
    }

    @Test void topicInfoRejectsNegativeSampleCount() {
        assertThrows(IllegalArgumentException.class,
                () -> TopicInfo.active("x", EnumSet.of(TopicSource.LIVENESS_TOKEN), 0, 0, -1));
    }

    @Test void topicInfoRejectsActiveSourcesOutsideSources() {
        assertThrows(IllegalArgumentException.class,
                () -> new TopicInfo("x",
                        EnumSet.of(TopicSource.LIVENESS_TOKEN),
                        EnumSet.of(TopicSource.OBSERVED_TRAFFIC),
                        0, 0, 0));
    }

    @Test void topicInfoCopiesItsSourceSets() {
        EnumSet<TopicSource> mutable = EnumSet.of(TopicSource.LIVENESS_TOKEN);
        TopicInfo t = TopicInfo.active("x", mutable, 0, 0, 0);
        mutable.add(TopicSource.OBSERVED_TRAFFIC);
        assertEquals(1, t.sources().size(), "defensive copy taken");
        assertEquals(1, t.activeSources().size());
        assertThrows(UnsupportedOperationException.class,
                () -> t.sources().add(TopicSource.OBSERVED_TRAFFIC));
        assertThrows(UnsupportedOperationException.class,
                () -> t.activeSources().add(TopicSource.OBSERVED_TRAFFIC));
    }

    // ----- cumulative mode ----------------------------------------------

    @Test void liveRegistryIsTheDefault() {
        assertFalse(new TopicRegistry("**").isCumulative());
        assertTrue(new TopicRegistry("**", true).isCumulative());
    }

    @Test void cumulativeKeepsWithdrawnTopicsButMarksThemInactive() {
        TopicRegistry r = new TopicRegistry("**", true);
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "gone");
        r.recordUndeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L);

        TopicInfo t = find(r, "gone");
        assertNotNull(t, "cumulative registry never forgets");
        assertFalse(t.active());
        assertTrue(t.activeSources().isEmpty());
        assertEquals(EnumSet.of(TopicSource.DECLARED_SUBSCRIBER), t.sources(),
                "provenance survives the withdrawal");
    }

    @Test void liveRegistryDropsTheSameTopic() {
        TopicRegistry r = new TopicRegistry("**", false);
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "gone");
        r.recordUndeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L);
        org.junit.jupiter.api.Assertions.assertNull(find(r, "gone"));
    }

    @Test void activeOnlyFiltersDepartedTopics() {
        TopicRegistry r = new TopicRegistry("**", true);
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "still/here");
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 2L, "departed");
        r.recordUndeclaration(TopicSource.DECLARED_SUBSCRIBER, 2L);

        assertEquals(2, r.snapshot().size(), "snapshot keeps both");
        assertEquals(List.of("still/here"),
                r.activeOnly().stream().map(TopicInfo::keyExpr).toList());
    }

    @Test void partialWithdrawalNarrowsActiveSourcesButNotSources() {
        TopicRegistry r = new TopicRegistry("**", true);
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "k");
        r.recordDeclaration(TopicSource.LIVENESS_TOKEN, 1L, "k");
        r.recordUndeclaration(TopicSource.LIVENESS_TOKEN, 1L);

        TopicInfo t = find(r, "k");
        assertTrue(t.active());
        assertEquals(EnumSet.of(TopicSource.DECLARED_SUBSCRIBER, TopicSource.LIVENESS_TOKEN),
                t.sources(), "still remembers the token");
        assertEquals(EnumSet.of(TopicSource.DECLARED_SUBSCRIBER), t.activeSources());
    }

    @Test void cumulativeRemembersATopicThatWentQuiet() {
        // The point of the recorder: a topic busy earlier in the run is
        // still reported later, with its sample count intact.
        TopicRegistry r = new TopicRegistry("**", true);
        r.recordSample("was/busy");
        r.recordSample("was/busy");

        TopicInfo t = find(r, "was/busy");
        assertEquals(2L, t.sampleCount());
        assertTrue(t.hasTraffic());
        assertTrue(t.active(), "observed traffic is sticky");
    }

    @Test void withdrawalDoesNotAdvanceLastSeen() throws Exception {
        TopicRegistry r = new TopicRegistry("**", true);
        r.recordDeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L, "k");
        long seenAtDeclare = find(r, "k").lastSeenMs();

        Thread.sleep(5);
        r.recordUndeclaration(TopicSource.DECLARED_SUBSCRIBER, 1L);

        assertEquals(seenAtDeclare, find(r, "k").lastSeenMs(),
                "lastSeenMs tracks positive evidence only");
    }
}

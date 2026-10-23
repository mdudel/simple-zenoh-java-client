/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.session;

import io.mdudel.zenoh.purejava.PureJavaZenohSubscriber;
import io.mdudel.zenoh.purejava.PureJavaZenohSubscriber.Topic;
import io.mdudel.zenoh.purejava.wire.messages.Interest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the synchronous one-shot {@code listTopicsNow(...)} /
 * {@code listAllTopicsNow(...)} API on {@link PureJavaZenohSubscriber}.
 *
 * <p>Complements {@link TopicDiscoveryTest} which covers the streaming
 * / callback-driven variant. Both share the same underlying
 * {@code declareInterest} machinery in {@link ZenohSession}; these
 * tests focus on the behaviour specific to CURRENT-mode collection:
 * blocking until FINAL, timing out gracefully, snapshot immutability,
 * and correct JSON serialisation.</p>
 */
class ListTopicsNowTest {

    private LoopbackZenohRouter router;

    @BeforeEach void bind() throws IOException {
        router = LoopbackZenohRouter.bind();
    }

    @AfterEach void unbind() {
        if (router != null) router.close();
    }

    @Test void returnsSnapshotAfterFinalSentinel() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();

            // Replier thread: as soon as the INTEREST arrives, feed three
            // declares + FINAL back on the loopback session.
            CountDownLatch replierStarted = new CountDownLatch(1);
            Thread replier = new Thread(() -> {
                try {
                    replierStarted.countDown();
                    long interestId = waitForCurrentInterest("**");
                    LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
                    peer.sendDeclareSubscriber(1L, interestId, 101L, "sensors/room1/temp");
                    peer.sendDeclareSubscriber(2L, interestId, 102L, "sensors/room2/temp");
                    peer.sendDeclareSubscriber(3L, interestId, 103L, "logs/system/**");
                    peer.sendDeclareFinal(4L, interestId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "list-topics-replier");
            replier.setDaemon(true);
            replier.start();
            assertTrue(replierStarted.await(1, TimeUnit.SECONDS));

            List<Topic> topics = sub.listAllTopicsNow(3_000);
            replier.join(2_000);

            assertEquals(3, topics.size(), "expected 3 topics, got " + topics);
            assertEquals("sensors/room1/temp", topics.get(0).keyExpr());
            assertEquals(101L,                 topics.get(0).declaredById());
            assertEquals("sensors/room2/temp", topics.get(1).keyExpr());
            assertEquals("logs/system/**",     topics.get(2).keyExpr());

            // The returned list must be immutable so callers can share it freely.
            assertThrows(UnsupportedOperationException.class,
                    () -> topics.add(new Topic("mutation-should-fail", 0L)));
        }
    }

    @Test void sendsCurrentModeInterestNotCurrentFuture() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();

            // Kick off the FINAL immediately so listTopicsNow() returns.
            Thread replier = new Thread(() -> {
                try {
                    long interestId = waitForAnyInterest();
                    router.sessions().get(0).sendDeclareFinal(1L, interestId);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            replier.setDaemon(true);
            replier.start();

            sub.listAllTopicsNow(2_000);
            replier.join(2_000);

            List<Interest> interests = router.sessions().get(0).receivedInterests();
            assertEquals(1, interests.size());
            Interest i = interests.get(0);
            assertEquals(Interest.Mode.CURRENT, i.mode(),
                    "listTopicsNow must use CURRENT mode so the interest self-terminates");
            assertTrue(i.wantsSubscribers(),
                    "listTopicsNow must set OPT_SUBSCRIBERS");
            assertEquals("**", i.keySuffix());
        }
    }

    @Test void emptyListWhenRouterHasNoSubscribers() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();

            // Router replies with FINAL only, no declares. Simulates a fresh
            // router with no active subscribers.
            Thread replier = new Thread(() -> {
                try {
                    long interestId = waitForAnyInterest();
                    router.sessions().get(0).sendDeclareFinal(1L, interestId);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            replier.setDaemon(true);
            replier.start();

            List<Topic> topics = sub.listAllTopicsNow(2_000);
            replier.join(2_000);
            assertTrue(topics.isEmpty(), "expected empty list, got " + topics);
        }
    }

    @Test void returnsPartialListOnTimeoutWithoutThrowing() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();

            // Send two declares but NEVER send FINAL. listTopicsNow should
            // time out gracefully and return the two entries anyway.
            Thread replier = new Thread(() -> {
                try {
                    long interestId = waitForAnyInterest();
                    LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
                    peer.sendDeclareSubscriber(1L, interestId, 501L, "alpha");
                    peer.sendDeclareSubscriber(2L, interestId, 502L, "beta");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            replier.setDaemon(true);
            replier.start();

            long start = System.currentTimeMillis();
            List<Topic> topics = sub.listAllTopicsNow(500);
            long elapsed = System.currentTimeMillis() - start;
            replier.join(1_000);

            assertTrue(elapsed >= 450 && elapsed < 2_000,
                    "expected roughly-500ms wait, got " + elapsed + "ms");
            assertEquals(2, topics.size(),
                    "should have returned the two entries that DID arrive before timeout");
            assertEquals("alpha", topics.get(0).keyExpr());
            assertEquals("beta",  topics.get(1).keyExpr());
        }
    }

    @Test void patternIsForwardedToRouter() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();

            Thread replier = new Thread(() -> {
                try {
                    long interestId = waitForCurrentInterest("sensors/**");
                    router.sessions().get(0).sendDeclareFinal(1L, interestId);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            replier.setDaemon(true);
            replier.start();

            sub.listTopicsNow("sensors/**", 2_000);
            replier.join(2_000);

            List<Interest> interests = router.sessions().get(0).receivedInterests();
            assertEquals("sensors/**", interests.get(0).keySuffix());
        }
    }

    @Test void beforeStartThrows() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            IOException e = assertThrows(IOException.class,
                    () -> sub.listAllTopicsNow(1_000));
            assertNotNull(e.getMessage());
        }
    }

    @Test void nonPositiveTimeoutRejected() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();
            assertThrows(IllegalArgumentException.class,
                    () -> sub.listAllTopicsNow(0));
            assertThrows(IllegalArgumentException.class,
                    () -> sub.listAllTopicsNow(-1));
        }
    }

    @Test void jsonSerialisationRoundtripsStructureAndOrder() {
        List<Topic> topics = List.of(
                new Topic("sensors/room1/temp",    101L),
                new Topic("sensors/room2/temp",    102L),
                new Topic("logs/\"quoted\"/entry", 103L),   // needs escaping
                new Topic("newlines\nand\ttabs",    104L)    // control chars
        );
        String json = Topic.toJson(topics);

        // Structural checks -- we don't ship a JSON parser in the pure-java
        // module (zero-runtime-deps invariant), so assert on the exact
        // expected output rather than reparsing.
        String expected = "["
                + "{\"keyExpr\":\"sensors/room1/temp\",\"declaredById\":101},"
                + "{\"keyExpr\":\"sensors/room2/temp\",\"declaredById\":102},"
                + "{\"keyExpr\":\"logs/\\\"quoted\\\"/entry\",\"declaredById\":103},"
                + "{\"keyExpr\":\"newlines\\nand\\ttabs\",\"declaredById\":104}"
                + "]";
        assertEquals(expected, json);
    }

    @Test void jsonHandlesEmptyList() {
        assertEquals("[]", Topic.toJson(List.of()));
    }

    // ---- helpers ------------------------------------------------------

    private long waitForAnyInterest() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (!router.sessions().isEmpty()) {
                List<Interest> is = router.sessions().get(0).receivedInterests();
                if (!is.isEmpty()) return is.get(0).id();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("router never received any INTEREST");
    }

    private long waitForCurrentInterest(String expectedPattern) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (!router.sessions().isEmpty()) {
                for (Interest i : router.sessions().get(0).receivedInterests()) {
                    if (i.mode() == Interest.Mode.CURRENT
                            && expectedPattern.equals(i.keySuffix())) {
                        return i.id();
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("router never received a CURRENT INTEREST for " + expectedPattern);
    }
}

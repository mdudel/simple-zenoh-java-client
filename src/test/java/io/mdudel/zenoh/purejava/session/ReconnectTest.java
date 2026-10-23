/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.session;

import io.mdudel.zenoh.purejava.transport.TcpTransport;
import io.mdudel.zenoh.purejava.wire.WBuf;
import io.mdudel.zenoh.purejava.wire.messages.Declare;
import io.mdudel.zenoh.purejava.wire.messages.DeclareKeyExpr;
import io.mdudel.zenoh.purejava.wire.messages.Frame;
import io.mdudel.zenoh.purejava.wire.messages.Interest;
import io.mdudel.zenoh.purejava.wire.messages.Push;
import io.mdudel.zenoh.purejava.wire.messages.Put;
import io.mdudel.zenoh.purejava.wire.messages.UndeclareKeyExpr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for automatic session reconnect, state re-declaration ({@link Subscription}
 * and {@link Interest}), multi-message {@link Frame} unbatching, and
 * {@link DeclareKeyExpr} numeric alias resolution.
 */
class ReconnectTest {

    private LoopbackZenohRouter router;

    @BeforeEach
    void bind() throws IOException {
        router = LoopbackZenohRouter.bind();
    }

    @AfterEach
    void unbind() {
        if (router != null) router.close();
    }

    @Test
    void autoReconnectReestablishesSessionAndRedeclaresSubscriptionsAndInterests() throws Exception {
        TcpTransport t = new TcpTransport("127.0.0.1", router.port());
        try (ZenohSession s = ZenohSession.builder(t)
                .leaseMs(2_000)
                .handshakeTimeoutMs(2_000)
                .closeTimeoutMs(500)
                .autoReconnect(true)
                .reconnectInitialDelayMs(50)
                .reconnectMaxDelayMs(200)
                .build()) {
            s.open();
            assertEquals(SessionState.OPEN, s.state());
            assertEquals(0L, s.reconnectCount());

            // Declare a subscription and an active interest before disconnect.
            Subscription sub = s.declareSubscriber("demo/reconnect/**");
            List<String> discovered = new CopyOnWriteArrayList<>();
            long interestId0 = s.declareInterest(
                    Interest.Mode.CURRENT_FUTURE,
                    Interest.OPT_SUBSCRIBERS,
                    "demo/**",
                    (declare, isFinal) -> {
                        if (declare != null && declare.body().kind() == Declare.Body.BodyKind.DECLARE_SUBSCRIBER) {
                            discovered.add(declare.body().asDeclareSubscriber().keySuffix());
                        }
                    });

            LoopbackZenohRouter.ClientSession cs1 = router.sessions().get(0);
            cs1.sendPushString(0, "demo/reconnect/one", "first");
            Sample sample1 = sub.poll(2_000, TimeUnit.MILLISECONDS);
            assertNotNull(sample1);
            assertEquals("first", sample1.payloadAsString());

            // Simulate server disconnect (send Close frame / close server socket).
            cs1.sendClose();
            cs1.close();

            // Wait for the client to automatically reconnect as a 2nd session on the router.
            long deadline = System.currentTimeMillis() + 3_000;
            while ((router.sessions().size() < 2 || s.state() != SessionState.OPEN || s.reconnectCount() < 1)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertEquals(SessionState.OPEN, s.state());
            assertTrue(s.reconnectCount() >= 1, "reconnectCount should be >= 1");
            assertEquals(2, router.sessions().size(), "router should have accepted a second connection");

            LoopbackZenohRouter.ClientSession cs2 = router.sessions().get(1);

            // Wait until the reconnected session has re-sent DeclareSubscriber and Interest.
            deadline = System.currentTimeMillis() + 2_000;
            while ((cs2.receivedDeclares().isEmpty() || cs2.receivedInterests().isEmpty())
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }

            List<Declare> redeclared = cs2.receivedDeclares();
            assertTrue(redeclared.stream().anyMatch(d ->
                    d.body().kind() == Declare.Body.BodyKind.DECLARE_SUBSCRIBER
                            && "demo/reconnect/**".equals(d.body().asDeclareSubscriber().keySuffix())),
                    "Subscription must be re-declared on the new connection");

            List<Interest> reInterests = cs2.receivedInterests();
            assertTrue(reInterests.stream().anyMatch(i -> "demo/**".equals(i.keySuffix())),
                    "Interest must be re-declared on the new connection");

            // Push a new message on the new server connection and verify the existing
            // Subscription object receives it seamlessly.
            cs2.sendPushString(0, "demo/reconnect/two", "second");
            Sample sample2 = sub.poll(2_000, TimeUnit.MILLISECONDS);
            assertNotNull(sample2, "existing Subscription must receive samples after reconnect");
            assertEquals("second", sample2.payloadAsString());

            // Also verify the re-declared Interest receives notifications on the new connection.
            long reInterestId = reInterests.get(0).id();
            cs2.sendDeclareSubscriber(1, reInterestId, 99L, "demo/after/reconnect");
            deadline = System.currentTimeMillis() + 2_000;
            while (!discovered.contains("demo/after/reconnect") && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertTrue(discovered.contains("demo/after/reconnect"),
                    "existing DiscoveryListener must receive declarations after reconnect");

            s.finalInterest(interestId0);
        }
    }

    @Test
    void multiMessageFrameUnbatchingAndKeyExprAliasResolution() throws Exception {
        TcpTransport t = new TcpTransport("127.0.0.1", router.port());
        try (ZenohSession s = ZenohSession.builder(t)
                .leaseMs(2_000)
                .handshakeTimeoutMs(2_000)
                .closeTimeoutMs(500)
                .build()) {
            s.open();
            Subscription sub = s.declareSubscriber("sensor/**");
            LoopbackZenohRouter.ClientSession cs = router.sessions().get(0);

            // Pack 3 network messages into a SINGLE transport Frame:
            //   1) DeclareKeyExpr(id=7, keyScope=0, "sensor/temp")
            //   2) Push(scope=7, suffix=null, senderMapping=true, Put("21.5C"))
            //   3) Push(scope=7, suffix="/room1", senderMapping=true, Put("22.0C"))
            Declare declKe = new Declare(null, List.of(),
                    Declare.Body.of(new DeclareKeyExpr(7L, 0, "sensor/temp", List.of())));
            Push push1 = new Push(7, null, true, List.of(),
                    Put.ofString("21.5C").encode());
            Push push2 = new Push(7, "/room1", true, List.of(),
                    Put.ofString("22.0C").encode());

            WBuf batched = new WBuf(128);
            batched.bytes(declKe.encode());
            batched.bytes(push1.encode());
            batched.bytes(push2.encode());

            Frame singleFrame = new Frame(0L, true, List.of(), batched.toByteArray());
            cs.sendRaw(singleFrame.encode());

            Sample s1 = sub.poll(2_000, TimeUnit.MILLISECONDS);
            assertNotNull(s1, "1st unbatched Push (exact alias) must be delivered");
            assertEquals("sensor/temp", s1.key());
            assertEquals("21.5C", s1.payloadAsString());

            Sample s2 = sub.poll(2_000, TimeUnit.MILLISECONDS);
            assertNotNull(s2, "2nd unbatched Push (alias + suffix) must be delivered");
            assertEquals("sensor/temp/room1", s2.key());
            assertEquals("22.0C", s2.payloadAsString());

            // Now send UndeclareKeyExpr(7) followed by Push(scope=7) in one Frame;
            // the Push after undeclare must be ignored.
            Declare undeclKe = new Declare(null, List.of(),
                    Declare.Body.of(new UndeclareKeyExpr(7L, List.of())));
            WBuf batch2 = new WBuf(64);
            batch2.bytes(undeclKe.encode());
            batch2.bytes(push1.encode());
            cs.sendRaw(new Frame(1L, true, List.of(), batch2.toByteArray()).encode());

            Sample s3 = sub.poll(200, TimeUnit.MILLISECONDS);
            assertNull(s3, "Push referencing an undeclared keyScope must be dropped");
        }
    }
}

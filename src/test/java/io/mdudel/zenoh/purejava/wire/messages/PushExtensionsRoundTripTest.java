/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.CongestionControl;
import io.mdudel.zenoh.purejava.wire.Priority;
import io.mdudel.zenoh.purejava.wire.PushExts;
import io.mdudel.zenoh.purejava.wire.Qos;
import io.mdudel.zenoh.purejava.wire.Timestamp;
import io.mdudel.zenoh.purejava.wire.ZenohId;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end round-trip: Push.ofPut(..., qos, ts, nodeId) -> Push.encode()
 * -> Push.decode() -> PushExts.extract*() recovers what we put in.
 */
class PushExtensionsRoundTripTest {

    @Test void roundTripQosOnlyForAllPriorities() {
        Put put = Put.ofString("payload");
        for (Priority p : Priority.values()) {
            Qos in = Qos.of(p);
            Push encoded = Push.ofPut("k/e/y", put, in, null, 0L);
            Push back = Push.decode(encoded.encode());
            assertEquals(in, PushExts.extractQos(back.extensions()),
                    "priority=" + p);
            // Timestamp + NodeId absent.
            org.junit.jupiter.api.Assertions.assertNull(PushExts.extractTimestamp(back.extensions()));
            assertEquals(0L, PushExts.extractNodeId(back.extensions()));
        }
    }

    @Test void roundTripQosCongestionAndExpressBits() {
        Put put = Put.ofString("payload");
        for (CongestionControl cc : CongestionControl.values()) {
            for (boolean ex : new boolean[] { false, true }) {
                Qos in = new Qos(Priority.DATA_HIGH, cc, ex);
                Push encoded = Push.ofPut("k", put, in, null, 0L);
                Push back = Push.decode(encoded.encode());
                assertEquals(in, PushExts.extractQos(back.extensions()),
                        "cc=" + cc + " ex=" + ex);
            }
        }
    }

    @Test void roundTripTimestampOnly() {
        Put put = Put.ofString("payload");
        ZenohId zid = new ZenohId(new byte[] { 0x0A, 0x0B, 0x0C, 0x0D });
        Timestamp in = new Timestamp(0x1122_3344_5566_7788L, zid);
        Push encoded = Push.ofPut("k", put, null, in, 0L);
        Push back = Push.decode(encoded.encode());
        Timestamp got = PushExts.extractTimestamp(back.extensions());
        assertNotNull(got);
        assertEquals(in, got);
        assertEquals(Qos.DEFAULT, PushExts.extractQos(back.extensions()));
        assertEquals(0L, PushExts.extractNodeId(back.extensions()));
    }

    @Test void roundTripNodeIdOnly() {
        Put put = Put.ofString("payload");
        long[] cases = { 1L, 0xFFL, 0x1_0000L, 0xFFFF_FFFFL };
        for (long nid : cases) {
            Push encoded = Push.ofPut("k", put, null, null, nid);
            Push back = Push.decode(encoded.encode());
            assertEquals(nid, PushExts.extractNodeId(back.extensions()),
                    "nid=" + nid);
        }
    }

    @Test void roundTripAllThreeTogether() {
        Put put = Put.ofString("payload");
        ZenohId zid = new ZenohId(new byte[] { 0x77, (byte) 0x88 });
        Timestamp ts = new Timestamp(0xDEADBEEFL, zid);
        Qos qos = new Qos(Priority.REAL_TIME, CongestionControl.BLOCK, true);
        long nid = 0xCAFEBABEL;

        Push encoded = Push.ofPut("skylord/tracks/123", put, qos, ts, nid);
        Push back = Push.decode(encoded.encode());
        assertEquals(qos, PushExts.extractQos(back.extensions()));
        assertEquals(ts,  PushExts.extractTimestamp(back.extensions()));
        assertEquals(nid, PushExts.extractNodeId(back.extensions()));
        // extensions appear in ascending id order (0x01, 0x02, 0x03).
        assertEquals(3, back.extensions().size());
        assertEquals(PushExts.EXT_ID_QOS,       back.extensions().get(0).id());
        assertEquals(PushExts.EXT_ID_TIMESTAMP, back.extensions().get(1).id());
        assertEquals(PushExts.EXT_ID_NODE_ID,   back.extensions().get(2).id());
    }

    @Test void defaultCallProducesByteIdenticalWireOutputToLegacyOverload() {
        // Golden vector: verify legacy overload output == new overload
        // output when the new overload gets only default values.
        Put put = Put.ofString("hello");
        byte[] legacy   = Push.ofPut("k", put).encode();
        byte[] extended = Push.ofPut("k", put, Qos.DEFAULT, null, 0L).encode();
        assertArrayEquals(legacy, extended);
    }
}

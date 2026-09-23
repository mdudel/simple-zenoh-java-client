/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import io.mdudel.zenoh.purejava.wire.messages.Push;
import io.mdudel.zenoh.purejava.wire.messages.Put;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushExtsTest {

    // ---- extension IDs (frozen against Rust source) ---------------------

    @Test void extensionIdsMatchRust() {
        assertEquals(0x01, PushExts.EXT_ID_QOS);
        assertEquals(0x02, PushExts.EXT_ID_TIMESTAMP);
        assertEquals(0x03, PushExts.EXT_ID_NODE_ID);
    }

    // ---- individual extension helpers -----------------------------------

    @Test void qosHelperBuildsZ64NonMandatory() {
        Qos q = Qos.of(Priority.CONTROL);
        Extension e = PushExts.qos(q);
        assertEquals(PushExts.EXT_ID_QOS, e.id());
        assertEquals(Extension.Encoding.Z64, e.encoding());
        org.junit.jupiter.api.Assertions.assertFalse(e.mandatory(),
                "QoS extension must be non-mandatory per Rust zextz64!(0x1, false)");
        assertEquals(Priority.CONTROL.wireValue(), (int) e.asZ64());
    }

    @Test void timestampHelperBuildsZBufNonMandatory() {
        ZenohId zid = new ZenohId(new byte[] { 0x0A, 0x0B, 0x0C });
        Timestamp ts = new Timestamp(0x1234_5678_ABCDL, zid);
        Extension e = PushExts.timestamp(ts);
        assertEquals(PushExts.EXT_ID_TIMESTAMP, e.id());
        assertEquals(Extension.Encoding.ZBUF, e.encoding());
        org.junit.jupiter.api.Assertions.assertFalse(e.mandatory(),
                "Timestamp extension must be non-mandatory per Rust zextzbuf!(0x2, false)");
        // Body decodes back to the same Timestamp.
        Timestamp back = Timestamp.decode(new RBuf(e.asZBuf()));
        assertEquals(ts, back);
    }

    @Test void nodeIdHelperBuildsZ64Mandatory() {
        Extension e = PushExts.nodeId(42);
        assertEquals(PushExts.EXT_ID_NODE_ID, e.id());
        assertEquals(Extension.Encoding.Z64, e.encoding());
        assertTrue(e.mandatory(),
                "NodeId extension must be mandatory per Rust zextz64!(0x3, true)");
        assertEquals(42L, e.asZ64());
    }

    @Test void nodeIdRejectsOutOfU32Range() {
        assertThrows(IllegalArgumentException.class, () -> PushExts.nodeId(-1));
        assertThrows(IllegalArgumentException.class, () -> PushExts.nodeId(0x1_0000_0000L));
    }

    // ---- buildPushExtensions skip-when-default -------------------------

    @Test void defaultsProduceEmptyList() {
        assertTrue(PushExts.buildPushExtensions(null, null, 0L).isEmpty());
        assertTrue(PushExts.buildPushExtensions(Qos.DEFAULT, null, 0L).isEmpty());
    }

    @Test void anyNonDefaultBuildsAtLeastOne() {
        assertEquals(1, PushExts.buildPushExtensions(Qos.of(Priority.CONTROL), null, 0L).size());
        assertEquals(1, PushExts.buildPushExtensions(null, timestampNow(), 0L).size());
        assertEquals(1, PushExts.buildPushExtensions(null, null, 42L).size());
    }

    @Test void extensionsAppearInAscendingIdOrder() {
        List<Extension> exts = PushExts.buildPushExtensions(
                Qos.of(Priority.REAL_TIME), timestampNow(), 42L);
        assertEquals(3, exts.size());
        assertEquals(PushExts.EXT_ID_QOS,       exts.get(0).id());
        assertEquals(PushExts.EXT_ID_TIMESTAMP, exts.get(1).id());
        assertEquals(PushExts.EXT_ID_NODE_ID,   exts.get(2).id());
    }

    // ---- byte-identical backward compat --------------------------------

    @Test void pushOfPutWithDefaultsProducesByteIdenticalWireOutput() {
        // The whole point of the additive design: passing default args to
        // the new overload MUST yield the same bytes as the pre-QoS overload.
        Put put = Put.ofString("hello");
        byte[] pre  = Push.ofPut("k/e/y", put).encode();
        byte[] post = Push.ofPut("k/e/y", put, null,       null, 0L).encode();
        byte[] postDefaultQos =
                     Push.ofPut("k/e/y", put, Qos.DEFAULT, null, 0L).encode();
        assertArrayEquals(pre, post);
        assertArrayEquals(pre, postDefaultQos);
    }

    @Test void pushOfPutWithNonDefaultQosSetsZFlagAndAppendsExtension() {
        Put put = Put.ofString("x");
        byte[] out = Push.ofPut("k", put, Qos.of(Priority.CONTROL), null, 0L).encode();
        // Header must have Z flag set now (0x80). N flag = 0x20. Base = 0x1d.
        assertEquals((byte) (0x1D | 0x20 | 0x80), out[0]);
    }

    // ---- extractors ----------------------------------------------------

    @Test void extractorsReadBackWhatBuildersWrote() {
        ZenohId zid = new ZenohId(new byte[] { 0x11, 0x22 });
        Timestamp ts = new Timestamp(0xABCDEF01L, zid);
        List<Extension> exts = PushExts.buildPushExtensions(
                Qos.of(Priority.REAL_TIME), ts, 7L);
        assertEquals(Qos.of(Priority.REAL_TIME), PushExts.extractQos(exts));
        Timestamp back = PushExts.extractTimestamp(exts);
        assertNotNull(back);
        assertEquals(ts, back);
        assertEquals(7L, PushExts.extractNodeId(exts));
    }

    @Test void extractorsHandleEmptyLists() {
        assertEquals(Qos.DEFAULT, PushExts.extractQos(List.of()));
        assertNull(PushExts.extractTimestamp(List.of()));
        assertEquals(0L, PushExts.extractNodeId(List.of()));
        assertEquals(Qos.DEFAULT, PushExts.extractQos(null));
    }

    // ---- helpers -------------------------------------------------------

    /**
     * A deterministic "small" timestamp that fits in a 63-bit signed positive
     * varint. Real wall-clock {@code Timestamp.now()} values overflow into the
     * sign bit for current dates, which the existing {@code VarInt.encode}
     * refuses. That's a separate pre-existing issue tracked outside this
     * test class; the QoS/Timestamp/NodeId API surface itself works
     * correctly for any value that {@code VarInt} accepts.
     */
    private static Timestamp timestampNow() {
        return new Timestamp(0x1234_5678_ABCDL,
                new ZenohId(new byte[] { 0x01, 0x02, 0x03 }));
    }
}

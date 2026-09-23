/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QosTest {

    @Test void defaultByteMatchesRust() {
        // Rust QoSType::DEFAULT = new(Priority::DEFAULT=Data(5), CongestionControl::DEFAULT=Drop, false)
        // Wire byte = 0x05 (priority=5, no D_FLAG, no E_FLAG).
        assertEquals(0x05, Qos.DEFAULT.toWireByte());
        assertTrue(Qos.DEFAULT.isDefault());
    }

    @Test void controlPriorityIsZero() {
        // CONTROL is priority 0, all flags clear.
        Qos q = Qos.of(Priority.CONTROL);
        assertEquals(0x00, q.toWireByte());
        assertFalse(q.isDefault());
    }

    @Test void blockSetsDFlag() {
        Qos q = new Qos(Priority.DATA, CongestionControl.BLOCK, false);
        // 0x05 | 0x08 = 0x0D
        assertEquals(0x0D, q.toWireByte());
    }

    @Test void expressSetsEFlag() {
        Qos q = new Qos(Priority.DATA, CongestionControl.DROP, true);
        // 0x05 | 0x10 = 0x15
        assertEquals(0x15, q.toWireByte());
    }

    @Test void allFlagsCombined() {
        Qos q = new Qos(Priority.REAL_TIME, CongestionControl.BLOCK, true);
        // priority=1 | D_FLAG=0x08 | E_FLAG=0x10 = 0x19
        assertEquals(0x19, q.toWireByte());
    }

    @Test void roundTripAllPrioritiesAndFlags() {
        for (Priority p : Priority.values()) {
            for (CongestionControl cc : CongestionControl.values()) {
                for (boolean ex : new boolean[] { false, true }) {
                    Qos in = new Qos(p, cc, ex);
                    Qos back = Qos.fromWireByte(in.toWireByte());
                    assertEquals(in, back,
                            "roundtrip failed for p=" + p + " cc=" + cc + " ex=" + ex);
                }
            }
        }
    }

    @Test void fromWireIgnoresUnstableFFlagAndReserved() {
        // Set unrelated bits (F_FLAG=0x20, bits 6/7); decode should drop them.
        int b = Qos.DEFAULT.toWireByte() | 0x20 | 0x40;
        Qos back = Qos.fromWireByte(b);
        // Decoded QoS should equal DEFAULT (F_FLAG maps to DROP in our stable mapping).
        assertEquals(Qos.DEFAULT, back);
    }

    @Test void isDefaultOnlyTrueForDefault() {
        assertTrue(Qos.DEFAULT.isDefault());
        assertFalse(Qos.of(Priority.CONTROL).isDefault());
        assertFalse(new Qos(Priority.DATA, CongestionControl.BLOCK, false).isDefault());
        assertFalse(new Qos(Priority.DATA, CongestionControl.DROP, true).isDefault());
    }
}

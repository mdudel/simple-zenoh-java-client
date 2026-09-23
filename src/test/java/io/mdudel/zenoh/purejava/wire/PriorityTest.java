/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PriorityTest {

    @Test void wireValuesMatchRustCanonical() {
        // Rust: Control=0, RealTime=1, InteractiveHigh=2, InteractiveLow=3,
        //       DataHigh=4, Data=5, DataLow=6, Background=7
        assertEquals(0, Priority.CONTROL.wireValue());
        assertEquals(1, Priority.REAL_TIME.wireValue());
        assertEquals(2, Priority.INTERACTIVE_HIGH.wireValue());
        assertEquals(3, Priority.INTERACTIVE_LOW.wireValue());
        assertEquals(4, Priority.DATA_HIGH.wireValue());
        assertEquals(5, Priority.DATA.wireValue());
        assertEquals(6, Priority.DATA_LOW.wireValue());
        assertEquals(7, Priority.BACKGROUND.wireValue());
    }

    @Test void defaultIsData() {
        assertSame(Priority.DATA, Priority.DEFAULT);
    }

    @Test void fromWireRoundTripsAll() {
        for (Priority p : Priority.values()) {
            assertSame(p, Priority.fromWire(p.wireValue()));
        }
    }

    @Test void fromWireRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> Priority.fromWire(-1));
        assertThrows(IllegalArgumentException.class, () -> Priority.fromWire(8));
        assertThrows(IllegalArgumentException.class, () -> Priority.fromWire(255));
    }

    @Test void parseAcceptsDisplayForm() {
        assertSame(Priority.CONTROL,          Priority.parse("control"));
        assertSame(Priority.REAL_TIME,        Priority.parse("real-time"));
        assertSame(Priority.INTERACTIVE_HIGH, Priority.parse("interactive-high"));
        assertSame(Priority.INTERACTIVE_LOW,  Priority.parse("interactive-low"));
        assertSame(Priority.DATA_HIGH,        Priority.parse("data-high"));
        assertSame(Priority.DATA,             Priority.parse("data"));
        assertSame(Priority.DATA_LOW,         Priority.parse("data-low"));
        assertSame(Priority.BACKGROUND,       Priority.parse("background"));
    }

    @Test void parseAcceptsJavaEnumForm() {
        // Marty's list of eight values from the requirement.
        assertSame(Priority.CONTROL,          Priority.parse("control"));
        assertSame(Priority.REAL_TIME,        Priority.parse("real_time"));
        assertSame(Priority.INTERACTIVE_HIGH, Priority.parse("interactive_high"));
        assertSame(Priority.INTERACTIVE_LOW,  Priority.parse("interactive_low"));
        assertSame(Priority.DATA_HIGH,        Priority.parse("data_high"));
        assertSame(Priority.DATA,             Priority.parse("data"));
        assertSame(Priority.DATA_LOW,         Priority.parse("data_low"));
        assertSame(Priority.BACKGROUND,       Priority.parse("background"));
    }

    @Test void parseIsCaseInsensitive() {
        assertSame(Priority.REAL_TIME,        Priority.parse("REAL_TIME"));
        assertSame(Priority.REAL_TIME,        Priority.parse("Real-Time"));
        assertSame(Priority.INTERACTIVE_HIGH, Priority.parse("Interactive_High"));
    }

    @Test void parseRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> Priority.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Priority.parse("high"));
        assertThrows(IllegalArgumentException.class, () -> Priority.parse("critical"));
        assertThrows(IllegalArgumentException.class, () -> Priority.parse(null));
    }

    @Test void displayNamesUseDashes() {
        // Zenoh Rust Display uses "-" separators, not "_".
        for (Priority p : Priority.values()) {
            org.junit.jupiter.api.Assertions.assertFalse(p.displayName().contains("_"),
                    "Priority display name must not contain '_': " + p.displayName());
        }
    }
}

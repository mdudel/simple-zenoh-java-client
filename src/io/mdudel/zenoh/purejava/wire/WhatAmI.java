/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire;

/**
 * The role of a Zenoh node, as encoded in the low 2 bits of the
 * length/WhatAmI byte inside INIT and OPEN messages.
 */
public enum WhatAmI {
    ROUTER(0b00),
    PEER  (0b01),
    CLIENT(0b10);
    // 0b11 is reserved per the spec.

    public final int bits;
    WhatAmI(int bits) { this.bits = bits; }

    public static WhatAmI fromBits(int b) {
        return switch (b & 0b11) {
            case 0b00 -> ROUTER;
            case 0b01 -> PEER;
            case 0b10 -> CLIENT;
            default   -> throw new IllegalArgumentException(
                    "WhatAmI: reserved value 0b11 not supported");
        };
    }
}

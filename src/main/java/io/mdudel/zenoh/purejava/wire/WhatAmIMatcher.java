/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire;

import java.util.EnumSet;
import java.util.Set;

/**
 * Scouting-side role matcher bitmap.
 *
 * <p>Unlike {@link WhatAmI} (which encodes exactly one role in 2 bits as it
 * appears in INIT / OPEN / HELLO), a {@code WhatAmIMatcher} is a
 * <em>bitmap</em> that appears in the low 3 bits of a SCOUT message's flags
 * byte and asks "please reply if you are any of the roles I have set here".
 * A single SCOUT can therefore ask about routers, peers and clients at
 * once.</p>
 *
 * <p>The wire bits are (from the reference source):</p>
 * <ul>
 *   <li>{@code 0b001} = Router</li>
 *   <li>{@code 0b010} = Peer</li>
 *   <li>{@code 0b100} = Client</li>
 * </ul>
 *
 * <p>These are <b>deliberately different</b> from the {@link WhatAmI} enum's
 * ordinal-style bits ({@code 0b00}/{@code 0b01}/{@code 0b10}) used by
 * INIT/OPEN/HELLO. Do not confuse the two; the two id spaces are separate
 * per the Zenoh wire spec.</p>
 */
public final class WhatAmIMatcher {

    /** Router bit in the SCOUT matcher bitmap. */
    public static final int BIT_ROUTER = 0b001;
    /** Peer bit in the SCOUT matcher bitmap. */
    public static final int BIT_PEER   = 0b010;
    /** Client bit in the SCOUT matcher bitmap. */
    public static final int BIT_CLIENT = 0b100;
    /** Only these three bits are defined; the rest are reserved. */
    public static final int VALID_MASK = 0b111;

    private final int bits;

    private WhatAmIMatcher(int bits) {
        if ((bits & ~VALID_MASK) != 0) {
            throw new IllegalArgumentException(
                    "WhatAmIMatcher: reserved bits set in 0b"
                            + Integer.toBinaryString(bits));
        }
        this.bits = bits;
    }

    /** Raw 3-bit bitmap ready to OR into a flags byte. */
    public int bits() { return bits; }

    /** True if this matcher would match a HELLO from a router. */
    public boolean matchesRouter() { return (bits & BIT_ROUTER) != 0; }
    /** True if this matcher would match a HELLO from a peer. */
    public boolean matchesPeer()   { return (bits & BIT_PEER)   != 0; }
    /** True if this matcher would match a HELLO from a client. */
    public boolean matchesClient() { return (bits & BIT_CLIENT) != 0; }

    /** True if this matcher's bitmap includes the given {@link WhatAmI} role. */
    public boolean matches(WhatAmI role) {
        return switch (role) {
            case ROUTER -> matchesRouter();
            case PEER   -> matchesPeer();
            case CLIENT -> matchesClient();
        };
    }

    /** Convert to the {@link WhatAmI} enums that are set in this matcher. */
    public Set<WhatAmI> roles() {
        EnumSet<WhatAmI> out = EnumSet.noneOf(WhatAmI.class);
        if (matchesRouter()) out.add(WhatAmI.ROUTER);
        if (matchesPeer())   out.add(WhatAmI.PEER);
        if (matchesClient()) out.add(WhatAmI.CLIENT);
        return out;
    }

    // ---- constructors --------------------------------------------------

    /** All three roles - the "give me everything" default. */
    public static WhatAmIMatcher any() { return new WhatAmIMatcher(VALID_MASK); }

    /** Just the given single role. */
    public static WhatAmIMatcher of(WhatAmI role) {
        return switch (role) {
            case ROUTER -> new WhatAmIMatcher(BIT_ROUTER);
            case PEER   -> new WhatAmIMatcher(BIT_PEER);
            case CLIENT -> new WhatAmIMatcher(BIT_CLIENT);
        };
    }

    /** OR of the given roles; empty set is rejected. */
    public static WhatAmIMatcher of(Set<WhatAmI> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException(
                    "WhatAmIMatcher.of: at least one role must be requested");
        }
        int b = 0;
        for (WhatAmI r : roles) b |= of(r).bits;
        return new WhatAmIMatcher(b);
    }

    /**
     * Parse a raw 3-bit bitmap from a SCOUT flags byte's low nibble.
     * Rejects zero (a SCOUT with no roles set makes no sense) and any bits
     * outside {@link #VALID_MASK}.
     */
    public static WhatAmIMatcher fromBits(int bits) {
        if ((bits & VALID_MASK) == 0) {
            throw new IllegalArgumentException(
                    "WhatAmIMatcher: at least one role bit must be set (got 0b"
                            + Integer.toBinaryString(bits) + ")");
        }
        return new WhatAmIMatcher(bits & VALID_MASK);
    }

    @Override public boolean equals(Object o) {
        return (o instanceof WhatAmIMatcher m) && m.bits == bits;
    }
    @Override public int hashCode() { return Integer.hashCode(bits); }
    @Override public String toString() {
        return "WhatAmIMatcher{" + roles() + "}";
    }
}

/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.RBuf;
import io.mdudel.zenoh.purejava.wire.WBuf;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * The Zenoh network-level INTEREST message: asks the router to send
 * us the DECLARE records (of subscribers / queryables / tokens / keyexprs)
 * matching a given key expression. Used for topic discovery.
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/network/interest.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|Mod|INTEREST |    header byte: id=0x19 | Mode:2 | Z
 * +-+-+-+---------+
 * ~    id:z32     ~    varint u32 interest id
 * +---------------+
 * |A|M|N|R|T|Q|S|K|    if Mode!=Final: options byte
 * +---------------+
 * ~ key_scope:z16 ~    if Mode!=Final && R==1
 * +---------------+
 * ~  key_suffix   ~    if Mode!=Final && R==1 && N==1  (u8;z16)
 * +---------------+
 * ~  [int_exts]   ~    if Z==1
 * +---------------+
 * </pre>
 *
 * <h3>Mode (bits 5-6 of the header)</h3>
 * <ul>
 *   <li>{@link Mode#FINAL} (0b00) &mdash; terminate a previously-sent
 *       CurrentFuture/Future interest. No options/key follow.</li>
 *   <li>{@link Mode#CURRENT} (0b01) &mdash; ask for existing declarations
 *       only; the router sends them then a DeclareFinal echoing our
 *       interest id.</li>
 *   <li>{@link Mode#FUTURE} (0b10) &mdash; ask for future
 *       declarations/undeclarations until we send a FINAL.</li>
 *   <li>{@link Mode#CURRENT_FUTURE} (0b11) &mdash; both; get current
 *       state, then live updates until we FINAL.</li>
 * </ul>
 *
 * <h3>Options bits (bit 0 = LSB)</h3>
 * <ul>
 *   <li>K = 0x01: interested in DeclareKeyExpr records</li>
 *   <li>S = 0x02: interested in DeclareSubscriber records</li>
 *   <li>Q = 0x04: interested in DeclareQueryable records</li>
 *   <li>T = 0x08: interested in DeclareToken records</li>
 *   <li>R = 0x10: interest is restricted to a specific key expression (has WireExpr)</li>
 *   <li>N = 0x20: the WireExpr has a name/suffix</li>
 *   <li>M = 0x40: mapping is sender-declared (else receiver)</li>
 *   <li>A = 0x80: replies SHOULD be aggregated</li>
 * </ul>
 *
 * <p>MVP topic-discovery usage: mode=CURRENT_FUTURE, options=SUBSCRIBERS,
 * restricted to {@code **} so we get every declared subscription.</p>
 */
public record Interest(
        long              id,
        Mode              mode,
        int               options,          // OPT_* bit flags; auto-adjusted for R/N/M by validate()
        int               keyScope,         // used iff options & OPT_R
        String            keySuffix,        // nullable, used iff options & OPT_R & OPT_N
        List<Extension>   extensions) {

    public enum Mode {
        FINAL(0b00),
        CURRENT(0b01),
        FUTURE(0b10),
        CURRENT_FUTURE(0b11);

        public final int bits;
        Mode(int bits) { this.bits = bits; }

        public static Mode fromBits(int bits) {
            return switch (bits & 0b11) {
                case 0b00 -> FINAL;
                case 0b01 -> CURRENT;
                case 0b10 -> FUTURE;
                case 0b11 -> CURRENT_FUTURE;
                default -> throw new IllegalStateException("unreachable");
            };
        }
    }

    /** Message id byte for INTEREST. */
    public static final int ID = 0x19;

    /** Where mode bits live in the header byte. */
    public static final int MODE_SHIFT = 5;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    // Options byte bits
    public static final int OPT_KEYEXPRS    = 0x01;
    public static final int OPT_SUBSCRIBERS = 0x02;
    public static final int OPT_QUERYABLES  = 0x04;
    public static final int OPT_TOKENS      = 0x08;
    public static final int OPT_RESTRICTED  = 0x10;
    public static final int OPT_NAMED       = 0x20;
    public static final int OPT_MAPPING     = 0x40;
    public static final int OPT_AGGREGATE   = 0x80;

    /** OR of KEYEXPRS | SUBSCRIBERS | QUERYABLES | TOKENS. */
    public static final int OPT_ALL_KINDS =
            OPT_KEYEXPRS | OPT_SUBSCRIBERS | OPT_QUERYABLES | OPT_TOKENS;

    public Interest {
        Objects.requireNonNull(mode, "mode");
        if (id < 0 || id > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("id must fit in u32: " + id);
        }
        if (keyScope < 0 || keyScope > 0xFFFF) {
            throw new IllegalArgumentException("keyScope must fit in u16: " + keyScope);
        }
        extensions = extensions == null ? List.of() : List.copyOf(extensions);

        // Normalise options per the reference implementation's Interest::options():
        //   - if wire_expr is present -> R=1; N is set iff key_suffix != null
        //   - if wire_expr is absent  -> R=0; N and M forced to 0
        if (mode == Mode.FINAL) {
            options = 0;   // FINAL never carries options / key / mode-dependent bits
        } else {
            boolean restricted = (options & OPT_RESTRICTED) != 0
                    || keySuffix != null
                    || keyScope != 0;
            if (restricted) {
                options |= OPT_RESTRICTED;
                if (keySuffix != null) options |= OPT_NAMED;
                else                   options &= ~OPT_NAMED;
                // M flag preserved as-caller-set (we default receiver-mapped)
            } else {
                options &= ~(OPT_RESTRICTED | OPT_NAMED | OPT_MAPPING);
            }
        }
    }

    // ---- Convenience factories ----------------------------------------

    /**
     * The most common shape for subscriber-side topic discovery: subscribe
     * to both current AND future DeclareSubscriber records for the given
     * key expression pattern.
     */
    public static Interest discoverSubscribers(long id, String keyExpr) {
        return new Interest(id, Mode.CURRENT_FUTURE, OPT_SUBSCRIBERS,
                0, keyExpr, List.of());
    }

    /**
     * Same as {@link #discoverSubscribers} but for all declaration kinds
     * (keyexprs + subscribers + queryables + tokens).
     */
    public static Interest discoverAll(long id, String keyExpr) {
        return new Interest(id, Mode.CURRENT_FUTURE, OPT_ALL_KINDS,
                0, keyExpr, List.of());
    }

    /** Terminate a running Future / CurrentFuture interest. */
    public static Interest finalOf(long id) {
        return new Interest(id, Mode.FINAL, 0, 0, null, List.of());
    }

    // ---- Encode / decode ----------------------------------------------

    public byte[] encode() {
        WBuf w = new WBuf(16);
        boolean z = !extensions.isEmpty();
        int header = ID | ((mode.bits & 0b11) << MODE_SHIFT) | (z ? FLAG_Z : 0);
        w.u8(header);
        w.varInt(id);
        if (mode != Mode.FINAL) {
            w.u8(options & 0xFF);
            if ((options & OPT_RESTRICTED) != 0) {
                w.varInt(keyScope);
                if (keySuffix != null) {
                    w.lenBytes(keySuffix.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        if (z) Extension.writeAll(extensions, w);
        return w.toByteArray();
    }

    public static Interest decode(byte[] data) {
        RBuf r = new RBuf(data);
        int header = r.u8();
        int id = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "Interest.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        Mode mode = Mode.fromBits((header >> MODE_SHIFT) & 0b11);
        boolean hasZ = (header & FLAG_Z) != 0;
        long interestId = r.varInt();

        int options = 0;
        int scope = 0;
        String suffix = null;
        if (mode != Mode.FINAL) {
            options = r.u8();
            if ((options & OPT_RESTRICTED) != 0) {
                long sc = r.varInt();
                if (sc < 0 || sc > 0xFFFF) {
                    throw new IllegalArgumentException(
                            "Interest.decode: keyScope out of u16 range: " + sc);
                }
                scope = (int) sc;
                if ((options & OPT_NAMED) != 0) {
                    suffix = new String(r.lenBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        return new Interest(interestId, mode, options, scope, suffix, exts);
    }

    // ---- Accessors ---------------------------------------------------

    public boolean wantsKeyExprs()    { return (options & OPT_KEYEXPRS)    != 0; }
    public boolean wantsSubscribers() { return (options & OPT_SUBSCRIBERS) != 0; }
    public boolean wantsQueryables()  { return (options & OPT_QUERYABLES)  != 0; }
    public boolean wantsTokens()      { return (options & OPT_TOKENS)      != 0; }
    public boolean isRestricted()     { return (options & OPT_RESTRICTED)  != 0; }
    public boolean isNamed()          { return (options & OPT_NAMED)       != 0; }
    public boolean senderMapping()    { return (options & OPT_MAPPING)     != 0; }
    public boolean isAggregate()      { return (options & OPT_AGGREGATE)   != 0; }

    @Override public String toString() {
        return "Interest{id=" + id + ", mode=" + mode
                + ", options=0x" + Integer.toHexString(options & 0xFF)
                + (keySuffix == null ? "" : ", key='" + keySuffix + "'")
                + ", extensions=" + extensions.size() + "}";
    }
}

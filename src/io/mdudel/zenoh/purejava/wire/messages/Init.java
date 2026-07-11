/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.RBuf;
import io.mdudel.zenoh.purejava.wire.WBuf;
import io.mdudel.zenoh.purejava.wire.WhatAmI;
import io.mdudel.zenoh.purejava.wire.ZenohId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Zenoh transport-level INIT message.
 *
 * <p>An INIT is either an {@link InitSyn} (A flag = 0, sent by the
 * initiator) or an {@link InitAck} (A flag = 1, sent by the responder).
 * They share the same on-wire layout except for the presence of the
 * Cookie field, which is only carried when A = 1.</p>
 *
 * <p>Wire layout (from the reference source):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|S|A|   INIT  |    header byte: id=0x01 | S | A | Z
 * +-+-+-+---------+
 * |    version    |
 * +---------------+
 * |zid_len|x|x|wai|    high 4 bits = actual ZID length - 1
 * +-------+-+-+---+    low  2 bits = WhatAmI (0b00 Router, 0b01 Peer, 0b10 Client)
 * ~      [u8]     ~    ZID bytes
 * +---------------+
 * |x|x|x|x|rid|fsn| \  if S=1: sn/id resolution (bit0 = fsn, bit1 = rid,
 * +---------------+  |          top bits reserved)
 * |     u16 LE    |  |         then a little-endian u16 BatchSize
 * |               | /
 * +---------------+
 * ~    <u8;zN>    ~    if A=1: Cookie (varint-length-prefixed byte string)
 * +---------------+
 * ~   [InitExts]  ~    if Z=1: extension chain
 * +---------------+
 * </pre>
 *
 * <p>The {@code Z} extension bit and the extension chain use the common
 * TLV format documented on {@link Extension}.</p>
 *
 * <p>{@code S} negotiates sequence-number / request-id resolutions and the
 * per-batch size. The pair of 2-bit fields inside the SNI byte
 * ({@code fsn} for frame SN, {@code rid} for request id) encode a
 * resolution enum (0=U8, 1=U16, 2=U32, 3=U64). This publisher-only client
 * omits {@code S} in its InitSyn (the server then uses its defaults) and
 * ignores it on the InitAck side.</p>
 */
public final class Init {

    // ---- header identity + flag bits (per transport::id and per-message flag mods) ----

    /** Transport-message id byte for INIT. */
    public static final int ID    = 0x01;
    /** A flag: 0 = InitSyn (request), 1 = InitAck (response). */
    public static final int FLAG_A = 0x20;
    /** S flag: 1 = size params (SN resolutions + batch size) follow. */
    public static final int FLAG_S = 0x40;
    /** Z flag: 1 = extension chain follows the rest of the fixed body. */
    public static final int FLAG_Z = 0x80;

    private Init() {}

    // ---- InitSyn (client -> server) ------------------------------------

    /** Immutable value carrier for a client-side InitSyn. */
    public record InitSyn(
            int             version,
            WhatAmI         whatAmI,
            ZenohId         zid,
            List<Extension> extensions) {

        public InitSyn {
            if (version < 0 || version > 0xFF) {
                throw new IllegalArgumentException("version must fit in u8: " + version);
            }
            if (whatAmI == null) throw new IllegalArgumentException("whatAmI is null");
            if (zid == null)     throw new IllegalArgumentException("zid is null");
            extensions = extensions == null
                    ? Collections.emptyList()
                    : List.copyOf(extensions);
        }

        /** Convenience: build a client-mode InitSyn with a random ZID and no extensions. */
        public static InitSyn clientDefault(int version) {
            return new InitSyn(version, WhatAmI.CLIENT, ZenohId.random(), List.of());
        }

        /** Encode this InitSyn to bytes. */
        public byte[] encode() {
            WBuf w = new WBuf(32);
            boolean z = !extensions.isEmpty();
            int header = ID | (z ? FLAG_Z : 0);   // A=0 (SYN), S=0 (no size negotiation)
            w.u8(header);
            w.u8(version);
            int lenNibble = zid.encodedLenNibble();     // 0..15
            int lenWai = ((lenNibble & 0x0F) << 4) | (whatAmI.bits & 0x03);
            w.u8(lenWai);
            w.bytes(zid.bytes());
            if (z) Extension.writeAll(extensions, w);
            return w.toByteArray();
        }
    }

    // ---- InitAck (server -> client) ------------------------------------

    /** Immutable value carrier for the server's InitAck reply. */
    public record InitAck(
            int             version,
            WhatAmI         whatAmI,
            ZenohId         zid,
            /** SN/ID resolution byte if S was set on the wire, else -1. */
            int             sniByteOrMinus1,
            /** Batch size if S was set on the wire, else -1. */
            int             batchSizeOrMinus1,
            byte[]          cookie,
            List<Extension> extensions) {

        public InitAck {
            extensions = extensions == null ? List.of() : List.copyOf(extensions);
            cookie = cookie == null ? new byte[0] : cookie.clone();
        }

        /** Parse an InitAck from bytes. Throws if the header id or A flag are wrong. */
        public static InitAck decode(byte[] data) {
            RBuf r = new RBuf(data);
            int header = r.u8();
            int id     = header & 0x1F;
            if (id != ID) {
                throw new IllegalArgumentException(
                        "InitAck.decode: expected id 0x" + Integer.toHexString(ID)
                                + " got 0x" + Integer.toHexString(id));
            }
            if ((header & FLAG_A) == 0) {
                throw new IllegalArgumentException(
                        "InitAck.decode: A flag not set - looks like an InitSyn, not an InitAck");
            }
            boolean hasS = (header & FLAG_S) != 0;
            boolean hasZ = (header & FLAG_Z) != 0;

            int version = r.u8();
            int lenWai  = r.u8();
            int lenNib  = (lenWai >>> 4) & 0x0F;
            WhatAmI wai = WhatAmI.fromBits(lenWai & 0x03);
            ZenohId zid = ZenohId.readAfterLenNibble(lenNib, r);

            int sni       = hasS ? r.u8()    : -1;
            int batchSize = hasS ? r.u16le() : -1;

            byte[] cookie = r.lenBytes(); // always present on an InitAck (A=1)

            List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();

            return new InitAck(version, wai, zid, sni, batchSize, cookie, exts);
        }
    }

    // Utility for tests
    static List<Extension> copy(List<Extension> in) {
        return in == null ? List.of() : new ArrayList<>(in);
    }
}

/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.RBuf;
import io.mdudel.zenoh.purejava.wire.WBuf;
import io.mdudel.zenoh.purejava.wire.WhatAmIMatcher;
import io.mdudel.zenoh.purejava.wire.ZenohId;

import java.util.List;
import java.util.Optional;

/**
 * The Zenoh scouting-level SCOUT message.
 *
 * <p>A SCOUT is sent (typically via UDP multicast to
 * {@code 224.0.0.224:7446}) to ask peers/routers on the local segment to
 * announce themselves via HELLO replies. Any node whose role bit is set in
 * the SCOUT's {@link WhatAmIMatcher} SHOULD reply.</p>
 *
 * <p>Wire layout (from the reference source ASCII diagram in
 * {@code commons/zenoh-protocol/src/scouting/scout.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|X|X|  SCOUT  |    header byte: id=0x01 | Z(bit7)
 * +-+-+-+---------+
 * |    version    |
 * +---------------+
 * |zid_len|I| what|    (#)(*)
 * +-+-+-+-+-+-+-+-+
 * ~      [u8]     ~    if I==1 -- ZenohID bytes (length = 1 + zid_len)
 * +---------------+
 * ~   [ScoutExts] ~    if Z==1 -- extension chain
 * +---------------+
 *
 * (#) zid_len: high 4 bits of the flags byte. real_len = 1 + zid_len.
 * (*) what:    low 3 bits, a WhatAmIMatcher bitmap:
 *              0b001 Router | 0b010 Peer | 0b100 Client
 * </pre>
 *
 * <p><b>Scouting id space is disjoint</b> from the transport layer; SCOUT
 * happens to share the id byte value {@code 0x01} with the transport-layer
 * INIT, but the two are demultiplexed by their transport (SCOUT arrives on
 * the UDP multicast socket, INIT on a Zenoh session TCP/TLS/WS link) and
 * never coexist in the same byte stream.</p>
 *
 * <p>Note the flag positions differ from the transport layer:</p>
 * <ul>
 *   <li>{@link #FLAG_I} = {@code 0x08} - ZID present bit (bit 3 of header
 *       flags byte, NOT the header byte)</li>
 *   <li>{@link #FLAG_Z} = {@code 0x80} - extensions follow (bit 7 of the
 *       <em>header</em> byte, matching every other Zenoh message)</li>
 * </ul>
 */
public final class Scout {

    /** Scouting-message id byte for SCOUT. */
    public static final int ID       = 0x01;
    /** Z flag on the header byte: 1 = extension chain follows. */
    public static final int FLAG_Z   = 0x80;
    /** I flag on the flags/what byte: 1 = ZID is present. */
    public static final int FLAG_I   = 0x08;

    private final int             version;
    private final WhatAmIMatcher  what;
    private final ZenohId         zid;         // may be null
    private final List<Extension> extensions;

    public Scout(int version,
                 WhatAmIMatcher what,
                 ZenohId zid,
                 List<Extension> extensions) {
        if (version < 0 || version > 0xFF) {
            throw new IllegalArgumentException("version must fit in u8: " + version);
        }
        if (what == null) throw new IllegalArgumentException("what is null");
        this.version    = version;
        this.what       = what;
        this.zid        = zid;
        this.extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** Convenience: no ZID, no extensions. */
    public Scout(int version, WhatAmIMatcher what) {
        this(version, what, null, List.of());
    }

    public int             version()    { return version; }
    public WhatAmIMatcher  what()       { return what; }
    public Optional<ZenohId> zid()      { return Optional.ofNullable(zid); }
    public List<Extension> extensions() { return extensions; }

    // ---- encode --------------------------------------------------------

    public byte[] encode() {
        WBuf w = new WBuf(16);
        boolean hasZ = !extensions.isEmpty();
        int header = ID | (hasZ ? FLAG_Z : 0);
        w.u8(header);
        w.u8(version);

        int flags = what.bits() & 0b111;
        if (zid != null) {
            flags |= FLAG_I;
            flags |= (zid.encodedLenNibble() & 0x0F) << 4;
        }
        w.u8(flags);

        if (zid != null) {
            w.bytes(zid.bytes());
        }
        if (hasZ) Extension.writeAll(extensions, w);
        return w.toByteArray();
    }

    // ---- decode --------------------------------------------------------

    public static Scout decode(byte[] data) {
        RBuf r = new RBuf(data);
        int header = r.u8();
        int id     = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "Scout.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean hasZ = (header & FLAG_Z) != 0;

        int version = r.u8();
        int flags   = r.u8();
        WhatAmIMatcher what = WhatAmIMatcher.fromBits(flags & 0b111);

        ZenohId zid = null;
        if ((flags & FLAG_I) != 0) {
            int lenNib = (flags >>> 4) & 0x0F;
            zid = ZenohId.readAfterLenNibble(lenNib, r);
        }

        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        return new Scout(version, what, zid, exts);
    }
}

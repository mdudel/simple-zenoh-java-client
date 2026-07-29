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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Zenoh scouting-level HELLO message.
 *
 * <p>A HELLO is either the unicast reply to a {@link Scout}, or a periodic
 * multicast advertisement of a node's presence. It carries the sender's
 * role, ZenohID, and the network locators it accepts sessions on.</p>
 *
 * <p>Wire layout (from the reference source ASCII diagram in
 * {@code commons/zenoh-protocol/src/scouting/hello.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|X|L|  HELLO  |    header byte: id=0x02 | L(bit5) | Z(bit7)
 * +-+-+-+---------+
 * |    version    |
 * +---------------+
 * |zid_len|X|X|wai|    (*)
 * +-+-+-+-+-+-+-+-+
 * ~     [u8]      ~    ZenohID bytes (length = 1 + zid_len; ALWAYS present)
 * +---------------+
 * ~   [Locator]   ~    if L==1 -- list of locators (varint count then
 * +---------------+                 <utf8;z8> entries; each locator string
 *                                   is a single-byte-length UTF-8)
 * ~  [HelloExts]  ~    if Z==1 -- extension chain
 * +---------------+
 *
 * (*) WhatAmI (low 2 bits): 0b00 Router, 0b01 Peer, 0b10 Client, 0b11 reserved.
 * </pre>
 *
 * <p>If {@code L==0}, the receiver treats the UDP source address of the
 * HELLO packet as the implicit single locator; this class returns an empty
 * locator list in that case and leaves it to the caller (typically
 * {@code PureJavaZenohScout}) to synthesise the source-address locator.</p>
 *
 * <p><b>Scouting id space is disjoint</b> from the transport layer;
 * HELLO's id {@code 0x02} happens to match the transport-layer OPEN's id
 * but the two are demultiplexed by their transport and never coexist in
 * the same byte stream.</p>
 */
public final class Hello {

    /** Scouting-message id byte for HELLO. */
    public static final int ID     = 0x02;
    /** L flag on the header byte: 1 = explicit locator list follows. */
    public static final int FLAG_L = 0x20;
    /** Z flag on the header byte: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    /** Maximum length in bytes of a single locator string on the wire. */
    public static final int MAX_LOCATOR_LEN = 255;

    private final int             version;
    private final WhatAmI         whatAmI;
    private final ZenohId         zid;
    private final List<String>    locators;
    private final List<Extension> extensions;

    public Hello(int version,
                 WhatAmI whatAmI,
                 ZenohId zid,
                 List<String> locators,
                 List<Extension> extensions) {
        if (version < 0 || version > 0xFF) {
            throw new IllegalArgumentException("version must fit in u8: " + version);
        }
        if (whatAmI == null) throw new IllegalArgumentException("whatAmI is null");
        if (zid == null)     throw new IllegalArgumentException("zid is null");

        List<String> locs = locators == null ? List.of() : List.copyOf(locators);
        for (String s : locs) {
            if (s == null) throw new IllegalArgumentException("locator is null");
            int enc = s.getBytes(StandardCharsets.UTF_8).length;
            if (enc > MAX_LOCATOR_LEN) {
                throw new IllegalArgumentException(
                        "locator UTF-8 length " + enc
                                + " exceeds z8 max " + MAX_LOCATOR_LEN
                                + ": " + s);
            }
        }
        this.version    = version;
        this.whatAmI    = whatAmI;
        this.zid        = zid;
        this.locators   = locs;
        this.extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    public int             version()    { return version; }
    public WhatAmI         whatAmI()    { return whatAmI; }
    public ZenohId         zid()        { return zid; }
    /** Never null; may be empty (in which case UDP source is the implicit locator). */
    public List<String>    locators()   { return locators; }
    public List<Extension> extensions() { return extensions; }

    // ---- encode --------------------------------------------------------

    public byte[] encode() {
        WBuf w = new WBuf(32);
        boolean hasL = !locators.isEmpty();
        boolean hasZ = !extensions.isEmpty();
        int header = ID | (hasL ? FLAG_L : 0) | (hasZ ? FLAG_Z : 0);
        w.u8(header);
        w.u8(version);
        int lenWai = ((zid.encodedLenNibble() & 0x0F) << 4) | (whatAmI.bits & 0x03);
        w.u8(lenWai);
        w.bytes(zid.bytes());
        if (hasL) {
            w.varInt(locators.size());
            for (String s : locators) {
                byte[] enc = s.getBytes(StandardCharsets.UTF_8);
                w.u8(enc.length);      // z8 length
                w.bytes(enc);
            }
        }
        if (hasZ) Extension.writeAll(extensions, w);
        return w.toByteArray();
    }

    // ---- decode --------------------------------------------------------

    public static Hello decode(byte[] data) {
        RBuf r = new RBuf(data);
        int header = r.u8();
        int id     = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "Hello.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean hasL = (header & FLAG_L) != 0;
        boolean hasZ = (header & FLAG_Z) != 0;

        int version = r.u8();
        int lenWai  = r.u8();
        int lenNib  = (lenWai >>> 4) & 0x0F;
        WhatAmI wai = WhatAmI.fromBits(lenWai & 0x03);
        ZenohId zid = ZenohId.readAfterLenNibble(lenNib, r);

        List<String> locs;
        if (hasL) {
            long count = r.varInt();
            if (count < 0 || count > 1_000_000) {
                throw new IllegalArgumentException(
                        "Hello.decode: absurd locator count " + count);
            }
            locs = new ArrayList<>((int) count);
            for (int i = 0; i < count; i++) {
                int slen = r.u8();
                byte[] sbytes = r.bytes(slen);
                locs.add(new String(sbytes, StandardCharsets.UTF_8));
            }
        } else {
            locs = List.of();
        }

        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        return new Hello(version, wai, zid, locs, exts);
    }
}

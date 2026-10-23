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

/**
 * The Zenoh {@code DeclareQueryable} sub-message: announces that a peer
 * answers queries on a given key expression. Carried inside a
 * {@link Declare} network message.
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/network/declare.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|M|N|  D_QBL  |    header byte: id=0x04 (within DeclareBody) | N | M | Z
 * +-+-+-+---------+
 * ~   qbl_id:z32  ~    varint u32 queryable id (peer-chosen)
 * +---------------+
 * ~ key_scope:z16 ~    varint u16 numeric mapping-scope (0 = no mapping)
 * +---------------+
 * ~  key_suffix   ~    if N==1: length-prefixed UTF-8 (u8;z16)
 * +---------------+
 * ~  [decl_exts]  ~    if Z==1: extension chain (QueryableInfo, id 0x01)
 * +---------------+
 * </pre>
 *
 * <p>The layout is identical to {@link DeclareSubscriber} apart from the
 * sub-message id. The one meaningful difference is that queryables do
 * define an extension: QueryableInfo (extension id {@code 0x01}, z64
 * flavour) carrying the {@code complete} flag and a routing distance.
 * This class does not interpret that extension. It preserves the whole
 * chain verbatim in {@link #extensions()} so the message round-trips
 * byte-identically and a later revision can decode it without a wire
 * change here.</p>
 *
 * <p>This client never declares a queryable of its own, because it has
 * no Request/Response support. The type exists so that inbound DECLARE
 * records from an INTEREST carrying
 * {@link Interest#OPT_QUERYABLES} can be read rather than falling
 * through to {@link Declare.Body.BodyKind#RAW}.</p>
 */
public record DeclareQueryable(
        long            id,
        int             keyScope,
        String          keySuffix,          // null iff N==0
        boolean         senderMapping,      // M flag
        List<Extension> extensions) {

    /** Sub-message id within DeclareBody. */
    public static final int ID     = 0x04;
    /** N flag: 1 = key expr carries a name/suffix. */
    public static final int FLAG_N = 0x20;
    /** M flag: 1 = key-expr mapping declared by sender; 0 = by receiver. */
    public static final int FLAG_M = 0x40;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    public DeclareQueryable {
        if (id < 0 || id > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("id must fit in u32: " + id);
        }
        if (keyScope < 0 || keyScope > 0xFFFF) {
            throw new IllegalArgumentException("keyScope must fit in u16: " + keyScope);
        }
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** Convenience: standard shape (scope=0, no extensions, receiver-mapped, full-suffix). */
    public static DeclareQueryable ofKeyExpr(long id, String keyExpr) {
        if (keyExpr == null || keyExpr.isEmpty()) {
            throw new IllegalArgumentException("keyExpr must be non-empty");
        }
        return new DeclareQueryable(id, 0, keyExpr, false, List.of());
    }

    /** Encode into the supplied buffer (used as the DeclareBody payload). */
    public void encode(WBuf w) {
        boolean n = (keySuffix != null);
        boolean m = senderMapping;
        boolean z = !extensions.isEmpty();
        int header = ID
                | (n ? FLAG_N : 0)
                | (m ? FLAG_M : 0)
                | (z ? FLAG_Z : 0);
        w.u8(header);
        w.varInt(id);
        w.varInt(keyScope);
        if (n) {
            byte[] suffixBytes = keySuffix.getBytes(StandardCharsets.UTF_8);
            w.lenBytes(suffixBytes);
        }
        if (z) Extension.writeAll(extensions, w);
    }

    /** Decode from the supplied buffer, given the header byte already read. */
    public static DeclareQueryable decode(int header, RBuf r) {
        int id = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "DeclareQueryable.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean hasN = (header & FLAG_N) != 0;
        boolean hasM = (header & FLAG_M) != 0;
        boolean hasZ = (header & FLAG_Z) != 0;
        long qblId = r.varInt();
        long scope = r.varInt();
        if (scope < 0 || scope > 0xFFFF) {
            throw new IllegalArgumentException(
                    "DeclareQueryable.decode: keyScope out of u16 range: " + scope);
        }
        String suffix = hasN ? new String(r.lenBytes(), StandardCharsets.UTF_8) : null;
        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        return new DeclareQueryable(qblId, (int) scope, suffix, hasM, exts);
    }

    @Override public String toString() {
        return "DeclareQueryable{id=" + id
                + ", scope=" + keyScope
                + ", suffix=" + (keySuffix == null ? "<mapping-only>" : "'" + keySuffix + "'")
                + ", mapping=" + (senderMapping ? "SENDER" : "RECEIVER")
                + ", extensions=" + extensions.size() + "}";
    }
}

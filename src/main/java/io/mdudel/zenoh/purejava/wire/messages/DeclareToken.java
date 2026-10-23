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
 * The Zenoh {@code DeclareToken} sub-message: announces a liveliness
 * token on a given key expression. Carried inside a {@link Declare}
 * network message.
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/network/declare.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|M|N|  D_TKN  |    header byte: id=0x06 (within DeclareBody) | N | M | Z
 * +-+-+-+---------+
 * ~   tkn_id:z32  ~    varint u32 token id (peer-chosen)
 * +---------------+
 * ~ key_scope:z16 ~    varint u16 numeric mapping-scope (0 = no mapping)
 * +---------------+
 * ~  key_suffix   ~    if N==1: length-prefixed UTF-8 (u8;z16)
 * +---------------+
 * ~  [decl_exts]  ~    if Z==1: extension chain
 * +---------------+
 * </pre>
 *
 * <p>Liveliness tokens are the closest thing Zenoh has to deliberate
 * service registration. An application declares a token to assert "I am
 * alive at this key", and the token is withdrawn automatically when the
 * session drops. That makes tokens a higher-confidence discovery source
 * than either declared subscribers or observed traffic, because a token
 * is an explicit statement of intent rather than an inference.</p>
 *
 * <p>The layout is identical to {@link DeclareSubscriber} apart from the
 * sub-message id. This client never declares a token of its own; the
 * type exists so inbound DECLARE records from an INTEREST carrying
 * {@link Interest#OPT_TOKENS} can be read rather than falling through to
 * {@link Declare.Body.BodyKind#RAW}.</p>
 */
public record DeclareToken(
        long            id,
        int             keyScope,
        String          keySuffix,          // null iff N==0
        boolean         senderMapping,      // M flag
        List<Extension> extensions) {

    /** Sub-message id within DeclareBody. */
    public static final int ID     = 0x06;
    /** N flag: 1 = key expr carries a name/suffix. */
    public static final int FLAG_N = 0x20;
    /** M flag: 1 = key-expr mapping declared by sender; 0 = by receiver. */
    public static final int FLAG_M = 0x40;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    public DeclareToken {
        if (id < 0 || id > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("id must fit in u32: " + id);
        }
        if (keyScope < 0 || keyScope > 0xFFFF) {
            throw new IllegalArgumentException("keyScope must fit in u16: " + keyScope);
        }
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** Convenience: standard shape (scope=0, no extensions, receiver-mapped, full-suffix). */
    public static DeclareToken ofKeyExpr(long id, String keyExpr) {
        if (keyExpr == null || keyExpr.isEmpty()) {
            throw new IllegalArgumentException("keyExpr must be non-empty");
        }
        return new DeclareToken(id, 0, keyExpr, false, List.of());
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
    public static DeclareToken decode(int header, RBuf r) {
        int id = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "DeclareToken.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean hasN = (header & FLAG_N) != 0;
        boolean hasM = (header & FLAG_M) != 0;
        boolean hasZ = (header & FLAG_Z) != 0;
        long tknId = r.varInt();
        long scope = r.varInt();
        if (scope < 0 || scope > 0xFFFF) {
            throw new IllegalArgumentException(
                    "DeclareToken.decode: keyScope out of u16 range: " + scope);
        }
        String suffix = hasN ? new String(r.lenBytes(), StandardCharsets.UTF_8) : null;
        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        return new DeclareToken(tknId, (int) scope, suffix, hasM, exts);
    }

    @Override public String toString() {
        return "DeclareToken{id=" + id
                + ", scope=" + keyScope
                + ", suffix=" + (keySuffix == null ? "<mapping-only>" : "'" + keySuffix + "'")
                + ", mapping=" + (senderMapping ? "SENDER" : "RECEIVER")
                + ", extensions=" + extensions.size() + "}";
    }
}

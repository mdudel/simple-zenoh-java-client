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
 * The Zenoh network-level PUSH message: a fire-and-forget carrier that
 * routes a {@link Put} (or a Del) payload to a key expression.
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/network/push.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|M|N|  PUSH   |    header byte: id=0x1d | N | M | Z
 * +-+-+-+---------+
 * ~ key_scope:z16 ~    varint (u16 range enforced by codec)
 * +---------------+
 * ~  key_suffix   ~    if N==1: length-prefixed UTF-8 (u8;z16)
 * +---------------+
 * ~  [push_exts]  ~    if Z==1: extension chain (QoS/Timestamp/NodeId)
 * +---------------+
 * ~   PushBody    ~    e.g. serialised {@link Put} (id=0x01) or Del (id=0x02)
 * +---------------+
 * </pre>
 *
 * <ul>
 *   <li>N flag: 1 = the WireExpr has a name/suffix; 0 = pure numeric mapping.</li>
 *   <li>M flag: 0 = key-expr mapping was declared by the <b>receiver</b>
 *       (default, used by simple publishers that never {@code DeclareKeyExpr});
 *       1 = mapping was declared by the <b>sender</b>.</li>
 *   <li>Z flag: 1 = extension chain follows.</li>
 * </ul>
 *
 * <p>The publisher-only MVP always emits {@code M=0} (receiver-mapped)
 * and {@code N=1} with {@code scope=0}, i.e. sends the full key
 * string on every message. That trades a few bytes per message for
 * not having to implement {@code DeclareKeyExpr}. When
 * {@code DeclareKeyExpr} is added later, this class already handles
 * both flag combinations on encode and decode.</p>
 *
 * <p>Body-payload bytes are stored opaquely; construct via
 * {@link #ofPut(String, Put)} to get the standard "full suffix,
 * receiver-mapped, no QoS/timestamp/nodeid extensions" shape used
 * by an accreditation-friendly publisher.</p>
 */
public record Push(
        int             keyScope,
        String          keySuffix,          // null iff N==0
        boolean         senderMapping,      // M flag
        List<Extension> extensions,
        byte[]          body) {

    /** Network-message id byte for PUSH. */
    public static final int ID     = 0x1d;
    /** N flag: 1 = key expr carries a name/suffix. */
    public static final int FLAG_N = 0x20;
    /** M flag: 1 = key-expr mapping declared by sender; 0 = by receiver. */
    public static final int FLAG_M = 0x40;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    public Push {
        if (keyScope < 0 || keyScope > 0xFFFF) {
            throw new IllegalArgumentException(
                    "keyScope must fit in u16: " + keyScope);
        }
        // keySuffix null iff caller intends N=0 (mapping-only expr)
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        body       = body       == null ? new byte[0] : body.clone();
    }

    /**
     * Convenience for the standard publisher path: full-suffix, receiver-mapped,
     * no extensions, body is an already-encoded {@link Put}.
     */
    public static Push ofPut(String keyExpr, Put put) {
        if (keyExpr == null || keyExpr.isEmpty()) {
            throw new IllegalArgumentException("keyExpr must be non-empty");
        }
        return new Push(0, keyExpr, false, List.of(), put.encode());
    }

    /** Encode this Push to bytes. */
    public byte[] encode() {
        WBuf w = new WBuf(body.length + 16);
        boolean n = (keySuffix != null);
        boolean m = senderMapping;
        boolean z = !extensions.isEmpty();
        int header = ID
                | (n ? FLAG_N : 0)
                | (m ? FLAG_M : 0)
                | (z ? FLAG_Z : 0);
        w.u8(header);
        w.varInt(keyScope);
        if (n) {
            byte[] suffixBytes = keySuffix.getBytes(StandardCharsets.UTF_8);
            w.lenBytes(suffixBytes);
        }
        if (z) Extension.writeAll(extensions, w);
        w.bytes(body);
        return w.toByteArray();
    }

    /** Parse a Push from bytes; the tail is treated as opaque body. */
    public static Push decode(byte[] data) {
        RBuf r = new RBuf(data);
        int header = r.u8();
        int id = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "Push.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean hasN = (header & FLAG_N) != 0;
        boolean hasM = (header & FLAG_M) != 0;
        boolean hasZ = (header & FLAG_Z) != 0;
        long scope = r.varInt();
        if (scope < 0 || scope > 0xFFFF) {
            throw new IllegalArgumentException(
                    "Push.decode: keyScope out of u16 range: " + scope);
        }
        String suffix = null;
        if (hasN) {
            suffix = new String(r.lenBytes(), StandardCharsets.UTF_8);
        }
        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        byte[] body = r.bytes(r.remaining());
        return new Push((int) scope, suffix, hasM, exts, body);
    }

    @Override public String toString() {
        return "Push{scope=" + keyScope
                + ", suffix=" + (keySuffix == null ? "<mapping-only>" : "'" + keySuffix + "'")
                + ", mapping=" + (senderMapping ? "SENDER" : "RECEIVER")
                + ", extensions=" + extensions.size()
                + ", body=" + body.length + "B}";
    }
}

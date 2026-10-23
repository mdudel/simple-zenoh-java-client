/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.RBuf;
import io.mdudel.zenoh.purejava.wire.WBuf;

import java.util.List;

/**
 * The Zenoh {@code UndeclareToken} sub-message: a previously-declared
 * liveliness token is gone. Carried inside a {@link Declare} network
 * message.
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/network/declare.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|X|X|  U_TKN  |    header byte: id=0x07 (within DeclareBody) | Z
 * +---------------+
 * ~   tkn_id:z32  ~    varint u32 token id (must match earlier D_TKN)
 * +---------------+
 * ~  [decl_exts]  ~    if Z==1: extension chain (WireExpr echo, extension id 0x0f)
 * +---------------+
 * </pre>
 *
 * <p>Identical in shape to {@link UndeclareSubscriber}. This is the
 * message that makes liveliness useful: when a peer holding a token
 * dies, the router withdraws the token on its behalf, so an observer
 * learns the peer is gone without polling. As with the other undeclare
 * bodies, only the id travels, so the consumer must remember the
 * id-to-key binding from the original {@link DeclareToken}.</p>
 */
public record UndeclareToken(
        long            id,
        List<Extension> extensions) {

    /** Sub-message id within DeclareBody. */
    public static final int ID     = 0x07;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    public UndeclareToken {
        if (id < 0 || id > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("id must fit in u32: " + id);
        }
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** Convenience: undeclare by id, no extensions. */
    public static UndeclareToken of(long id) {
        return new UndeclareToken(id, List.of());
    }

    /** Encode into the supplied buffer (used as the DeclareBody payload). */
    public void encode(WBuf w) {
        boolean z = !extensions.isEmpty();
        int header = ID | (z ? FLAG_Z : 0);
        w.u8(header);
        w.varInt(id);
        if (z) Extension.writeAll(extensions, w);
    }

    /** Decode from the supplied buffer, given the header byte already read. */
    public static UndeclareToken decode(int header, RBuf r) {
        int id = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "UndeclareToken.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean hasZ = (header & FLAG_Z) != 0;
        long tknId = r.varInt();
        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        return new UndeclareToken(tknId, exts);
    }

    @Override public String toString() {
        return "UndeclareToken{id=" + id
                + ", extensions=" + extensions.size() + "}";
    }
}

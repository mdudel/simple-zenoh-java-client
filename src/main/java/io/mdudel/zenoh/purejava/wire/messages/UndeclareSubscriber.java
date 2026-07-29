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
 * The Zenoh {@code UndeclareSubscriber} sub-message: tells the router
 * that a previously-declared subscriber should be torn down. Carried
 * inside a {@link Declare} network message.
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/network/declare.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|X|X|  U_SUB  |    header byte: id=0x03 (within DeclareBody) | Z
 * +---------------+
 * ~  subs_id:z32  ~    varint u32 subscriber id (must match earlier D_SUB)
 * +---------------+
 * ~  [decl_exts]  ~    if Z==1: extension chain (WireExpr echo, extension id 0x0f)
 * +---------------+
 * </pre>
 *
 * <p>The optional WireExpr extension is a copy of the declared key
 * expression, useful for the router when it can't look up the original
 * declaration (rare; not needed by our MVP client). Encode leaves the
 * extension chain empty by default; decode preserves any that arrive.</p>
 */
public record UndeclareSubscriber(
        long            id,
        List<Extension> extensions) {

    /** Sub-message id within DeclareBody. */
    public static final int ID     = 0x03;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    public UndeclareSubscriber {
        if (id < 0 || id > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("id must fit in u32: " + id);
        }
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** Convenience: undeclare by id, no extensions. */
    public static UndeclareSubscriber of(long id) {
        return new UndeclareSubscriber(id, List.of());
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
    public static UndeclareSubscriber decode(int header, RBuf r) {
        int id = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "UndeclareSubscriber.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean hasZ = (header & FLAG_Z) != 0;
        long subsId = r.varInt();
        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        return new UndeclareSubscriber(subsId, exts);
    }

    @Override public String toString() {
        return "UndeclareSubscriber{id=" + id
                + ", extensions=" + extensions.size() + "}";
    }
}

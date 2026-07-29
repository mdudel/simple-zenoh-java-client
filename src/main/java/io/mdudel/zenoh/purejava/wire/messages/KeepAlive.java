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
 * The Zenoh transport-level KEEP_ALIVE message.
 *
 * <p>Sent periodically to prevent the link lease from expiring on the
 * peer. The spec recommends sending one every {@code lease / 4} of
 * whatever lease was agreed in the OpenSyn/OpenAck exchange, and MAY
 * be skipped if other traffic went out on the link within the same
 * interval.</p>
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/transport/keepalive.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|X|X| KALIVE  |    header byte: id=0x04 | Z
 * +-+-+-+---------+
 * ~  [KAliveExts] ~    if Z=1: extension chain
 * +---------------+
 * </pre>
 *
 * <p>No body fields. The extension chain is the only variable
 * content. For the publisher MVP we emit no extensions.</p>
 */
public record KeepAlive(List<Extension> extensions) {

    /** Transport-message id byte for KEEP_ALIVE. */
    public static final int ID     = 0x04;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    /** Singleton empty KeepAlive - the common outbound case. */
    public static final KeepAlive EMPTY = new KeepAlive(List.of());

    public KeepAlive {
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** Encode this KeepAlive to bytes. */
    public byte[] encode() {
        boolean z = !extensions.isEmpty();
        WBuf w = new WBuf(z ? 4 : 1);
        int header = ID | (z ? FLAG_Z : 0);
        w.u8(header);
        if (z) Extension.writeAll(extensions, w);
        return w.toByteArray();
    }

    /** Parse a KeepAlive from bytes. */
    public static KeepAlive decode(byte[] data) {
        RBuf r = new RBuf(data);
        int header = r.u8();
        int id = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "KeepAlive.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean hasZ = (header & FLAG_Z) != 0;
        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        return new KeepAlive(exts);
    }
}

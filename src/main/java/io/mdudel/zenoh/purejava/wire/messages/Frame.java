/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.RBuf;
import io.mdudel.zenoh.purejava.wire.WBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * The Zenoh transport-level FRAME message: a container that carries one
 * or more serialised network messages (typically {@link Push}) with a
 * single shared sequence number.
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/transport/frame.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|X|R|  FRAME  |    header byte: id=0x05 | R | Z
 * +-+-+-+---------+
 * %    seq num    %    varint TransportSn
 * +---------------+
 * ~  [FrameExts]  ~    if Z=1: extension chain (QoS ext only defined so far)
 * +---------------+
 * ~  [NetworkMsg] ~    zero-or-more back-to-back serialised network messages
 * +---------------+
 * </pre>
 *
 * <p>Notes:
 * <ul>
 *   <li>R flag: 1 = reliable channel, 0 = best-effort channel.</li>
 *   <li>Z flag: 1 = extension chain follows.</li>
 *   <li>The frame body is a raw concatenation of serialised
 *       {@link Push} (or other network) messages. There is no framing
 *       between them; each network message is self-delimiting. On
 *       stream transports (TCP), a 16-bit little-endian length prefix
 *       is prepended to the whole FRAME by the transport layer &mdash;
 *       that framing is <b>not</b> part of this class.</li>
 * </ul>
 *
 * <p>This publisher-side implementation stores the encoded network
 * payload bytes opaquely; a helper constructor exists that takes a
 * {@link Push} directly and encodes it.</p>
 */
public record Frame(
        long            sn,
        boolean         reliable,
        List<Extension> extensions,
        byte[]          payload) {

    /** Transport-message id byte for FRAME. */
    public static final int ID     = 0x05;
    /** R flag: 1 = reliable channel, 0 = best-effort. */
    public static final int FLAG_R = 0x20;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    public Frame {
        if (sn < 0) {
            throw new IllegalArgumentException("sn must be non-negative (unsigned varint): " + sn);
        }
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        payload    = payload    == null ? new byte[0] : payload.clone();
    }

    /** Convenience: single-Push reliable frame with no extensions. */
    public static Frame ofPush(long sn, boolean reliable, Push push) {
        return new Frame(sn, reliable, List.of(), push.encode());
    }

    /** Encode this Frame to bytes (no outer 16-bit length prefix). */
    public byte[] encode() {
        WBuf w = new WBuf(payload.length + 8);
        boolean z = !extensions.isEmpty();
        int header = ID | (reliable ? FLAG_R : 0) | (z ? FLAG_Z : 0);
        w.u8(header);
        w.varInt(sn);
        if (z) Extension.writeAll(extensions, w);
        w.bytes(payload);
        return w.toByteArray();
    }

    /** Parse a Frame from bytes; the tail (all bytes past the header/extensions) is treated as opaque payload. */
    public static Frame decode(byte[] data) {
        RBuf r = new RBuf(data);
        int header = r.u8();
        int id = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "Frame.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean reliable = (header & FLAG_R) != 0;
        boolean hasZ     = (header & FLAG_Z) != 0;
        long sn = r.varInt();
        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        byte[] payload = r.bytes(r.remaining());
        return new Frame(sn, reliable, exts, payload);
    }

    @Override public String toString() {
        return "Frame{sn=" + sn
                + ", channel=" + (reliable ? "RELIABLE" : "BEST_EFFORT")
                + ", extensions=" + extensions.size()
                + ", payload=" + payload.length + "B}";
    }
}

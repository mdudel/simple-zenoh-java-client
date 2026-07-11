/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Encoding;
import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.RBuf;
import io.mdudel.zenoh.purejava.wire.Timestamp;
import io.mdudel.zenoh.purejava.wire.WBuf;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The Zenoh zenoh-level PUT message: the actual payload delivered under
 * a key expression, carried as the body of a {@link Push}.
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/zenoh/put.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|E|T|   PUT   |    header byte: id=0x01 | T | E | Z
 * +-+-+-+---------+
 * ~ ts: <u8;z16>  ~    if T==1: {@link Timestamp} (varint HLC + z-lengthed id)
 * +---------------+
 * ~   encoding    ~    if E==1: {@link Encoding} (varint(id<<1|S) + optional schema)
 * +---------------+
 * ~  [put_exts]   ~    if Z==1: extension chain (SourceInfo/Attachment)
 * +---------------+
 * ~ pl: <u8;z32>  ~    varint-length-prefixed payload (up to ~4 GB on wire)
 * +---------------+
 * </pre>
 *
 * <ul>
 *   <li>T flag: 1 = a timestamp is present.</li>
 *   <li>E flag: 1 = a non-empty {@link Encoding} is present.</li>
 *   <li>Z flag: 1 = extension chain follows (SourceInfo id=0x1, Attachment id=0x3).</li>
 * </ul>
 *
 * <p>MVP publisher path: no SourceInfo, no Attachment, encoding is
 * {@link Encoding#EMPTY} (E=0). Timestamp is optional; when omitted
 * the receiver stamps its own.</p>
 */
public record Put(
        Timestamp       timestamp,          // nullable (T flag)
        Encoding        encoding,           // never null; EMPTY suppresses E flag
        List<Extension> extensions,
        byte[]          payload) {

    /** PushBody id byte for PUT. */
    public static final int ID     = 0x01;
    /** T flag: 1 = timestamp present. */
    public static final int FLAG_T = 0x20;
    /** E flag: 1 = non-empty Encoding present. */
    public static final int FLAG_E = 0x40;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    public Put {
        encoding   = encoding   == null ? Encoding.EMPTY : encoding;
        extensions = extensions == null ? List.of()      : List.copyOf(extensions);
        payload    = payload    == null ? new byte[0]    : payload.clone();
    }

    /** Convenience: raw-bytes payload with default encoding and no timestamp/extensions. */
    public static Put ofBytes(byte[] payload) {
        return new Put(null, Encoding.EMPTY, List.of(), payload);
    }

    /** Convenience: UTF-8 string payload with {@link Encoding#ID_ZENOH_STRING} encoding. */
    public static Put ofString(String payload) {
        return new Put(null,
                Encoding.of(Encoding.ID_ZENOH_STRING),
                List.of(),
                payload.getBytes(StandardCharsets.UTF_8));
    }

    /** Convenience: JSON string payload with {@link Encoding#ID_APPLICATION_JSON} encoding. */
    public static Put ofJson(String json) {
        return new Put(null,
                Encoding.of(Encoding.ID_APPLICATION_JSON),
                List.of(),
                json.getBytes(StandardCharsets.UTF_8));
    }

    /** Encode this Put to bytes (ready to become the body of a {@link Push}). */
    public byte[] encode() {
        WBuf w = new WBuf(payload.length + 16);
        boolean t = (timestamp != null);
        boolean e = !encoding.isEmpty();
        boolean z = !extensions.isEmpty();
        int header = ID
                | (t ? FLAG_T : 0)
                | (e ? FLAG_E : 0)
                | (z ? FLAG_Z : 0);
        w.u8(header);
        if (t) timestamp.encode(w);
        if (e) encoding.encode(w);
        if (z) Extension.writeAll(extensions, w);
        w.lenBytes(payload);
        return w.toByteArray();
    }

    /** Parse a Put from bytes. */
    public static Put decode(byte[] data) {
        RBuf r = new RBuf(data);
        int header = r.u8();
        int id = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "Put.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean hasT = (header & FLAG_T) != 0;
        boolean hasE = (header & FLAG_E) != 0;
        boolean hasZ = (header & FLAG_Z) != 0;
        Timestamp ts       = hasT ? Timestamp.decode(r) : null;
        Encoding  enc      = hasE ? Encoding.decode(r)  : Encoding.EMPTY;
        List<Extension> ex = hasZ ? Extension.readAll(r) : List.of();
        byte[] payload = r.lenBytes();
        return new Put(ts, enc, ex, payload);
    }

    @Override public String toString() {
        return "Put{timestamp=" + (timestamp == null ? "-" : timestamp.toString())
                + ", encoding=" + encoding
                + ", extensions=" + extensions.size()
                + ", payload=" + payload.length + "B}";
    }
}

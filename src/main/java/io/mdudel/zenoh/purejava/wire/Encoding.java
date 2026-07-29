/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * The Zenoh {@code Encoding} field carried inside a Put payload.
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/core/encoding.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * ~   id: z16   |S~   varint (u16 id shifted left by 1 | S flag)
 * +---------------+
 * ~schema: <u8;z8>~   if S==1: length-prefixed byte schema (max 255 bytes)
 * +---------------+
 * </pre>
 *
 * <p>The prefix ID (14 bits effective &mdash; the codec caps at u16
 * so the composite {@code id<<1 | S} fits in a 17-bit varint) is a
 * Zenoh-defined enum published as part of the API. The wire protocol
 * itself doesn't care what the numbers mean. The optional schema
 * suffix is arbitrary bytes (typically an ASCII MIME parameter).</p>
 *
 * <p>An {@link #EMPTY} constant matches the Rust
 * {@code Encoding::empty()} value; Put encode omits the Encoding
 * field entirely (and clears the E flag) when its value equals this.</p>
 */
public final class Encoding {

    public static final Encoding EMPTY = new Encoding(0, null);

    /** Convenience prefix IDs. These match the values published in the Zenoh Java API
     *  but the wire protocol itself is agnostic. */
    public static final int ID_ZENOH_BYTES  = 0;
    public static final int ID_ZENOH_STRING = 1;
    public static final int ID_ZENOH_SERIALIZED = 2;
    public static final int ID_APPLICATION_OCTET_STREAM = 3;
    public static final int ID_TEXT_PLAIN = 4;
    public static final int ID_APPLICATION_JSON = 5;
    public static final int ID_TEXT_JSON  = 6;

    private final int    id;      // u16
    private final byte[] schema;  // null iff no schema

    private Encoding(int id, byte[] schema) {
        if (id < 0 || id > 0xFFFF) {
            throw new IllegalArgumentException("Encoding id must fit in u16: " + id);
        }
        if (schema != null && schema.length > 0xFF) {
            throw new IllegalArgumentException(
                    "Encoding schema exceeds 255 bytes: " + schema.length);
        }
        this.id     = id;
        this.schema = schema == null ? null : schema.clone();
    }

    public static Encoding of(int id) { return new Encoding(id, null); }

    public static Encoding of(int id, byte[] schema) { return new Encoding(id, schema); }

    public static Encoding of(int id, String schema) {
        return new Encoding(id, schema == null ? null : schema.getBytes(StandardCharsets.UTF_8));
    }

    public int      id()     { return id; }
    public boolean  hasSchema() { return schema != null; }
    public byte[]   schema() { return schema == null ? null : schema.clone(); }
    public String   schemaAsString() {
        return schema == null ? null : new String(schema, StandardCharsets.UTF_8);
    }

    public boolean isEmpty() { return id == 0 && schema == null; }

    /** Encode this Encoding field into the supplied buffer (id varint, then optional schema). */
    public void encode(WBuf w) {
        boolean s = (schema != null);
        long composite = ((long) id << 1) | (s ? 1 : 0);
        w.varInt(composite);
        if (s) {
            // <u8;z8> means "u8 length, then that many bytes"; enforced max 255 above.
            w.u8(schema.length);
            w.bytes(schema);
        }
    }

    /** Decode an Encoding field from the supplied buffer. */
    public static Encoding decode(RBuf r) {
        long composite = r.varInt();
        boolean s = (composite & 1L) != 0;
        long id = composite >>> 1;
        if (id < 0 || id > 0xFFFF) {
            throw new IllegalArgumentException(
                    "Encoding.decode: id out of u16 range: " + id);
        }
        byte[] schema = null;
        if (s) {
            int len = r.u8();
            schema = r.bytes(len);
        }
        return new Encoding((int) id, schema);
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Encoding other)) return false;
        return id == other.id && Arrays.equals(schema, other.schema);
    }

    @Override public int hashCode() {
        return 31 * Objects.hash(id) + Arrays.hashCode(schema);
    }

    @Override public String toString() {
        return "Encoding{id=" + id
                + (schema == null ? "" : ", schema='" + schemaAsString() + "'")
                + "}";
    }
}

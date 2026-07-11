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
 * The Zenoh network-level DECLARE message: a container that carries a
 * single {@link DeclareBody} entry (DeclareSubscriber, UndeclareSubscriber,
 * DeclareKeyExpr, etc.) plus network-message-level extensions
 * (QoS, Timestamp, NodeId).
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/network/declare.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|X|I| DECLARE |    header byte: id=0x1e | I | Z
 * +-+-+-+---------+
 * ~interest_id:z32~    if I==1: varint u32 (interest correlation)
 * +---------------+
 * ~  [decl_exts]  ~    if Z==1: extension chain (QoS 0x1 / Timestamp 0x2 / NodeId 0x3)
 * +---------------+
 * ~  declaration  ~    one DeclareBody entry (header byte + body)
 * +---------------+
 * </pre>
 *
 * <p>MVP subscriber emits the standard shape: no interest_id, no
 * network-message extensions (QoS default, no timestamp, NodeId
 * default). Extension IDs are:</p>
 * <ul>
 *   <li>{@code 0x1} = QoS (z64 flavour, non-mandatory)</li>
 *   <li>{@code 0x2} = Timestamp (zbuf flavour, non-mandatory)</li>
 *   <li>{@code 0x3} = NodeId (z64 flavour, mandatory)</li>
 * </ul>
 *
 * <p>Body dispatch matches Rust's {@code DeclareBody} enum via the
 * first byte of the {@code declaration} tail; we currently know
 * about {@link DeclareSubscriber} (0x02) and {@link UndeclareSubscriber}
 * (0x03). Other bodies are exposed as {@link BodyKind#RAW} with the
 * raw payload bytes preserved so unknown bodies round-trip.</p>
 */
public record Declare(
        Long            interestId,             // null iff I==0
        List<Extension> extensions,
        Body            body) {

    /** Network-message id byte for DECLARE. */
    public static final int ID     = 0x1e;
    /** I flag: 1 = interest_id is present. */
    public static final int FLAG_I = 0x20;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    public Declare {
        if (interestId != null && (interestId < 0 || interestId > 0xFFFFFFFFL)) {
            throw new IllegalArgumentException("interestId must fit in u32: " + interestId);
        }
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        if (body == null) throw new IllegalArgumentException("body is required");
    }

    /** Convenience: declare-a-subscriber with defaults (no interest, no ext). */
    public static Declare ofDeclareSubscriber(DeclareSubscriber ds) {
        return new Declare(null, List.of(), Body.of(ds));
    }

    /** Convenience: undeclare-a-subscriber with defaults. */
    public static Declare ofUndeclareSubscriber(UndeclareSubscriber us) {
        return new Declare(null, List.of(), Body.of(us));
    }

    /** Encode this Declare to bytes (this is the network-message payload as delivered inside a Frame). */
    public byte[] encode() {
        WBuf w = new WBuf(32);
        boolean i = (interestId != null);
        boolean z = !extensions.isEmpty();
        int header = ID | (i ? FLAG_I : 0) | (z ? FLAG_Z : 0);
        w.u8(header);
        if (i) w.varInt(interestId);
        if (z) Extension.writeAll(extensions, w);
        body.encode(w);
        return w.toByteArray();
    }

    /** Parse a Declare from bytes. */
    public static Declare decode(byte[] data) {
        RBuf r = new RBuf(data);
        int header = r.u8();
        int id = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "Declare.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean hasI = (header & FLAG_I) != 0;
        boolean hasZ = (header & FLAG_Z) != 0;
        Long interestId = hasI ? r.varInt() : null;
        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        Body body = Body.decode(r);
        return new Declare(interestId, exts, body);
    }

    /**
     * DeclareBody union. Known bodies get a typed representation
     * ({@link BodyKind#DECLARE_SUBSCRIBER} / {@link BodyKind#UNDECLARE_SUBSCRIBER});
     * unknown bodies land as {@link BodyKind#RAW} preserving the raw
     * bytes verbatim so a future spec revision does not corrupt the
     * enclosing Declare on round-trip.
     */
    public static final class Body {

        public enum BodyKind { DECLARE_SUBSCRIBER, UNDECLARE_SUBSCRIBER, RAW }

        private final BodyKind             kind;
        private final DeclareSubscriber    declareSubscriber;
        private final UndeclareSubscriber  undeclareSubscriber;
        private final byte[]               rawBytes;    // header byte + body, for RAW

        private Body(BodyKind kind,
                     DeclareSubscriber ds,
                     UndeclareSubscriber us,
                     byte[] raw) {
            this.kind = kind;
            this.declareSubscriber = ds;
            this.undeclareSubscriber = us;
            this.rawBytes = raw == null ? null : raw.clone();
        }

        public static Body of(DeclareSubscriber   ds) { return new Body(BodyKind.DECLARE_SUBSCRIBER,   ds, null, null); }
        public static Body of(UndeclareSubscriber us) { return new Body(BodyKind.UNDECLARE_SUBSCRIBER, null, us, null); }
        /** Build a RAW body from the FULL sub-message bytes (header byte first). */
        public static Body ofRaw(byte[] fullBodyBytes) {
            if (fullBodyBytes == null || fullBodyBytes.length == 0) {
                throw new IllegalArgumentException("raw body must be non-empty");
            }
            return new Body(BodyKind.RAW, null, null, fullBodyBytes);
        }

        public BodyKind             kind()                { return kind; }
        public DeclareSubscriber    asDeclareSubscriber() { return declareSubscriber; }
        public UndeclareSubscriber  asUndeclareSubscriber(){ return undeclareSubscriber; }
        public byte[]               rawBytes()            { return rawBytes == null ? null : rawBytes.clone(); }

        void encode(WBuf w) {
            switch (kind) {
                case DECLARE_SUBSCRIBER   -> declareSubscriber.encode(w);
                case UNDECLARE_SUBSCRIBER -> undeclareSubscriber.encode(w);
                case RAW                  -> w.bytes(rawBytes);
            }
        }

        static Body decode(RBuf r) {
            int header = r.u8();
            int subId = header & 0x1F;
            return switch (subId) {
                case DeclareSubscriber.ID  -> of(DeclareSubscriber.decode(header, r));
                case UndeclareSubscriber.ID -> of(UndeclareSubscriber.decode(header, r));
                default -> {
                    // Consume the rest of the buffer as an opaque RAW body.
                    // We include the header byte so a Body.ofRaw(bytes)-then-encode
                    // round trips byte-identical.
                    int remaining = r.remaining();
                    byte[] rest = r.bytes(remaining);
                    byte[] full = new byte[1 + rest.length];
                    full[0] = (byte) header;
                    System.arraycopy(rest, 0, full, 1, rest.length);
                    yield ofRaw(full);
                }
            };
        }

        @Override public String toString() {
            return switch (kind) {
                case DECLARE_SUBSCRIBER   -> "Body{" + declareSubscriber   + "}";
                case UNDECLARE_SUBSCRIBER -> "Body{" + undeclareSubscriber + "}";
                case RAW                  -> "Body{RAW(" + rawBytes.length + "B)}";
            };
        }
    }

    @Override public String toString() {
        return "Declare{"
                + (interestId == null ? "" : "interestId=" + interestId + ", ")
                + "extensions=" + extensions.size()
                + ", body=" + body + "}";
    }
}

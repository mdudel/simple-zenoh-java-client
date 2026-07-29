/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Zenoh extension (TLV chain element).
 *
 * <p>Wire format of the extension header byte:</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|ENC|M|  ID   |
 * +-+---+-+-------+
 * </pre>
 *
 * <ul>
 *   <li>bits 0-3 (ID): 4-bit extension identifier.</li>
 *   <li>bit 4    (M):  mandatory flag - if set and the receiver doesn't
 *       recognise the extension, it MUST refuse the enclosing message.</li>
 *   <li>bits 5-6 (ENC): body encoding: 00 Unit, 01 Z64, 10 ZBuf,
 *       11 reserved.</li>
 *   <li>bit 7    (Z):  more extensions follow after this one's body.</li>
 * </ul>
 *
 * <p>The body encoding determines what comes after the header byte:</p>
 * <ul>
 *   <li>Unit: nothing.</li>
 *   <li>Z64:  one varint (up to 64-bit unsigned).</li>
 *   <li>ZBuf: one varint length, then that many opaque bytes.</li>
 * </ul>
 *
 * <p>Callers that don't recognise an extension can (and should) still parse
 * its length correctly using this class, which lets a receiver skip past
 * unknown extensions and still find the next one - a core requirement of
 * the Zenoh forward-compatibility story.</p>
 */
public final class Extension {

    // Bit masks matching the Rust `iext` module.
    public static final int FLAG_Z    = 0x80;
    public static final int ENC_MASK  = 0x60;
    public static final int ENC_UNIT  = 0x00;
    public static final int ENC_Z64   = 0x20;
    public static final int ENC_ZBUF  = 0x40;
    public static final int ENC_RSVD  = 0x60;
    public static final int FLAG_M    = 0x10;
    public static final int ID_MASK   = 0x0F;

    /** Encoding tag: the shape of the extension body. */
    public enum Encoding {
        UNIT(ENC_UNIT),
        Z64 (ENC_Z64),
        ZBUF(ENC_ZBUF);

        public final int bits;
        Encoding(int bits) { this.bits = bits; }

        static Encoding fromBits(int b) {
            return switch (b & ENC_MASK) {
                case ENC_UNIT -> UNIT;
                case ENC_Z64  -> Z64;
                case ENC_ZBUF -> ZBUF;
                default       -> throw new IllegalArgumentException(
                        "Extension: reserved encoding 0b11 in header 0x"
                                + Integer.toHexString(b));
            };
        }
    }

    private final int      id;
    private final boolean  mandatory;
    private final Encoding encoding;
    private final long     u64;    // used if encoding == Z64
    private final byte[]   bytes;  // used if encoding == ZBUF (empty otherwise)

    private Extension(int id, boolean mandatory, Encoding encoding, long u64, byte[] bytes) {
        if ((id & ~ID_MASK) != 0) {
            throw new IllegalArgumentException(
                    "Extension id must fit in 4 bits (0..15): " + id);
        }
        this.id        = id;
        this.mandatory = mandatory;
        this.encoding  = encoding;
        this.u64       = u64;
        this.bytes     = bytes;
    }

    public static Extension unit(int id, boolean mandatory) {
        return new Extension(id, mandatory, Encoding.UNIT, 0L, new byte[0]);
    }

    public static Extension z64(int id, boolean mandatory, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "Extension.z64: value must be unsigned (>= 0), got " + value);
        }
        return new Extension(id, mandatory, Encoding.Z64, value, new byte[0]);
    }

    public static Extension zbuf(int id, boolean mandatory, byte[] payload) {
        return new Extension(id, mandatory, Encoding.ZBUF, 0L,
                Objects.requireNonNull(payload, "payload").clone());
    }

    public int      id()          { return id; }
    public boolean  mandatory()   { return mandatory; }
    public Encoding encoding()    { return encoding; }
    public long     asZ64()       {
        if (encoding != Encoding.Z64) throw new IllegalStateException("not Z64: " + encoding);
        return u64;
    }
    public byte[]   asZBuf()      {
        if (encoding != Encoding.ZBUF) throw new IllegalStateException("not ZBUF: " + encoding);
        return bytes.clone();
    }

    // --- codec -----------------------------------------------------------

    /**
     * Write a list of extensions to {@code out}. The Z (more-follows) bit is
     * set automatically on every extension except the last.
     *
     * <p>Writing zero extensions writes nothing.</p>
     */
    public static void writeAll(List<Extension> exts, WBuf out) {
        for (int i = 0; i < exts.size(); i++) {
            boolean last = (i == exts.size() - 1);
            exts.get(i).writeOne(out, !last);
        }
    }

    private void writeOne(WBuf out, boolean moreFollows) {
        int header = id | (mandatory ? FLAG_M : 0) | encoding.bits | (moreFollows ? FLAG_Z : 0);
        out.u8(header);
        switch (encoding) {
            case UNIT -> { /* no body */ }
            case Z64  -> out.varInt(u64);
            case ZBUF -> out.lenBytes(bytes);
        }
    }

    /**
     * Read an extension chain until the Z flag on some header is clear.
     * Returns an empty list if the caller opted not to enter the chain
     * (that policy is decided by the outer message's Z-flag).
     *
     * <p>Encountering the reserved ENC=0b11 encoding throws.</p>
     */
    public static List<Extension> readAll(RBuf in) {
        List<Extension> out = new ArrayList<>();
        boolean more = true;
        while (more) {
            int hdr = in.u8();
            more     = (hdr & FLAG_Z) != 0;
            int  eid = hdr & ID_MASK;
            boolean m = (hdr & FLAG_M) != 0;
            Encoding enc = Encoding.fromBits(hdr);
            switch (enc) {
                case UNIT -> out.add(new Extension(eid, m, enc, 0L, new byte[0]));
                case Z64  -> out.add(new Extension(eid, m, enc, in.varInt(), new byte[0]));
                case ZBUF -> out.add(new Extension(eid, m, enc, 0L, in.lenBytes()));
            }
        }
        return out;
    }

    // --- equality --------------------------------------------------------

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Extension other)) return false;
        if (id != other.id || mandatory != other.mandatory || encoding != other.encoding) return false;
        return switch (encoding) {
            case UNIT -> true;
            case Z64  -> u64 == other.u64;
            case ZBUF -> java.util.Arrays.equals(bytes, other.bytes);
        };
    }

    @Override public int hashCode() {
        int h = id * 31 + (mandatory ? 1 : 0);
        h = h * 31 + encoding.hashCode();
        h = h * 31 + Long.hashCode(u64);
        h = h * 31 + java.util.Arrays.hashCode(bytes);
        return h;
    }

    @Override public String toString() {
        return "Extension{id=" + id
                + (mandatory ? " M" : "")
                + " enc=" + encoding
                + switch (encoding) {
                    case UNIT -> "";
                    case Z64  -> " z64=" + u64;
                    case ZBUF -> " zbuf(" + bytes.length + "B)";
                }
                + "}";
    }
}

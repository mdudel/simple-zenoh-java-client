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
 * The Zenoh transport-level CLOSE message.
 *
 * <p>Sent either in response to an unacceptable INIT/OPEN, or at any
 * time thereafter to arbitrarily terminate the link (S=0) or the
 * whole session (S=1).</p>
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/transport/close.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|X|S|  CLOSE  |    header byte: id=0x03 | S | Z
 * +-+-+-+---------+
 * |     reason    |    single u8 reason code
 * +---------------+
 * ~  [CloseExts]  ~    if Z=1: extension chain
 * +---------------+
 * </pre>
 *
 * <ul>
 *   <li>S flag: 1 = close the whole session across all links; 0 = close
 *       only this link, other links to the same peer stay up.</li>
 *   <li>Z flag: 1 = extension chain follows.</li>
 * </ul>
 */
public record Close(
        int             reason,
        boolean         session,
        List<Extension> extensions) {

    // ---- header identity + flag bits ----

    /** Transport-message id byte for CLOSE. */
    public static final int ID     = 0x03;
    /** S flag: 1 = close the whole session, 0 = close only this link. */
    public static final int FLAG_S = 0x20;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    // ---- reason codes (from zenoh-protocol close::reason) --------------

    public static final int REASON_GENERIC             = 0x00;
    public static final int REASON_UNSUPPORTED         = 0x01;
    public static final int REASON_INVALID             = 0x02;
    public static final int REASON_MAX_SESSIONS        = 0x03;
    public static final int REASON_MAX_LINKS           = 0x04;
    public static final int REASON_EXPIRED             = 0x05;
    public static final int REASON_UNRESPONSIVE        = 0x06;
    public static final int REASON_CONNECTION_TO_SELF  = 0x07;

    /** Best-effort name for a reason code; unknown codes return "UNKNOWN(0xNN)". */
    public static String reasonName(int reason) {
        return switch (reason) {
            case REASON_GENERIC            -> "GENERIC";
            case REASON_UNSUPPORTED        -> "UNSUPPORTED";
            case REASON_INVALID            -> "INVALID";
            case REASON_MAX_SESSIONS       -> "MAX_SESSIONS";
            case REASON_MAX_LINKS          -> "MAX_LINKS";
            case REASON_EXPIRED            -> "EXPIRED";
            case REASON_UNRESPONSIVE       -> "UNRESPONSIVE";
            case REASON_CONNECTION_TO_SELF -> "CONNECTION_TO_SELF";
            default -> "UNKNOWN(0x" + Integer.toHexString(reason) + ")";
        };
    }

    public Close {
        if (reason < 0 || reason > 0xFF) {
            throw new IllegalArgumentException("reason must fit in u8: " + reason);
        }
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** Convenience for the common outbound case: session-scoped, generic reason, no extensions. */
    public static Close sessionGeneric() {
        return new Close(REASON_GENERIC, true, List.of());
    }

    /** Convenience for a link-scoped close with a specific reason and no extensions. */
    public static Close linkWithReason(int reason) {
        return new Close(reason, false, List.of());
    }

    /** Encode this Close to bytes. */
    public byte[] encode() {
        WBuf w = new WBuf(4);
        boolean z = !extensions.isEmpty();
        int header = ID | (session ? FLAG_S : 0) | (z ? FLAG_Z : 0);
        w.u8(header);
        w.u8(reason);
        if (z) Extension.writeAll(extensions, w);
        return w.toByteArray();
    }

    /** Parse a Close from bytes. */
    public static Close decode(byte[] data) {
        RBuf r = new RBuf(data);
        int header = r.u8();
        int id = header & 0x1F;
        if (id != ID) {
            throw new IllegalArgumentException(
                    "Close.decode: expected id 0x" + Integer.toHexString(ID)
                            + " got 0x" + Integer.toHexString(id));
        }
        boolean session = (header & FLAG_S) != 0;
        boolean hasZ    = (header & FLAG_Z) != 0;
        int reason = r.u8();
        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        return new Close(reason, session, exts);
    }

    /** Human-readable dump for logs. */
    @Override public String toString() {
        return "Close{reason=" + reasonName(reason)
                + " (0x" + Integer.toHexString(reason) + ")"
                + ", scope=" + (session ? "SESSION" : "LINK")
                + ", extensions=" + extensions.size() + "}";
    }
}

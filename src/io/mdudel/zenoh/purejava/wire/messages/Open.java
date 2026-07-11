/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.wire.messages;

import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.RBuf;
import io.mdudel.zenoh.purejava.wire.WBuf;

import java.util.Collections;
import java.util.List;

/**
 * The Zenoh transport-level OPEN message.
 *
 * <p>An OPEN is either an {@link OpenSyn} (A flag = 0, sent by the
 * initiator after a successful INIT exchange) or an {@link OpenAck}
 * (A flag = 1, sent by the responder to complete the handshake).</p>
 *
 * <p>Wire layout (from
 * {@code commons/zenoh-protocol/src/transport/open.rs}):</p>
 * <pre>
 *  7 6 5 4 3 2 1 0
 * +-+-+-+-+-+-+-+-+
 * |Z|T|A|   OPEN  |    header byte: id=0x02 | A | T | Z
 * +-+-+-+---------+
 * %     lease     %    varint: lease period of the sender
 * +---------------+
 * %  initial_sn   %    varint: initial sequence number proposed
 * +---------------+
 * ~    &lt;u8;z16&gt;   ~    if A=0 (OpenSyn): Cookie echoed from InitAck
 * +---------------+
 * ~   [OpenExts]  ~    if Z=1: extension chain
 * +---------------+
 * </pre>
 *
 * <ul>
 *   <li>A flag: 0 = OpenSyn (initiator), 1 = OpenAck (responder).</li>
 *   <li>T flag: 0 = lease is in milliseconds, 1 = lease is in seconds.
 *       The wire always carries a varint; T selects the unit. We
 *       always encode using milliseconds (T = 0) on the outbound
 *       OpenSyn - simple, avoids rounding, and the server tolerates
 *       either.</li>
 *   <li>Z flag: 1 = extension chain follows.</li>
 * </ul>
 *
 * <p>OpenSyn MUST echo the Cookie the server sent in InitAck. OpenAck
 * has no Cookie.</p>
 */
public final class Open {

    // ---- header identity + flag bits ----

    /** Transport-message id byte for OPEN. */
    public static final int ID     = 0x02;
    /** A flag: 0 = OpenSyn, 1 = OpenAck. */
    public static final int FLAG_A = 0x20;
    /** T flag: 0 = lease in ms, 1 = lease in seconds. */
    public static final int FLAG_T = 0x40;
    /** Z flag: 1 = extension chain follows. */
    public static final int FLAG_Z = 0x80;

    private Open() {}

    // ---- OpenSyn (client -> server) ------------------------------------

    /**
     * Immutable value carrier for a client-side OpenSyn.
     *
     * <p>{@link #leaseMillis} is unsigned; caller must ensure it is
     * non-negative. {@link #initialSn} is the varint-encoded starting
     * sequence number for outbound frames on this link and must be
     * compatible with the SN resolution negotiated in the preceding
     * InitSyn/InitAck exchange.</p>
     */
    public record OpenSyn(
            long            leaseMillis,
            long            initialSn,
            byte[]          cookie,
            List<Extension> extensions) {

        public OpenSyn {
            if (leaseMillis < 0) {
                throw new IllegalArgumentException("leaseMillis must be >= 0: " + leaseMillis);
            }
            if (initialSn < 0) {
                throw new IllegalArgumentException("initialSn must be >= 0: " + initialSn);
            }
            if (cookie == null) {
                throw new IllegalArgumentException("cookie is null (must be echoed from InitAck)");
            }
            cookie = cookie.clone();
            extensions = extensions == null ? Collections.emptyList() : List.copyOf(extensions);
        }

        /** Encode this OpenSyn to bytes. Uses T=0 (milliseconds). */
        public byte[] encode() {
            WBuf w = new WBuf(16 + cookie.length);
            boolean z = !extensions.isEmpty();
            int header = ID | (z ? FLAG_Z : 0); // A=0 (SYN), T=0 (ms)
            w.u8(header);
            w.varInt(leaseMillis);
            w.varInt(initialSn);
            w.lenBytes(cookie);
            if (z) Extension.writeAll(extensions, w);
            return w.toByteArray();
        }
    }

    // ---- OpenAck (server -> client) ------------------------------------

    /** Immutable value carrier for the server's OpenAck reply. */
    public record OpenAck(
            /** Server's lease in the unit the T flag indicated. See {@link #leaseInSeconds}. */
            long            lease,
            /** True iff the T flag was set (lease was in seconds), false = milliseconds. */
            boolean         leaseInSeconds,
            long            initialSn,
            List<Extension> extensions) {

        public OpenAck {
            extensions = extensions == null ? List.of() : List.copyOf(extensions);
        }

        /** Server's lease normalised to milliseconds. */
        public long leaseMillis() {
            return leaseInSeconds ? lease * 1_000L : lease;
        }

        /**
         * Parse an OpenAck from bytes. Throws if the header id is wrong
         * or the A flag is clear (i.e. looks like an OpenSyn instead).
         */
        public static OpenAck decode(byte[] data) {
            RBuf r = new RBuf(data);
            int header = r.u8();
            int id = header & 0x1F;
            if (id != ID) {
                throw new IllegalArgumentException(
                        "OpenAck.decode: expected id 0x" + Integer.toHexString(ID)
                                + " got 0x" + Integer.toHexString(id));
            }
            if ((header & FLAG_A) == 0) {
                throw new IllegalArgumentException(
                        "OpenAck.decode: A flag not set - looks like an OpenSyn, not an OpenAck");
            }
            boolean tSeconds = (header & FLAG_T) != 0;
            boolean hasZ     = (header & FLAG_Z) != 0;

            long lease     = r.varInt();
            long initialSn = r.varInt();
            List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();

            return new OpenAck(lease, tSeconds, initialSn, exts);
        }
    }
}

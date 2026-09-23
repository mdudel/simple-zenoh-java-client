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
 * Factory helpers for the three PUSH-scope network extensions currently
 * modelled by this client:
 *
 * <ul>
 *   <li>{@link #qos(Qos)} - extension id {@code 0x01}, Z64, non-mandatory.
 *       Carries the packed {@link Qos} byte.</li>
 *   <li>{@link #timestamp(Timestamp)} - extension id {@code 0x02}, ZBuf,
 *       non-mandatory. Carries the serialised {@link Timestamp}.</li>
 *   <li>{@link #nodeId(long)} - extension id {@code 0x03}, Z64,
 *       <b>mandatory</b> (M=1 on the wire, matching Rust
 *       {@code zextz64!(0x3, true)}). Carries a 32-bit routing id in a
 *       varint.</li>
 * </ul>
 *
 * <p>These ids come straight from Rust
 * {@code commons/zenoh-protocol/src/network/push.rs::ext}:
 * {@code QoS = zextz64!(0x1, false)}, {@code Timestamp = zextzbuf!(0x2, false)},
 * {@code NodeId = zextz64!(0x3, true)}.</p>
 *
 * <p>Callers that use {@link #buildPushExtensions(Qos, Timestamp, long)}
 * get an extension list that is empty (i.e. no extensions to emit) when
 * every argument is at its default. This lets a publisher emit
 * byte-identical wire output to the pre-QoS version of this client when
 * the caller does not opt in.</p>
 */
public final class PushExts {

    /** Extension id for the QoS byte on PUSH. */
    public static final int EXT_ID_QOS       = 0x01;
    /** Extension id for the Timestamp on PUSH. */
    public static final int EXT_ID_TIMESTAMP = 0x02;
    /** Extension id for the NodeId (routing origin) on PUSH. */
    public static final int EXT_ID_NODE_ID   = 0x03;

    private PushExts() {}

    /**
     * QoS extension for PUSH. Non-mandatory; Z64-encoded packed byte.
     * The Rust codec omits this extension when the QoS equals its
     * default; this helper does not decide that -- callers should
     * gate on {@link Qos#isDefault()} themselves. See
     * {@link #buildPushExtensions(Qos, Timestamp, long)}.
     */
    public static Extension qos(Qos qos) {
        Objects.requireNonNull(qos, "qos");
        return Extension.z64(EXT_ID_QOS, /*mandatory=*/false, qos.toWireByte() & 0xFFL);
    }

    /**
     * Timestamp extension for PUSH. Non-mandatory; ZBuf-encoded serialised
     * {@link Timestamp} (NTP64 varint + length-prefixed ZID bytes).
     */
    public static Extension timestamp(Timestamp ts) {
        Objects.requireNonNull(ts, "ts");
        WBuf w = new WBuf(24);
        ts.encode(w);
        return Extension.zbuf(EXT_ID_TIMESTAMP, /*mandatory=*/false, w.toByteArray());
    }

    /**
     * NodeId extension for PUSH. <b>Mandatory</b> per Rust source
     * ({@code zextz64!(0x3, true)}): receivers that don't recognise it
     * MUST refuse the enclosing message. Z64-encoded 32-bit routing id.
     *
     * @param nodeId 32-bit unsigned routing id (0 skipped by
     *               {@link #buildPushExtensions})
     * @throws IllegalArgumentException if the id doesn't fit in a u32
     */
    public static Extension nodeId(long nodeId) {
        if (nodeId < 0 || nodeId > 0xFFFFFFFFL) {
            throw new IllegalArgumentException(
                    "NodeId must fit in u32 (0..4294967295): " + nodeId);
        }
        return Extension.z64(EXT_ID_NODE_ID, /*mandatory=*/true, nodeId);
    }

    /**
     * Build the extension list for a PUSH from the three high-level
     * knobs a publisher can currently set. Extensions are appended in
     * ascending id order (0x01, 0x02, 0x03) per Zenoh convention.
     *
     * <p>Skip rules (matching the Rust codec):</p>
     * <ul>
     *   <li>QoS: omitted iff {@code qos.equals(Qos.DEFAULT)} or {@code qos == null}.</li>
     *   <li>Timestamp: omitted iff {@code ts == null}.</li>
     *   <li>NodeId: omitted iff {@code nodeId == 0}.</li>
     * </ul>
     *
     * <p>If all three skip, returns {@link java.util.Collections#emptyList()}
     * so the caller's Z-flag stays clear and the wire output is
     * byte-identical to the pre-QoS client.</p>
     */
    public static List<Extension> buildPushExtensions(Qos qos, Timestamp ts, long nodeId) {
        boolean haveQos = (qos != null && !qos.isDefault());
        boolean haveTs  = (ts  != null);
        boolean haveNid = (nodeId != 0);
        if (!haveQos && !haveTs && !haveNid) return List.of();
        List<Extension> out = new ArrayList<>(3);
        if (haveQos) out.add(qos(qos));
        if (haveTs)  out.add(timestamp(ts));
        if (haveNid) out.add(nodeId(nodeId));
        return out;
    }

    /**
     * Extract a {@link Qos} from an extension list produced by
     * {@link Extension#readAll}, or {@link Qos#DEFAULT} if the list
     * contains no QoS extension.
     */
    public static Qos extractQos(List<Extension> exts) {
        if (exts == null) return Qos.DEFAULT;
        for (Extension e : exts) {
            if (e.id() == EXT_ID_QOS && e.encoding() == Extension.Encoding.Z64) {
                return Qos.fromWireByte((int) (e.asZ64() & 0xFFL));
            }
        }
        return Qos.DEFAULT;
    }

    /**
     * Extract a {@link Timestamp} from an extension list, or {@code null}
     * if none is present.
     */
    public static Timestamp extractTimestamp(List<Extension> exts) {
        if (exts == null) return null;
        for (Extension e : exts) {
            if (e.id() == EXT_ID_TIMESTAMP && e.encoding() == Extension.Encoding.ZBUF) {
                RBuf r = new RBuf(e.asZBuf());
                return Timestamp.decode(r);
            }
        }
        return null;
    }

    /**
     * Extract a NodeId from an extension list, or {@code 0} if none is
     * present (matching the Rust {@code NodeIdType::DEFAULT} where
     * {@code node_id == 0}).
     */
    public static long extractNodeId(List<Extension> exts) {
        if (exts == null) return 0L;
        for (Extension e : exts) {
            if (e.id() == EXT_ID_NODE_ID && e.encoding() == Extension.Encoding.Z64) {
                return e.asZ64();
            }
        }
        return 0L;
    }
}

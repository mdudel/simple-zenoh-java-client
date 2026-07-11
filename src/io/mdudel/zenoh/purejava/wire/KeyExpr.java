/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of a clean-room pure-Java implementation of the Eclipse
 * Zenoh 1.x wire protocol. It is not a copy of any Zenoh source code.
 */
package io.mdudel.zenoh.purejava.wire;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Zenoh key expression: a string with wildcard semantics matching the
 * spec at <a href="https://zenoh.io/docs/manual/abstractions/#key-expression">
 * zenoh.io/docs/manual/abstractions</a>.
 *
 * <h2>Wildcard syntax (chunk-based, {@code /} separators)</h2>
 * <ul>
 *   <li><b>{@code *}</b> &mdash; a whole chunk. E.g. {@code a/*&#47;c}
 *       matches {@code a/anything/c} but not {@code a/x/y/c}.</li>
 *   <li><b>{@code **}</b> &mdash; zero or more chunks. E.g.
 *       {@code a/**&#47;c} matches {@code a/c}, {@code a/x/c},
 *       {@code a/x/y/c}, etc.</li>
 *   <li><b>{@code $*}</b> &mdash; sub-chunk wildcard, i.e.
 *       {@code [^/]*} inside a single chunk. E.g.
 *       {@code sensor$*&#47;temp} matches {@code sensor1/temp} and
 *       {@code sensorABC/temp}. Chunk-only ({@code $*} as a whole
 *       chunk) is non-canonical &mdash; use {@code *} instead.</li>
 * </ul>
 *
 * <h2>Operations</h2>
 * <ul>
 *   <li>{@link #matches(String)} &mdash; does this KE match a concrete
 *       (wildcard-free) key? Used subscriber-side after receiving a
 *       PUT whose key is the resolved concrete key.</li>
 *   <li>{@link #intersects(KeyExpr)} &mdash; is there ANY concrete key
 *       that would match both this and the argument? Used router-side
 *       for subscription routing and client-side for
 *       "does my declared filter apply to this incoming publisher's
 *       key expression?". Symmetric.</li>
 * </ul>
 *
 * <p>Wildcard intersection is a faithful port of the reference
 * {@code ClassicIntersector} in {@code commons/zenoh-keyexpr/src/key_expr/intersect/classical.rs}.
 * The {@code STAR_DSL=true} variant (which recognises {@code $*}) is the one
 * routers use.</p>
 *
 * <p>Verbatim / admin-space chunks (leading {@code @}) are NOT specially
 * handled in this v1 &mdash; admin-space subscribers are a follow-up. The
 * classical intersector would refuse to match verbatim chunks against
 * wildcards for safety; we tolerate them (match on equality) since the
 * common case is subscriber-side wildcards against non-verbatim keys.</p>
 */
public final class KeyExpr {

    private final String value;

    public KeyExpr(String value) {
        this.value = Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("keyexpr must be non-empty");
        }
    }

    public String value() { return value; }

    /** Sugar for {@code new KeyExpr(s)}. */
    public static KeyExpr of(String s) { return new KeyExpr(s); }

    /** True iff this key expression contains no wildcards (no {@code *} or {@code $}). */
    public boolean isConcrete() {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*' || c == '$') return false;
        }
        return true;
    }

    /**
     * Does this key expression match the given concrete (wildcard-free) key?
     * The argument MUST be a concrete key; passing another wildcard KE here
     * is a bug &mdash; use {@link #intersects(KeyExpr)} for wildcard-vs-wildcard.
     *
     * <p>Behaviour: equivalent to {@code intersects(new KeyExpr(concrete))}
     * but slightly cheaper on the fast path since we know one side has no
     * wildcards.</p>
     */
    public boolean matches(String concreteKey) {
        Objects.requireNonNull(concreteKey, "concreteKey");
        // The intersection algorithm is already correct for the concrete-vs-wildcard
        // case; there's no perf reason to special-case here beyond the string equality
        // fast path.
        if (value.equals(concreteKey)) return true;
        return intersect(value.getBytes(StandardCharsets.US_ASCII),
                         concreteKey.getBytes(StandardCharsets.US_ASCII));
    }

    /** True iff there exists any concrete key that matches BOTH this KE and {@code other}. Symmetric. */
    public boolean intersects(KeyExpr other) {
        Objects.requireNonNull(other, "other");
        if (this.value.equals(other.value)) return true;
        return intersect(value.getBytes(StandardCharsets.US_ASCII),
                         other.value.getBytes(StandardCharsets.US_ASCII));
    }

    @Override public String toString() { return value; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KeyExpr other)) return false;
        return value.equals(other.value);
    }

    @Override public int hashCode() { return value.hashCode(); }

    /**
     * Prepend {@code org} to {@code keyExpr} with slash normalisation.
     *
     * <p>Behaviour must remain byte-identical to
     * {@code io.mdudel.zenoh.ZenohClient.resolveKey(String, String)}
     * in the JNI-backed sibling module &mdash; the two implementations are
     * meant to be drop-in swappable, which requires the effective wire
     * key be the same for the same inputs. If the JNI version's
     * behaviour changes, this must change with it.</p>
     *
     * <p>Examples:</p>
     * <pre>
     *   resolveKey(null,   "tracks") -> "tracks"
     *   resolveKey("",     "tracks") -> "tracks"
     *   resolveKey("acme", "tracks") -> "acme/tracks"
     *   resolveKey("acme/","tracks") -> "acme/tracks"
     *   resolveKey("acme","/tracks") -> "acme/tracks"
     *   resolveKey("acme/","/tracks")-> "acme/tracks"
     *   resolveKey("acme",  "")      -> "acme"
     * </pre>
     */
    public static String resolveKey(String org, String keyExpr) {
        String k = keyExpr == null ? "" : keyExpr;
        if (org == null || org.isEmpty()) return k;
        String o = org;
        while (o.endsWith("/")) o = o.substring(0, o.length() - 1);
        while (k.startsWith("/")) k = k.substring(1);
        if (o.isEmpty()) return k;
        if (k.isEmpty()) return o;
        return o + "/" + k;
    }

    // ---- intersection algorithm ---------------------------------------
    //
    // Ported faithfully from the ClassicIntersector in
    // commons/zenoh-keyexpr/src/key_expr/intersect/classical.rs (Rust reference).
    // The Rust version is generic over STAR_DSL {true, false}; we always use
    // STAR_DSL=true because that's what Zenoh routers use.
    //
    // Byte-array level, US-ASCII only. Key expressions are UTF-8 in the spec
    // but the wildcard chars (`*`, `$`, `/`) are ASCII and the reference
    // algorithm operates on raw bytes; non-ASCII bytes only participate in
    // equality comparisons and equality is byte-identical to UTF-8 equality.

    private static final byte SLASH = '/';
    private static final byte STAR  = '*';
    private static final byte DOLLAR= '$';

    /**
     * Split {@code s} at the first {@code /}; return {@code {chunk, rest}}
     * where {@code rest} does NOT include the delimiter. If no {@code /}
     * is present, {@code rest} is empty. Mirrors the reference
     * {@code next(s)} in classical.rs.
     */
    private static byte[][] nextChunk(byte[] s) {
        for (int i = 0; i < s.length; i++) {
            if (s[i] == SLASH) {
                byte[] chunk = new byte[i];
                System.arraycopy(s, 0, chunk, 0, i);
                byte[] rest  = new byte[s.length - i - 1];
                System.arraycopy(s, i + 1, rest, 0, rest.length);
                return new byte[][] { chunk, rest };
            }
        }
        return new byte[][] { s, new byte[0] };
    }

    /** True iff {@code a} and {@code b} are byte-identical arrays. */
    private static boolean eq(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    /** True iff {@code a} equals a single-byte array containing {@code b}. */
    private static boolean isByte(byte[] a, byte b) {
        return a.length == 1 && a[0] == b;
    }

    /** True iff {@code a} equals the two-byte "**". */
    private static boolean isDoubleStar(byte[] a) {
        return a.length == 2 && a[0] == STAR && a[1] == STAR;
    }

    /** True iff {@code a} equals the two-byte "$*". */
    private static boolean isDollarStar(byte[] a) {
        return a.length == 2 && a[0] == DOLLAR && a[1] == STAR;
    }

    /**
     * Sub-chunk (aka {@code $*}) intersection between two SINGLE chunks.
     * Faithful port of {@code star_dsl_intersect} in classical.rs.
     */
    private static boolean starDslIntersect(byte[] it1, byte[] it2) {
        while (it1.length != 0 && it2.length != 0) {
            byte c1 = it1[0], c2 = it2[0];
            byte[] adv1 = tail(it1), adv2 = tail(it2);
            if (c1 == DOLLAR && c2 == DOLLAR) {
                if (adv1.length == 1 || adv2.length == 1) return true;
                if (starDslIntersect(tail(adv1), it2)) return true;
                return starDslIntersect(it1, tail(adv2));
            }
            if (c1 == DOLLAR) {
                if (adv1.length == 1) return true;
                if (starDslIntersect(tail(adv1), it2)) return true;
                it2 = adv2;
            } else if (c2 == DOLLAR) {
                if (adv2.length == 1) return true;
                if (starDslIntersect(it1, tail(adv2))) return true;
                it1 = adv1;
            } else if (c1 == c2) {
                it1 = adv1;
                it2 = adv2;
            } else {
                return false;
            }
        }
        // Empty on both sides means match; a trailing "$*" (2 bytes: '$' '*') on
        // one side matches empty on the other.
        return (it1.length == 0 && it2.length == 0)
                || isDollarStar(it1) || isDollarStar(it2);
    }

    /** Slice off the first byte. */
    private static byte[] tail(byte[] a) {
        byte[] r = new byte[a.length - 1];
        System.arraycopy(a, 1, r, 0, r.length);
        return r;
    }

    /** Single-chunk intersection with {@code $*} DSL enabled. */
    private static boolean chunkIntersect(byte[] c1, byte[] c2) {
        if (eq(c1, c2))     return true;
        if (isByte(c1, STAR) || isByte(c2, STAR)) return true;
        return starDslIntersect(c1, c2);
    }

    /**
     * Multi-chunk intersection with {@code **} handling. Faithful port of
     * {@code it_intersect<STAR_DSL=true>} in classical.rs. Verbatim (admin-space)
     * chunk handling is simplified: no chunks are treated as verbatim in v1.
     */
    private static boolean intersect(byte[] it1, byte[] it2) {
        while (it1.length != 0 && it2.length != 0) {
            byte[][] p1 = nextChunk(it1);
            byte[][] p2 = nextChunk(it2);
            byte[] cur1 = p1[0], adv1 = p1[1];
            byte[] cur2 = p2[0], adv2 = p2[1];
            if (isDoubleStar(cur1)) {
                if (adv1.length == 0) return true;
                // "**" swallows zero or more chunks; try both:
                //   consume adv2 (swallow one chunk from the OTHER side)
                //   OR advance past ** (leave right alone)
                return intersect(it1, adv2) || intersect(adv1, it2);
            }
            if (isDoubleStar(cur2)) {
                if (adv2.length == 0) return true;
                return intersect(adv1, it2) || intersect(it1, adv2);
            }
            if (chunkIntersect(cur1, cur2)) {
                it1 = adv1;
                it2 = adv2;
            } else {
                return false;
            }
        }
        // Both must be either empty or a lone "**" (which matches zero chunks).
        return (it1.length == 0 || isDoubleStar(it1))
                && (it2.length == 0 || isDoubleStar(it2));
    }
}

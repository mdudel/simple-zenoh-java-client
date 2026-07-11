/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PEM parser for X.509 certificates and PKCS#8 private keys.
 *
 * <p>Deliberately narrow: this class handles the PEM formats that
 * {@code openssl req} + {@code openssl pkcs8} + Zenoh's PEM samples
 * emit, and rejects everything else with a helpful error message.
 * It does <b>not</b> pull in Bouncy Castle or {@code sun.security.*};
 * everything is JDK stdlib.</p>
 *
 * <h2>Supported certificate PEM</h2>
 * <ul>
 *   <li>{@code -----BEGIN CERTIFICATE-----} / {@code -----END CERTIFICATE-----}
 *       blocks. Multiple concatenated blocks in one file are supported;
 *       useful for leaf + intermediate chains.</li>
 * </ul>
 *
 * <h2>Supported private-key PEM</h2>
 * <ul>
 *   <li>{@code -----BEGIN PRIVATE KEY-----} &mdash; PKCS#8 unencrypted.
 *       The modern format, what {@code openssl pkcs8 -topk8 -nocrypt}
 *       produces. Native handling via {@link PKCS8EncodedKeySpec}.</li>
 *   <li>{@code -----BEGIN RSA PRIVATE KEY-----} &mdash; PKCS#1 traditional
 *       RSA. What older {@code openssl req} still emits by default. This
 *       loader wraps it in a minimal PKCS#8 envelope in memory before
 *       decoding, so users don't need to run
 *       {@code openssl pkcs8 -topk8 -nocrypt} first.</li>
 *   <li>{@code -----BEGIN EC PRIVATE KEY-----} &mdash; PKCS#1-style EC.
 *       Same wrap-in-PKCS#8 treatment.</li>
 * </ul>
 *
 * <h2>Not supported (documented, will throw)</h2>
 * <ul>
 *   <li>{@code -----BEGIN ENCRYPTED PRIVATE KEY-----} &mdash; encrypted
 *       PKCS#8 keys. Users should convert with
 *       {@code openssl pkcs8 -topk8 -nocrypt} first, or use PKCS12
 *       keystores which handle passwords natively.</li>
 * </ul>
 */
final class PemLoader {

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----\\s*([A-Za-z0-9+/=\\s]+?)-----END \\1-----",
            Pattern.DOTALL);

    private PemLoader() {}

    /**
     * Read all {@code -----BEGIN CERTIFICATE-----} blocks from a PEM file and
     * decode each into an {@link X509Certificate}. Throws if the file contains
     * no certificate blocks or any block fails to decode.
     */
    static List<X509Certificate> readCertificates(Path pemPath) throws IOException {
        Objects.requireNonNull(pemPath, "pemPath");
        String text = Files.readString(pemPath, StandardCharsets.US_ASCII);
        List<X509Certificate> out = new ArrayList<>();
        Matcher m = PEM_BLOCK.matcher(text);
        CertificateFactory cf;
        try { cf = CertificateFactory.getInstance("X.509"); }
        catch (CertificateException e) {
            throw new IOException("X.509 CertificateFactory unavailable: " + e.getMessage(), e);
        }
        while (m.find()) {
            String label = m.group(1).trim();
            if (!"CERTIFICATE".equals(label)) continue;
            byte[] der = decodeBase64Body(m.group(2));
            try {
                X509Certificate cert = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(der));
                out.add(cert);
            } catch (CertificateException e) {
                throw new IOException(
                        "failed to decode CERTIFICATE block in " + pemPath + ": " + e.getMessage(), e);
            }
        }
        if (out.isEmpty()) {
            throw new IOException(
                    "no CERTIFICATE PEM block found in " + pemPath);
        }
        return out;
    }

    /**
     * Read a single private-key PEM block and decode it into a {@link PrivateKey}.
     * Supports {@code PRIVATE KEY} (PKCS#8 unencrypted), {@code RSA PRIVATE KEY}
     * (PKCS#1, auto-wrapped in PKCS#8), and {@code EC PRIVATE KEY} (same).
     */
    static PrivateKey readPrivateKey(Path pemPath) throws IOException {
        Objects.requireNonNull(pemPath, "pemPath");
        String text = Files.readString(pemPath, StandardCharsets.US_ASCII);
        Matcher m = PEM_BLOCK.matcher(text);
        while (m.find()) {
            String label = m.group(1).trim();
            byte[] der = decodeBase64Body(m.group(2));
            return switch (label) {
                case "PRIVATE KEY" -> decodePkcs8(der, "RSA-then-EC", pemPath);
                case "RSA PRIVATE KEY" -> decodePkcs8(wrapPkcs1AsPkcs8(der, RSA_OID), "RSA", pemPath);
                case "EC PRIVATE KEY"  -> decodePkcs8(wrapPkcs1AsPkcs8(der, EC_OID),  "EC",  pemPath);
                case "ENCRYPTED PRIVATE KEY" -> throw new IOException(
                        "encrypted PKCS#8 keys are not supported; convert with "
                                + "'openssl pkcs8 -topk8 -nocrypt -in in.pem -out out.pem' "
                                + "or use a PKCS12 keystore. File: " + pemPath);
                default -> {
                    // Not a key block; keep scanning.
                    if (!m.find()) yield null;
                    throw new IOException(
                            "unrecognised PEM label '" + label + "' in " + pemPath);
                }
            };
        }
        throw new IOException("no PRIVATE KEY PEM block found in " + pemPath);
    }

    // ---- internals ----------------------------------------------------

    /** OID for rsaEncryption (1.2.840.113549.1.1.1), DER-encoded AlgorithmIdentifier prefix bytes. */
    private static final byte[] RSA_OID = new byte[] {
            0x30, 0x0d,
            0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
    };
    /** OID for id-ecPublicKey (1.2.840.10045.2.1). Fixed prefix; the curve OID lives in the PKCS#1 body. */
    private static final byte[] EC_OID = new byte[] {
            0x30, 0x0b,
            0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01
    };

    private static byte[] decodeBase64Body(String body) {
        // Strip whitespace + strict-decode; base64 in PEM allows arbitrary line breaks.
        return Base64.getMimeDecoder().decode(body.getBytes(StandardCharsets.US_ASCII));
    }

    private static PrivateKey decodePkcs8(byte[] der, String algHint, Path source) throws IOException {
        // Try RSA first (the vast majority of use), then EC. If the caller
        // knows the algorithm (RSA / EC paths above), respect the hint.
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        String[] algs = switch (algHint) {
            case "RSA" -> new String[] { "RSA" };
            case "EC"  -> new String[] { "EC"  };
            default    -> new String[] { "RSA", "EC" };  // PKCS#8 self-identifies; either factory reads it
        };
        Exception last = null;
        for (String alg : algs) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                last = e;
            }
        }
        throw new IOException(
                "failed to decode private key in " + source + " as any of "
                        + java.util.Arrays.toString(algs)
                        + ": " + (last == null ? "unknown" : last.getMessage()), last);
    }

    /**
     * Wrap a raw PKCS#1 DER key body in a minimal PKCS#8 envelope so
     * {@link PKCS8EncodedKeySpec} + {@link KeyFactory} can decode it.
     *
     * <p>Layout emitted:</p>
     * <pre>
     * SEQUENCE {
     *   INTEGER 0,                   -- version
     *   AlgorithmIdentifier { ... }, -- from algOid
     *   OCTET STRING { pkcs1Der }    -- the raw PKCS#1 body
     * }
     * </pre>
     */
    private static byte[] wrapPkcs1AsPkcs8(byte[] pkcs1Der, byte[] algOid) throws IOException {
        try {
            java.io.ByteArrayOutputStream inner = new java.io.ByteArrayOutputStream();
            // version INTEGER 0
            inner.write(new byte[] { 0x02, 0x01, 0x00 });
            // algorithm AlgorithmIdentifier
            inner.write(algOid);
            // privateKey OCTET STRING containing the PKCS#1 body
            writeDerTag(inner, (byte) 0x04, pkcs1Der);
            // outer SEQUENCE
            java.io.ByteArrayOutputStream outer = new java.io.ByteArrayOutputStream();
            writeDerTag(outer, (byte) 0x30, inner.toByteArray());
            return outer.toByteArray();
        } catch (IOException e) {
            throw new IOException("PKCS#1->PKCS#8 wrap failed: " + e.getMessage(), e);
        }
    }

    private static void writeDerTag(OutputStream out, byte tag, byte[] body) throws IOException {
        out.write(tag);
        int len = body.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len <= 0xFF) {
            out.write(0x81);
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(0x82);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else if (len <= 0xFFFFFF) {
            out.write(0x83);
            out.write((len >>> 16) & 0xFF);
            out.write((len >>> 8)  & 0xFF);
            out.write(len & 0xFF);
        } else {
            throw new IOException("DER length too large: " + len);
        }
        out.write(body);
    }
}

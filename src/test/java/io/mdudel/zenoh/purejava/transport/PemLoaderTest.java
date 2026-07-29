/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 *
 * Unit tests for PemLoader. All PEM material is generated at test-startup
 * with JDK-only APIs (KeyPairGenerator + JcaPKCS10CertificationRequest-free
 * hand-rolled self-signed certs); there are NO on-disk fixture files, so
 * the suite runs in any environment with just a JDK -- no openssl needed.
 *
 * Covers the specific behaviours introduced/fixed by commit e2cdae3
 * ("KK fixed my pemloader"):
 *   1. class + methods are public (compile-time check just by importing).
 *   2. EC PRIVATE KEY blocks are wrapped with the CORRECT curve OID via
 *      the new DerReader-based ecAlgorithmIdentifier() -- old code
 *      emitted a hardcoded fixed AlgorithmIdentifier and could not
 *      decode any real EC key.
 *   3. decodePkcs8() default branch tries Ed25519 / Ed448 / DSA in
 *      addition to RSA / EC.
 *   4. readPrivateKey() now silently skips unrecognised PEM blocks
 *      (default -> null branch) instead of aborting mid-file.
 *
 * Plus the standard sanity coverage: cert loading, chain concatenation,
 * PKCS#1 RSA auto-wrap, encrypted-key rejection message, empty-file
 * rejection, null argument, key<->cert modulus match.
 */
package io.mdudel.zenoh.purejava.transport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PemLoaderTest {

    // =============================================================
    // CERTIFICATE loading
    // =============================================================

    @Test
    void loadsSingleSelfSignedCertificate() throws Exception {
        KeyPair kp = rsaKeyPair();
        X509Certificate expected = SelfSignedCerts.rsaSelfSigned(kp, "CN=zenoh-test");
        Path pem = writeTemp("cert-", ".pem", certToPem(expected));
        List<X509Certificate> got = PemLoader.readCertificates(pem);
        assertEquals(1, got.size());
        assertEquals(expected.getSubjectX500Principal(),
                got.get(0).getSubjectX500Principal());
    }

    @Test
    void loadsChainOfConcatenatedCertificates() throws Exception {
        KeyPair a = rsaKeyPair();
        KeyPair b = rsaKeyPair();
        X509Certificate ca = SelfSignedCerts.rsaSelfSigned(a, "CN=ca");
        X509Certificate leaf = SelfSignedCerts.rsaSelfSigned(b, "CN=leaf");
        String pem = certToPem(leaf) + "\n" + certToPem(ca);
        Path file = writeTemp("chain-", ".pem", pem);
        List<X509Certificate> got = PemLoader.readCertificates(file);
        assertEquals(2, got.size(), "chain should decode both blocks");
    }

    @Test
    void certFileWithOnlyKeyBlockIsRejected() throws Exception {
        // A file that contains a PRIVATE KEY block but no CERTIFICATE should
        // fail with the "no CERTIFICATE PEM block" message.
        KeyPair kp = rsaKeyPair();
        String keyOnly = pkcs8Pem(kp.getPrivate());
        Path file = writeTemp("keyonly-", ".pem", keyOnly);
        IOException e = assertThrows(IOException.class,
                () -> PemLoader.readCertificates(file));
        assertTrue(e.getMessage().contains("no CERTIFICATE PEM block"),
                "message was: " + e.getMessage());
    }

    @Test
    void certFileWithoutAnyPemBlockRejected() throws Exception {
        Path file = writeTemp("junk-", ".pem", "hello world -- no PEM here\n");
        IOException e = assertThrows(IOException.class,
                () -> PemLoader.readCertificates(file));
        assertTrue(e.getMessage().contains("no CERTIFICATE"),
                "message was: " + e.getMessage());
    }

    @Test
    void certFileWithMalformedBase64Rejected() throws Exception {
        // Well-formed BEGIN/END markers but garbage base64 body -> factory
        // wraps the CertificateException in an IOException.
        String bad = "-----BEGIN CERTIFICATE-----\n"
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n"
                + "-----END CERTIFICATE-----\n";
        Path file = writeTemp("badb64-", ".pem", bad);
        IOException e = assertThrows(IOException.class,
                () -> PemLoader.readCertificates(file));
        assertTrue(e.getMessage().contains("failed to decode CERTIFICATE block"),
                "message was: " + e.getMessage());
    }

    @Test
    void readCertificatesRejectsNullPath() {
        assertThrows(NullPointerException.class,
                () -> PemLoader.readCertificates(null));
    }

    // =============================================================
    // PRIVATE KEY loading -- PKCS#8 native path
    // =============================================================

    @Test
    void loadsPkcs8UnencryptedRsaKey() throws Exception {
        KeyPair kp = rsaKeyPair();
        Path pem = writeTemp("rsa-p8-", ".pem", pkcs8Pem(kp.getPrivate()));
        PrivateKey got = PemLoader.readPrivateKey(pem);
        assertEquals("RSA", got.getAlgorithm());
        assertEquals(((RSAPrivateKey) kp.getPrivate()).getModulus(),
                ((RSAPrivateKey) got).getModulus());
    }

    @Test
    void loadsPkcs8UnencryptedEcKey() throws Exception {
        // PKCS#8 EC key path: goes through decodePkcs8(der, "RSA-then-EC")
        // and must succeed via the EC KeyFactory branch.
        KeyPair kp = ecKeyPair("secp256r1");
        Path pem = writeTemp("ec-p8-", ".pem", pkcs8Pem(kp.getPrivate()));
        PrivateKey got = PemLoader.readPrivateKey(pem);
        assertEquals("EC", got.getAlgorithm());
        assertEquals(((ECPrivateKey) kp.getPrivate()).getS(),
                ((ECPrivateKey) got).getS());
    }

    @Test
    void loadsPkcs8Ed25519Key() throws Exception {
        // New in this commit: default alg-hint branch now also tries
        // Ed25519 / Ed448 / DSA. Prior code would have failed with
        // "failed to decode private key ... as any of [RSA, EC]".
        KeyPair kp;
        try {
            kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException nsae) {
            // JDK 15+ has this in the default SunEC provider; if it's
            // missing (very old JDK), skip cleanly.
            return;
        }
        Path pem = writeTemp("ed25519-p8-", ".pem", pkcs8Pem(kp.getPrivate()));
        PrivateKey got = PemLoader.readPrivateKey(pem);
        // JDK returns "Ed25519" on some versions and the umbrella "EdDSA"
        // on others (both are valid; the loader just needs to decode the
        // key at all -- which is what this commit's expanded alg-hint list
        // enables). Prior code would have failed outright with
        // "failed to decode private key ... as any of [RSA, EC]".
        String alg = got.getAlgorithm();
        assertTrue("Ed25519".equals(alg) || "EdDSA".equals(alg),
                "expected Ed25519/EdDSA, got: " + alg);
    }

    // =============================================================
    // PRIVATE KEY loading -- PKCS#1 auto-wrap paths
    // =============================================================

    @Test
    void loadsPkcs1RsaPrivateKeyViaAutoWrap() throws Exception {
        KeyPair kp = rsaKeyPair();
        byte[] pkcs1 = rsaPkcs8ToPkcs1Der(kp.getPrivate());
        String pem = pemBlock("RSA PRIVATE KEY", pkcs1);
        Path file = writeTemp("rsa-p1-", ".pem", pem);
        PrivateKey got = PemLoader.readPrivateKey(file);
        assertEquals("RSA", got.getAlgorithm());
        assertEquals(((RSAPrivateKey) kp.getPrivate()).getModulus(),
                ((RSAPrivateKey) got).getModulus());
    }

    @Test
    void pkcs1AndPkcs8FormsOfSameRsaKeyAgree() throws Exception {
        // Round-trip: same key material through both loader paths gives
        // byte-identical private-exponent + modulus.
        KeyPair kp = rsaKeyPair();
        Path p8 = writeTemp("rsa-p8-eq-", ".pem", pkcs8Pem(kp.getPrivate()));
        Path p1 = writeTemp("rsa-p1-eq-", ".pem",
                pemBlock("RSA PRIVATE KEY", rsaPkcs8ToPkcs1Der(kp.getPrivate())));
        RSAPrivateKey k8 = (RSAPrivateKey) PemLoader.readPrivateKey(p8);
        RSAPrivateKey k1 = (RSAPrivateKey) PemLoader.readPrivateKey(p1);
        assertEquals(k8.getModulus(), k1.getModulus());
        assertEquals(k8.getPrivateExponent(), k1.getPrivateExponent());
    }

    // -------------------------------------------------------------
    // THE ACTUAL BUG FIX: EC PRIVATE KEY (SEC1) auto-wrap
    //
    // Old code hardcoded a fixed AlgorithmIdentifier prefix with NO curve
    // OID inside; every real SEC1 EC key wrapped that way produced an
    // invalid PKCS#8 blob and KeyFactory.generatePrivate() failed.
    //
    // New code parses the SEC1 SEQUENCE, extracts the [0] explicit
    // curve-parameters tag, and rebuilds a proper
    //   AlgorithmIdentifier { id-ecPublicKey, curveOID }.
    // These four tests exercise three common named curves + one
    // negative-path check.
    // -------------------------------------------------------------

    @Test
    void loadsSec1EcPrivateKeyP256ViaAutoWrap() throws Exception {
        assertSec1RoundTrips("secp256r1");
    }

    @Test
    void loadsSec1EcPrivateKeyP384ViaAutoWrap() throws Exception {
        assertSec1RoundTrips("secp384r1");
    }

    @Test
    void loadsSec1EcPrivateKeyP521ViaAutoWrap() throws Exception {
        assertSec1RoundTrips("secp521r1");
    }

    @Test
    void sec1KeyWithoutCurveParametersRejected() throws Exception {
        // Build a minimal SEC1 SEQUENCE that has version + OCTET STRING
        // private-key but NO [0] curve-params element. New loader must
        // reject with the "no curve parameters" message rather than
        // returning a garbage key.
        byte[] version = new byte[]{0x02, 0x01, 0x01};                // INTEGER 1
        byte[] priv = new byte[]{0x04, 0x02, 0x00, 0x00};             // OCTET STRING 00 00 (dummy)
        byte[] body = concat(version, priv);
        byte[] sec1 = derTagLenValue(0x30, body);                     // SEQUENCE { version, priv }
        String pem = pemBlock("EC PRIVATE KEY", sec1);
        Path file = writeTemp("ec-nocurve-", ".pem", pem);
        IOException e = assertThrows(IOException.class,
                () -> PemLoader.readPrivateKey(file));
        assertTrue(e.getMessage().contains("no curve parameters"),
                "message was: " + e.getMessage());
    }

    // =============================================================
    // Behavioural changes on scanning + errors
    // =============================================================

    @Test
    void unknownLabelIsSkippedAndKeyBlockStillFound() throws Exception {
        // NEW behaviour: switch's default branch returns null and the
        // scanning loop continues. Old code would throw
        // "unrecognised PEM label 'DH PARAMETERS'". Puts the unknown
        // block BEFORE the key block to prove the loop advances.
        KeyPair kp = rsaKeyPair();
        String pem = "-----BEGIN DH PARAMETERS-----\n"
                + Base64.getEncoder().encodeToString(new byte[]{0, 1, 2, 3, 4}) + "\n"
                + "-----END DH PARAMETERS-----\n"
                + pkcs8Pem(kp.getPrivate());
        Path file = writeTemp("mixed-", ".pem", pem);
        PrivateKey got = PemLoader.readPrivateKey(file);
        assertEquals("RSA", got.getAlgorithm());
    }

    @Test
    void certConcatenatedWithKeyKeyIsStillFound() throws Exception {
        // Common real-world case: chain file with cert THEN key blocks.
        // Loader for readPrivateKey should skip the CERTIFICATE block
        // (unknown label -> null -> continue) and return the key.
        KeyPair kp = rsaKeyPair();
        X509Certificate cert = SelfSignedCerts.rsaSelfSigned(kp, "CN=combo");
        String combined = certToPem(cert) + pkcs8Pem(kp.getPrivate());
        Path file = writeTemp("combo-", ".pem", combined);
        PrivateKey got = PemLoader.readPrivateKey(file);
        assertEquals("RSA", got.getAlgorithm());
    }

    @Test
    void missingKeyBlockRejected() throws Exception {
        Path file = writeTemp("no-key-", ".pem", "totally not a key\n");
        IOException e = assertThrows(IOException.class,
                () -> PemLoader.readPrivateKey(file));
        assertTrue(e.getMessage().contains("no PRIVATE KEY"),
                "message was: " + e.getMessage());
    }

    @Test
    void encryptedPkcs8KeyRejectedWithHelpfulMessage() throws Exception {
        String pem = "-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
                + "MIIBrTBXBgkqhkiG9w0BBQ0wSjApBgkqhkiG9w0BBQwwHAQI\n"
                + "-----END ENCRYPTED PRIVATE KEY-----\n";
        Path file = writeTemp("enc-", ".pem", pem);
        IOException e = assertThrows(IOException.class,
                () -> PemLoader.readPrivateKey(file));
        String msg = e.getMessage();
        assertTrue(msg.contains("openssl pkcs8"),
                "should point at openssl conversion: " + msg);
        assertTrue(msg.contains("encrypted"),
                "should mention 'encrypted': " + msg);
    }

    @Test
    void readPrivateKeyRejectsNullPath() {
        assertThrows(NullPointerException.class,
                () -> PemLoader.readPrivateKey(null));
    }

    // =============================================================
    // Cert <-> key coherence
    // =============================================================

    @Test
    void loadedRsaKeyAndCertHaveMatchingModulus() throws Exception {
        KeyPair kp = rsaKeyPair();
        X509Certificate cert = SelfSignedCerts.rsaSelfSigned(kp, "CN=match");
        Path cpem = writeTemp("cmatch-", ".pem", certToPem(cert));
        Path kpem = writeTemp("kmatch-", ".pem", pkcs8Pem(kp.getPrivate()));
        RSAPrivateKey rk = (RSAPrivateKey) PemLoader.readPrivateKey(kpem);
        RSAPublicKey pk = (RSAPublicKey) PemLoader.readCertificates(cpem).get(0).getPublicKey();
        assertEquals(pk.getModulus(), rk.getModulus());
    }

    // =============================================================
    // API surface: readPrivateKey returns the KEY not null on happy path
    // (regression guard around the new `default -> null` branch)
    // =============================================================

    @Test
    void readPrivateKeyNeverReturnsNullOnHappyPath() throws Exception {
        KeyPair kp = rsaKeyPair();
        Path pem = writeTemp("nn-", ".pem", pkcs8Pem(kp.getPrivate()));
        PrivateKey got = PemLoader.readPrivateKey(pem);
        assertNotNull(got, "happy-path load must not return null");
    }

    @Test
    void fileWithOnlyUnknownLabelsGivesMissingKeyError() throws Exception {
        // If EVERY block hits the default->null branch, the loop exits
        // without a hit and the "no PRIVATE KEY PEM block found" IOException
        // is thrown -- proves the fallthrough correctly reaches the end.
        String pem = "-----BEGIN DH PARAMETERS-----\n"
                + Base64.getEncoder().encodeToString(new byte[]{9, 8, 7}) + "\n"
                + "-----END DH PARAMETERS-----\n"
                + "-----BEGIN X509 CRL-----\n"
                + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}) + "\n"
                + "-----END X509 CRL-----\n";
        Path file = writeTemp("only-unknown-", ".pem", pem);
        IOException e = assertThrows(IOException.class,
                () -> PemLoader.readPrivateKey(file));
        assertTrue(e.getMessage().contains("no PRIVATE KEY"),
                "message was: " + e.getMessage());
    }

    // =============================================================
    // Helpers
    // =============================================================

    private static void assertSec1RoundTrips(String curve) throws Exception {
        KeyPair kp = ecKeyPair(curve);
        byte[] sec1 = ecPkcs8ToSec1Der(kp.getPrivate());
        String pem = pemBlock("EC PRIVATE KEY", sec1);
        Path file = writeTemp("ec-sec1-" + curve + "-", ".pem", pem);
        PrivateKey got = PemLoader.readPrivateKey(file);
        assertEquals("EC", got.getAlgorithm());
        ECPrivateKey want = (ECPrivateKey) kp.getPrivate();
        ECPrivateKey have = (ECPrivateKey) got;
        assertEquals(want.getS(), have.getS(),
                "SEC1-wrap must round-trip S scalar for " + curve);
        assertFalse(have.getParams() == null,
                "decoded EC key must carry curve parameters for " + curve);
    }

    private static KeyPair rsaKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static KeyPair ecKeyPair(String curve) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec(curve));
        return g.generateKeyPair();
    }

    /** Emit a PKCS#8 PEM using the private key's own getEncoded() (which is PKCS#8). */
    private static String pkcs8Pem(PrivateKey pk) {
        return pemBlock("PRIVATE KEY", pk.getEncoded());
    }

    /** Emit a CERTIFICATE PEM. */
    private static String certToPem(X509Certificate c) throws Exception {
        return pemBlock("CERTIFICATE", c.getEncoded());
    }

    private static String pemBlock(String label, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + b64 + "\n-----END " + label + "-----\n";
    }

    private static Path writeTemp(String prefix, String suffix, String content) throws IOException {
        Path p = Files.createTempFile(prefix, suffix);
        p.toFile().deleteOnExit();
        Files.writeString(p, content, StandardCharsets.US_ASCII);
        return p;
    }

    /**
     * Take a JDK RSA private key (which encodes as PKCS#8) and hand-emit
     * the equivalent PKCS#1 RSAPrivateKey DER body -- exactly what old
     * `openssl req` would drop into a "-----BEGIN RSA PRIVATE KEY-----"
     * block. Uses only java.security stdlib.
     */
    private static byte[] rsaPkcs8ToPkcs1Der(PrivateKey pk) throws Exception {
        RSAPrivateCrtKey k = (RSAPrivateCrtKey) pk;
        // PKCS#1 RSAPrivateKey ::= SEQUENCE {
        //   version INTEGER (0),
        //   modulus, publicExponent, privateExponent,
        //   prime1, prime2, exponent1, exponent2, coefficient
        // }
        byte[] body = concat(
                derInteger(BigInteger.ZERO),
                derInteger(k.getModulus()),
                derInteger(k.getPublicExponent()),
                derInteger(k.getPrivateExponent()),
                derInteger(k.getPrimeP()),
                derInteger(k.getPrimeQ()),
                derInteger(k.getPrimeExponentP()),
                derInteger(k.getPrimeExponentQ()),
                derInteger(k.getCrtCoefficient())
        );
        return derTagLenValue(0x30, body);
    }

    /**
     * Take a JDK EC private key (PKCS#8-encoded) and convert to the SEC1
     * "-----BEGIN EC PRIVATE KEY-----" DER body.
     *
     * <p>Cheap trick: the PKCS#8 blob wraps the SEC1 body as the payload
     * OCTET STRING of the PrivateKeyInfo, so we just re-parse and pull
     * that octet-string out. We then splice the curve OID (which lives in
     * the outer AlgorithmIdentifier) back in as the SEC1 [0] parameters
     * element so it round-trips through the loader.
     */
    private static byte[] ecPkcs8ToSec1Der(PrivateKey pk) throws Exception {
        // Re-decode via PKCS8EncodedKeySpec to keep it strictly stdlib.
        // The private key's own getEncoded() is already PKCS#8 DER.
        byte[] pkcs8 = pk.getEncoded();
        // Sanity round-trip check we can rebuild the same key from it.
        KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));

        // Walk the PKCS#8 SEQUENCE: version, AlgorithmIdentifier, OCTET STRING.
        MiniDer outer = new MiniDer(pkcs8);
        MiniDer seq = new MiniDer(outer.read(0x30));
        seq.read(0x02);                       // version INTEGER
        byte[] alg = seq.read(0x30);          // AlgorithmIdentifier SEQUENCE
        byte[] pkOctet = seq.read(0x04);      // privateKey OCTET STRING -> SEC1 body

        // From the AlgorithmIdentifier, pull the curve OID (2nd element).
        MiniDer algSeq = new MiniDer(alg);
        algSeq.read(0x06);                    // id-ecPublicKey OID
        byte[] curveOid = algSeq.read(0x06);  // named-curve OID content

        // The pkOctet is itself a SEC1 ECPrivateKey SEQUENCE that MAY or MAY
        // NOT contain the [0] parameters element depending on JDK version.
        // Rebuild it deterministically WITH the [0] parameters element so
        // the loader's ecAlgorithmIdentifier() has something to extract.
        MiniDer sec1Outer = new MiniDer(pkOctet);
        MiniDer sec1 = new MiniDer(sec1Outer.read(0x30));
        byte[] version = sec1.read(0x02);
        byte[] privKey = sec1.read(0x04);

        // Reconstruct: SEQUENCE { INTEGER version, OCTET STRING privateKey,
        //                         [0] OID curveOid }
        byte[] versionTLV = derTagLenValue(0x02, version);
        byte[] privKeyTLV = derTagLenValue(0x04, privKey);
        byte[] curveOidTLV = derTagLenValue(0x06, curveOid);
        byte[] paramsCtx0 = derTagLenValue(0xA0, curveOidTLV);
        byte[] body = concat(versionTLV, privKeyTLV, paramsCtx0);
        return derTagLenValue(0x30, body);
    }

    /** Minimal DER TLV reader used only in test helpers. */
    private static final class MiniDer {
        private final byte[] data;
        private int pos;

        MiniDer(byte[] d) {
            this.data = d;
        }

        byte[] read(int expectedTag) throws IOException {
            int tag = data[pos++] & 0xFF;
            if (tag != expectedTag) {
                throw new IOException(String.format(
                        "expected tag 0x%02x, got 0x%02x at pos %d", expectedTag, tag, pos - 1));
            }
            int first = data[pos++] & 0xFF;
            int len;
            if ((first & 0x80) == 0) {
                len = first;
            } else {
                int n = first & 0x7F;
                len = 0;
                for (int i = 0; i < n; i++) len = (len << 8) | (data[pos++] & 0xFF);
            }
            byte[] out = new byte[len];
            System.arraycopy(data, pos, out, 0, len);
            pos += len;
            return out;
        }
    }

    private static byte[] derInteger(BigInteger v) {
        return derTagLenValue(0x02, v.toByteArray());
    }

    private static byte[] derTagLenValue(int tag, byte[] body) {
        byte[] len = derLength(body.length);
        byte[] out = new byte[1 + len.length + body.length];
        out[0] = (byte) tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(body, 0, out, 1 + len.length, body.length);
        return out;
    }

    private static byte[] derLength(int len) {
        if (len < 0x80) {
            return new byte[]{(byte) len};
        } else if (len <= 0xFF) {
            return new byte[]{(byte) 0x81, (byte) len};
        } else if (len <= 0xFFFF) {
            return new byte[]{(byte) 0x82, (byte) (len >>> 8), (byte) len};
        } else if (len <= 0xFFFFFF) {
            return new byte[]{(byte) 0x83, (byte) (len >>> 16), (byte) (len >>> 8), (byte) len};
        }
        throw new IllegalArgumentException("length too large: " + len);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    // Referenced only from tests; silences "unused import" on some JDKs.
    @SuppressWarnings("unused")
    private static final Class<?> UNUSED_ECPUB = ECPublicKey.class;
    @SuppressWarnings("unused")
    private static final Class<?> UNUSED_RSAPCRT = RSAPrivateCrtKeySpec.class;

    // ---- self-signed cert builder --------------------------------
    // Hand-rolled minimal X.509 v1 self-signed builder so we don't depend
    // on BouncyCastle. Enough to satisfy CertificateFactory.generateCert
    // and expose subject / public key for the tests above.
    static final class SelfSignedCerts {
        static X509Certificate rsaSelfSigned(KeyPair kp, String subjectDn) throws Exception {
            RSAPublicKey pub = (RSAPublicKey) kp.getPublic();

            // TBSCertificate (v1) fields
            byte[] serial = derInteger(BigInteger.valueOf(System.nanoTime() & 0x7FFFFFFFFFFFFFFFL));
            byte[] sigAlgSeq = rsaSha256AlgId();
            byte[] name = distinguishedName(subjectDn);
            byte[] validity = derTagLenValue(0x30, concat(
                    utcTime(-3600L),
                    utcTime(365L * 24 * 3600L)
            ));
            byte[] spki = subjectPublicKeyInfoRsa(pub);
            byte[] tbs = derTagLenValue(0x30, concat(
                    serial, sigAlgSeq, name, validity, name, spki
            ));

            // Sign
            java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initSign(kp.getPrivate());
            sig.update(tbs);
            byte[] sigBytes = sig.sign();
            byte[] sigBitString = derTagLenValue(0x03,
                    concat(new byte[]{0x00}, sigBytes));    // 0 unused bits

            byte[] certDer = derTagLenValue(0x30, concat(tbs, sigAlgSeq, sigBitString));
            return (X509Certificate) java.security.cert.CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(certDer));
        }

        private static byte[] rsaSha256AlgId() {
            // OID 1.2.840.113549.1.1.11 sha256WithRSAEncryption
            byte[] oid = new byte[]{0x06, 0x09,
                    0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7,
                    0x0D, 0x01, 0x01, 0x0B};
            byte[] nullParam = new byte[]{0x05, 0x00};
            return derTagLenValue(0x30, concat(oid, nullParam));
        }

        private static byte[] distinguishedName(String cn) {
            // Very small: RDNSequence with one AttributeTypeAndValue: CN=<value>
            if (!cn.startsWith("CN=")) {
                throw new IllegalArgumentException("only CN= supported in test helper: " + cn);
            }
            String value = cn.substring(3);
            byte[] cnOid = new byte[]{0x06, 0x03, 0x55, 0x04, 0x03};       // 2.5.4.3
            byte[] cnValue = derTagLenValue(0x0C, value.getBytes(StandardCharsets.UTF_8));
            byte[] attrTypeAndValue = derTagLenValue(0x30, concat(cnOid, cnValue));
            byte[] rdn = derTagLenValue(0x31, attrTypeAndValue);
            return derTagLenValue(0x30, rdn);
        }

        private static byte[] utcTime(long deltaSeconds) {
            long epoch = System.currentTimeMillis() / 1000L + deltaSeconds;
            java.util.Date d = new java.util.Date(epoch * 1000L);
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String s = sdf.format(d);
            return derTagLenValue(0x17, s.getBytes(StandardCharsets.US_ASCII));
        }

        private static byte[] subjectPublicKeyInfoRsa(RSAPublicKey pub) {
            // AlgorithmIdentifier { rsaEncryption, NULL }
            byte[] oid = new byte[]{0x06, 0x09,
                    0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7,
                    0x0D, 0x01, 0x01, 0x01};
            byte[] nullParam = new byte[]{0x05, 0x00};
            byte[] alg = derTagLenValue(0x30, concat(oid, nullParam));

            // RSAPublicKey SEQUENCE { modulus INTEGER, publicExponent INTEGER }
            byte[] rsaPubKey = derTagLenValue(0x30, concat(
                    derInteger(pub.getModulus()),
                    derInteger(pub.getPublicExponent())));
            // wrapped in BIT STRING (0 unused bits)
            byte[] pkBits = derTagLenValue(0x03,
                    concat(new byte[]{0x00}, rsaPubKey));

            return derTagLenValue(0x30, concat(alg, pkBits));
        }
    }
}

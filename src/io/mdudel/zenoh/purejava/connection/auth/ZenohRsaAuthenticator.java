/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Portions derived from Eclipse Zenoh 1.9.0, Copyright ZettaScale Technology,
 * under the Apache License, Version 2.0.
 */
package io.mdudel.zenoh.purejava.connection.auth;

import io.mdudel.zenoh.purejava.session.SessionAuthenticator;
import io.mdudel.zenoh.purejava.session.SessionException;
import io.mdudel.zenoh.purejava.transport.PemLoader;
import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.RBuf;
import io.mdudel.zenoh.purejava.wire.WBuf;

import javax.crypto.Cipher;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Zenoh 1.9 pubkey transport authentication. This is distinct from TLS/mTLS
 * and, per the official 1.9 implementation, uses RSA PKCS#1 v1.5 encryption.
 */
public final class ZenohRsaAuthenticator implements SessionAuthenticator {
    static final int OUTER_AUTH_ID = 3;
    static final int PUBKEY_AUTH_ID = 1;

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final Set<RsaIdentity> trustedRouters;
    private byte[] responseForRouter;

    public ZenohRsaAuthenticator(Path publicKeyPath, Path privateKeyPath,
                                 Path... trustedRouterPublicKeys) throws IOException {
        this(asRsaPublic(PemLoader.readPublicKey(publicKeyPath), publicKeyPath),
                asRsaPrivate(PemLoader.readPrivateKey(privateKeyPath), privateKeyPath),
                loadTrusted(trustedRouterPublicKeys));
    }

    ZenohRsaAuthenticator(RSAPublicKey publicKey, RSAPrivateKey privateKey,
                          Set<RsaIdentity> trustedRouters) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalArgumentException("Zenoh RSA public/private keys do not match");
        }
        this.trustedRouters = Set.copyOf(trustedRouters);
    }

    @Override public List<Extension> initSynExtensions() {
        WBuf pubkey = new WBuf();
        pubkey.lenBytes(toLittleEndian(publicKey.getModulus()));
        pubkey.lenBytes(toLittleEndian(publicKey.getPublicExponent()));
        return List.of(outerAuth(innerZbuf(pubkey.toByteArray())));
    }

    @Override public void receiveInitAck(List<Extension> extensions) throws SessionException {
        byte[] payload = innerPubkeyPayload(extensions, "InitAck", Extension.Encoding.ZBUF);
        try {
            RBuf reader = new RBuf(payload);
            BigInteger modulus = fromLittleEndian(reader.lenBytes());
            BigInteger exponent = fromLittleEndian(reader.lenBytes());
            byte[] encryptedNonce = reader.lenBytes();
            if (reader.hasMore()) throw new IllegalArgumentException("trailing pubkey-auth bytes");
            RSAPublicKey routerKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
            if (!trustedRouters.isEmpty()
                    && !trustedRouters.contains(new RsaIdentity(modulus, exponent))) {
                throw new SessionException("Zenoh pubkey authentication rejected untrusted router RSA key");
            }
            Cipher decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            decrypt.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] nonce = decrypt.doFinal(encryptedNonce);
            Cipher encrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            encrypt.init(Cipher.ENCRYPT_MODE, routerKey);
            responseForRouter = encrypt.doFinal(nonce);
        } catch (SessionException e) {
            throw e;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SessionException("Zenoh 1.9 RSA InitAck authentication failed: " + e.getMessage(), e);
        }
    }

    @Override public List<Extension> openSynExtensions() throws SessionException {
        if (responseForRouter == null) {
            throw new SessionException("Zenoh RSA authentication state missing InitAck challenge");
        }
        WBuf response = new WBuf();
        response.lenBytes(responseForRouter);
        return List.of(outerAuth(innerZbuf(response.toByteArray())));
    }

    @Override public void receiveOpenAck(List<Extension> extensions) throws SessionException {
        innerPubkeyPayload(extensions, "OpenAck", Extension.Encoding.UNIT);
        responseForRouter = null;
    }

    private static Extension outerAuth(byte[] innerExtensionList) {
        return Extension.zbuf(OUTER_AUTH_ID, false, innerExtensionList);
    }

    private static byte[] innerZbuf(byte[] payload) {
        WBuf result = new WBuf();
        result.varInt(1); // number of authentication mechanisms
        Extension.writeAll(List.of(Extension.zbuf(PUBKEY_AUTH_ID, false, payload)), result);
        return result.toByteArray();
    }

    private static byte[] innerPubkeyPayload(List<Extension> extensions, String phase,
                                             Extension.Encoding expected) throws SessionException {
        Extension outer = extensions.stream()
                .filter(e -> e.id() == OUTER_AUTH_ID && e.encoding() == Extension.Encoding.ZBUF)
                .findFirst().orElseThrow(() -> new SessionException(
                        phase + " missing Zenoh authentication extension"));
        try {
            RBuf list = new RBuf(outer.asZBuf());
            long count = list.varInt();
            if (count < 1 || count > 16) throw new IllegalArgumentException("invalid auth mechanism count " + count);
            List<Extension> mechanisms = Extension.readAll(list);
            if (mechanisms.size() != count || list.hasMore()) {
                throw new IllegalArgumentException("malformed authentication mechanism list");
            }
            Extension pubkey = mechanisms.stream().filter(e -> e.id() == PUBKEY_AUTH_ID).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("missing RSA pubkey mechanism"));
            if (pubkey.encoding() != expected) {
                throw new IllegalArgumentException("RSA pubkey mechanism has " + pubkey.encoding()
                        + ", expected " + expected);
            }
            return expected == Extension.Encoding.ZBUF ? pubkey.asZBuf() : new byte[0];
        } catch (IllegalArgumentException e) {
            throw new SessionException("invalid Zenoh authentication extension in " + phase
                    + ": " + e.getMessage(), e);
        }
    }

    private static Set<RsaIdentity> loadTrusted(Path[] paths) throws IOException {
        Set<RsaIdentity> result = new HashSet<>();
        if (paths == null) return result;
        for (Path path : paths) {
            RSAPublicKey key = asRsaPublic(PemLoader.readPublicKey(
                    Objects.requireNonNull(path, "trustedRouterPublicKey")), path);
            result.add(new RsaIdentity(key.getModulus(), key.getPublicExponent()));
        }
        return result;
    }

    private static RSAPublicKey asRsaPublic(PublicKey key, Path source) throws IOException {
        if (key instanceof RSAPublicKey rsa) return rsa;
        throw new IOException("Zenoh 1.9 pubkey authentication requires an RSA public key: " + source);
    }

    private static RSAPrivateKey asRsaPrivate(PrivateKey key, Path source) throws IOException {
        if (key instanceof RSAPrivateKey rsa) return rsa;
        throw new IOException("Zenoh 1.9 pubkey authentication requires an RSA private key: " + source);
    }

    static byte[] toLittleEndian(BigInteger value) {
        byte[] big = value.toByteArray();
        int start = big.length > 1 && big[0] == 0 ? 1 : 0;
        byte[] little = new byte[big.length - start];
        for (int i = start; i < big.length; i++) little[big.length - 1 - i] = big[i];
        return little;
    }

    static BigInteger fromLittleEndian(byte[] value) {
        if (value.length == 0) throw new IllegalArgumentException("empty RSA integer");
        byte[] big = new byte[value.length];
        for (int i = 0; i < value.length; i++) big[value.length - 1 - i] = value[i];
        return new BigInteger(1, big);
    }

    record RsaIdentity(BigInteger modulus, BigInteger exponent) {}
}

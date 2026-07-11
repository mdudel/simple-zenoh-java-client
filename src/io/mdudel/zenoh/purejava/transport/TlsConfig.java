/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.transport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Immutable TLS configuration for {@link TlsTransport}. Build one with
 * the fluent {@link Builder} and reuse it across many transport
 * instances &mdash; the underlying {@link SSLContext} is materialised
 * once at build time.
 *
 * <p>Minimal-viable config is a trust store path + password; the
 * server-cert path is common in accreditation environments where an
 * internal CA is not in the JVM's default cacerts. Optional client
 * keystore + password enables mTLS.</p>
 *
 * <p>Everything else has JDK-safe defaults: TLSv1.3 + TLSv1.2 enabled,
 * hostname verification on, JVM default cipher suites, 15 s handshake
 * timeout.</p>
 *
 * <p>All KeyStore / TrustManager / KeyManager wiring happens in
 * {@link Builder#build()} and any {@link GeneralSecurityException} or
 * {@link IOException} is wrapped in {@link IllegalStateException}
 * so callers can treat the builder outcome as either "usable config"
 * or "startup-time misconfiguration". Runtime handshake failures
 * remain regular {@link TransportException}s in the transport.</p>
 */
public final class TlsConfig {

    /** Enabled protocols in preference order. TLSv1.3 first, TLSv1.2 fallback. */
    public static final List<String> DEFAULT_PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

    /** Default TLS handshake timeout (ms). */
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_MS = 15_000;

    private final SSLContext       sslContext;
    private final SSLSocketFactory socketFactory;
    private final List<String>     enabledProtocols;
    private final List<String>     enabledCipherSuites;  // null = JVM default
    private final boolean          verifyHostname;
    private final int              handshakeTimeoutMs;
    private final boolean          needClientAuth;       // for future server-mode reuse

    private TlsConfig(SSLContext sslContext,
                      SSLSocketFactory socketFactory,
                      List<String> enabledProtocols,
                      List<String> enabledCipherSuites,
                      boolean verifyHostname,
                      int handshakeTimeoutMs,
                      boolean needClientAuth) {
        this.sslContext          = sslContext;
        this.socketFactory       = socketFactory;
        this.enabledProtocols    = List.copyOf(enabledProtocols);
        this.enabledCipherSuites = enabledCipherSuites == null ? null : List.copyOf(enabledCipherSuites);
        this.verifyHostname      = verifyHostname;
        this.handshakeTimeoutMs  = handshakeTimeoutMs;
        this.needClientAuth      = needClientAuth;
    }

    /** The underlying {@link SSLContext}. Exposed for consumers (e.g. {@code WsTransport}) that need to configure an {@link javax.net.ssl.SSLEngine} rather than an {@link SSLSocket}. */
    public SSLContext       sslContext()          { return sslContext; }
    public SSLSocketFactory socketFactory()       { return socketFactory; }
    public List<String>     enabledProtocols()    { return enabledProtocols; }
    public List<String>     enabledCipherSuites() { return enabledCipherSuites; }
    public boolean          verifyHostname()      { return verifyHostname; }
    public int              handshakeTimeoutMs()  { return handshakeTimeoutMs; }
    public boolean          needClientAuth()      { return needClientAuth; }

    public static Builder builder() { return new Builder(); }

    // ---- Builder ------------------------------------------------------

    public static final class Builder {

        private Path         trustStorePath;
        private char[]       trustStorePassword;
        private String       trustStoreType   = "PKCS12";
        private List<Path>   trustPemPaths;              // set instead of trustStorePath for PEM CAs

        private Path         keyStorePath;
        private char[]       keyStorePassword;
        private char[]       keyPassword;
        private String       keyStoreType     = "PKCS12";
        private Path         keyPemCertChain;            // set instead of keyStorePath for PEM key material
        private Path         keyPemPrivateKey;

        private List<String> enabledProtocols    = DEFAULT_PROTOCOLS;
        private List<String> enabledCipherSuites = null;
        private boolean      verifyHostname      = true;
        private int          handshakeTimeoutMs  = DEFAULT_HANDSHAKE_TIMEOUT_MS;
        private boolean      needClientAuth      = false;

        private Builder() {}

        /**
         * Set the trust store. Required unless {@link #trustSystem()} is used.
         * Password may be {@code null} for password-less trust stores.
         */
        public Builder trustStore(Path path, char[] password) {
            this.trustStorePath = Objects.requireNonNull(path, "path");
            this.trustStorePassword = password;
            return this;
        }

        /** Change trust store type. Default {@code "PKCS12"}. */
        public Builder trustStoreType(String type) {
            this.trustStoreType = Objects.requireNonNull(type, "type");
            return this;
        }

        /** Use the JVM default trust anchors (typically the {@code cacerts} file). */
        public Builder trustSystem() {
            this.trustStorePath     = null;
            this.trustStorePassword = null;
            this.trustPemPaths      = null;
            return this;
        }

        /**
         * Trust the CA certificate(s) in one or more PEM files. Every
         * {@code -----BEGIN CERTIFICATE-----} block in each file is added
         * to an in-memory trust store, so a chain file (leaf + intermediates)
         * works as one argument, and multiple root CAs work as multiple
         * arguments. Mutually exclusive with {@link #trustStore(Path, char[])}.
         */
        public Builder trustStorePem(Path... caPemPaths) {
            if (caPemPaths == null || caPemPaths.length == 0) {
                throw new IllegalArgumentException("at least one PEM path required");
            }
            for (Path p : caPemPaths) Objects.requireNonNull(p, "caPemPath");
            this.trustPemPaths      = List.of(caPemPaths);
            this.trustStorePath     = null;   // clear PKCS12 setting if any
            this.trustStorePassword = null;
            return this;
        }

        /**
         * Set the client key store for mTLS. When set, the client presents
         * its certificate on TLS handshake.
         */
        public Builder keyStore(Path path, char[] password, char[] keyPassword) {
            this.keyStorePath     = Objects.requireNonNull(path, "path");
            this.keyStorePassword = password;
            this.keyPassword      = keyPassword != null ? keyPassword : password;
            return this;
        }

        /** Change key store type. Default {@code "PKCS12"}. */
        public Builder keyStoreType(String type) {
            this.keyStoreType = Objects.requireNonNull(type, "type");
            return this;
        }

        /**
         * Present a client certificate and key in PEM form. Enables mTLS.
         *
         * <p>{@code certChainPem} may contain a single leaf certificate
         * or a concatenated chain (leaf first, then intermediates).
         * {@code privateKeyPem} may be PKCS#8
         * ({@code -----BEGIN PRIVATE KEY-----}, the modern format) or
         * PKCS#1 ({@code -----BEGIN RSA PRIVATE KEY-----}, the legacy
         * format {@code openssl req} still emits by default). Encrypted
         * PKCS#8 is not supported &mdash; convert with
         * {@code openssl pkcs8 -topk8 -nocrypt} first, or use a PKCS12
         * keystore which handles passwords natively.</p>
         *
         * <p>Mutually exclusive with {@link #keyStore(Path, char[], char[])}.</p>
         */
        public Builder keyStorePem(Path certChainPem, Path privateKeyPem) {
            this.keyPemCertChain  = Objects.requireNonNull(certChainPem,  "certChainPem");
            this.keyPemPrivateKey = Objects.requireNonNull(privateKeyPem, "privateKeyPem");
            this.keyStorePath     = null;   // clear PKCS12 setting if any
            this.keyStorePassword = null;
            this.keyPassword      = null;
            return this;
        }

        /** Override the enabled protocols. Default: {@link #DEFAULT_PROTOCOLS}. */
        public Builder enabledProtocols(String... protocols) {
            this.enabledProtocols = List.of(protocols);
            return this;
        }

        /** Override the enabled cipher suites. Default: JVM default (i.e. don't set). */
        public Builder enabledCipherSuites(String... suites) {
            this.enabledCipherSuites = List.of(suites);
            return this;
        }

        /**
         * Toggle hostname verification. Default: {@code true}. Disable only
         * for pinned-cert scenarios where the CN/SAN would not match the
         * connect host (e.g. IP-only connect to a router with a hostname-only
         * cert); document the risk in the caller's config.
         */
        public Builder verifyHostname(boolean on) {
            this.verifyHostname = on;
            return this;
        }

        /** TLS handshake timeout in ms. Default {@value #DEFAULT_HANDSHAKE_TIMEOUT_MS}. */
        public Builder handshakeTimeoutMs(int ms) {
            if (ms < 0) throw new IllegalArgumentException("ms must be >= 0");
            this.handshakeTimeoutMs = ms;
            return this;
        }

        /**
         * When used to build a server-side context, require client
         * certificate authentication. Kept here for symmetry / future
         * reuse; a client-side {@link TlsTransport} ignores this.
         */
        public Builder needClientAuth(boolean on) {
            this.needClientAuth = on;
            return this;
        }

        public TlsConfig build() {
            try {
                if (trustStorePath != null && trustPemPaths != null) {
                    throw new IllegalStateException(
                            "trustStore(Path,char[]) and trustStorePem(Path...) are mutually exclusive");
                }
                if (keyStorePath != null && keyPemCertChain != null) {
                    throw new IllegalStateException(
                            "keyStore(...) and keyStorePem(cert,key) are mutually exclusive");
                }
                TrustManagerFactory tmf = null;
                if (trustStorePath != null) {
                    KeyStore ts = loadKeyStore(trustStorePath, trustStorePassword, trustStoreType);
                    tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(ts);
                } else if (trustPemPaths != null) {
                    KeyStore ts = KeyStore.getInstance("PKCS12");
                    ts.load(null, null);
                    int aliasN = 0;
                    for (Path p : trustPemPaths) {
                        for (java.security.cert.X509Certificate cert : PemLoader.readCertificates(p)) {
                            ts.setCertificateEntry("ca-" + (aliasN++), cert);
                        }
                    }
                    tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(ts);
                }
                KeyManagerFactory kmf = null;
                if (keyStorePath != null) {
                    KeyStore ks = loadKeyStore(keyStorePath, keyStorePassword, keyStoreType);
                    kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    kmf.init(ks, keyPassword);
                } else if (keyPemCertChain != null) {
                    java.util.List<java.security.cert.X509Certificate> chain =
                            PemLoader.readCertificates(keyPemCertChain);
                    java.security.PrivateKey privateKey =
                            PemLoader.readPrivateKey(keyPemPrivateKey);
                    KeyStore ks = KeyStore.getInstance("PKCS12");
                    ks.load(null, null);
                    // In-memory keystore requires SOME password on the key entry per JCA spec;
                    // it's not stored anywhere and never persisted, so any non-null value works.
                    char[] inMemoryPw = new char[0];
                    ks.setKeyEntry("client", privateKey, inMemoryPw,
                            chain.toArray(new java.security.cert.X509Certificate[0]));
                    kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    kmf.init(ks, inMemoryPw);
                }
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(
                        kmf != null ? kmf.getKeyManagers() : null,
                        tmf != null ? tmf.getTrustManagers() : null,
                        new SecureRandom());
                return new TlsConfig(ctx, ctx.getSocketFactory(),
                        enabledProtocols, enabledCipherSuites,
                        verifyHostname, handshakeTimeoutMs, needClientAuth);
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException(
                        "TlsConfig build failed: " + e.getMessage(), e);
            }
        }

        private static KeyStore loadKeyStore(Path path, char[] password, String type)
                throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException {
            KeyStore ks = KeyStore.getInstance(type);
            try (InputStream in = Files.newInputStream(path)) {
                ks.load(in, password);
            }
            return ks;
        }
    }

    // Defensive helpers for consumers that need to null-check credentials.

    static char[] nullSafeClone(char[] pw) {
        return pw == null ? null : Arrays.copyOf(pw, pw.length);
    }

    // Silence unused-import warning on some IDE configurations.
    @SuppressWarnings("unused")
    private static final Class<?> UNUSED_UNRECOVERABLE = UnrecoverableKeyException.class;
}

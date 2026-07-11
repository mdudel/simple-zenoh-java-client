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
package io.mdudel.zenoh.purejava;

import io.mdudel.zenoh.purejava.session.SessionException;
import io.mdudel.zenoh.purejava.session.SessionState;
import io.mdudel.zenoh.purejava.session.ZenohSession;
import io.mdudel.zenoh.purejava.transport.TcpTransport;
import io.mdudel.zenoh.purejava.transport.TlsConfig;
import io.mdudel.zenoh.purejava.transport.TlsTransport;
import io.mdudel.zenoh.purejava.transport.Transport;
import io.mdudel.zenoh.purejava.transport.WsTransport;
import io.mdudel.zenoh.purejava.wire.KeyExpr;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java Zenoh 1.x publisher client. No JNI. No native binaries. Zero
 * runtime dependencies beyond JDK 17.
 *
 * <p>Facade over {@link ZenohSession} + the {@link Transport} implementations
 * ({@link TcpTransport}, {@link TlsTransport}, {@link WsTransport}) that
 * mirrors the shape of the JNI-backed {@code io.mdudel.zenoh.ZenohClient}
 * in the sibling module &mdash; switching between the two at a call site
 * is a one-line change.</p>
 *
 * <h2>Endpoint syntax</h2>
 * <p>The {@code connectEndpoint} builder value accepts either the standard
 * Zenoh {@code proto/host:port} form (matching the JNI publisher) or a full
 * URI:</p>
 * <ul>
 *   <li>{@code tcp/host:port}   &rarr; {@link TcpTransport}</li>
 *   <li>{@code tls/host:port}   &rarr; {@link TlsTransport} (requires TLS builder args)</li>
 *   <li>{@code ws/host:port}    &rarr; {@link WsTransport} plaintext (routes as {@code ws://host:port})</li>
 *   <li>{@code wss/host:port}   &rarr; {@link WsTransport} TLS (routes as {@code wss://host:port})</li>
 *   <li>{@code ws://host:port/path}, {@code wss://host:port/path} &mdash; full URI variants</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>{@link #start()} single-threaded (call once).</li>
 *   <li>{@link #publish(byte[])} safe from any thread &mdash; delegates to
 *       {@link ZenohSession#publish(String, byte[])} which is lock-free
 *       at the session layer.</li>
 *   <li>{@link #close()} idempotent from any thread.</li>
 * </ul>
 */
public final class PureJavaZenohPublisher implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PureJavaZenohPublisher.class.getName());

    // ----- config (immutable) --------------------------------------------
    private final String  connectEndpoint;
    private final String  keyExpr;
    private final String  org;
    private final String  rootCaCertPath;
    private final String  clientCertPath;
    private final String  clientKeyPath;
    private final char[]  keyStorePassword;
    private final boolean verifyHostname;
    private final long    leaseMs;

    // ----- runtime state -------------------------------------------------
    private volatile ZenohSession session;
    private volatile Transport    transport;
    private volatile String       lastError;
    private final AtomicLong sentCount  = new AtomicLong(0);
    private final AtomicLong lastSendMs = new AtomicLong(0);

    private PureJavaZenohPublisher(Builder b) {
        this.connectEndpoint  = nz(b.connectEndpoint);
        this.keyExpr          = (b.keyExpr != null && !b.keyExpr.isEmpty())
                                    ? b.keyExpr : "demo/example/zenoh-java";
        this.org              = nz(b.org);
        this.rootCaCertPath   = nz(b.rootCaCertPath);
        this.clientCertPath   = nz(b.clientCertPath);
        this.clientKeyPath    = nz(b.clientKeyPath);
        this.keyStorePassword = b.keyStorePassword;
        this.verifyHostname   = b.verifyHostname;
        this.leaseMs          = b.leaseMs;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ----- accessors -----------------------------------------------------
    public String  getConnectEndpoint()  { return connectEndpoint; }
    public String  getKeyExpr()          { return keyExpr; }
    public String  getOrg()              { return org; }
    public String  getEffectiveKeyExpr() { return KeyExpr.resolveKey(org, keyExpr); }
    public boolean isActive()            {
        return session != null && session.state() == SessionState.OPEN;
    }
    public long    getSentCount()        { return sentCount.get(); }
    public long    getLastSendMs()       { return lastSendMs.get(); }
    public String  getLastError()        { return lastError; }

    // ----- lifecycle -----------------------------------------------------

    /**
     * Build the transport for {@link #connectEndpoint}, run the four-message
     * INIT/OPEN handshake, and enter the publish-ready state.
     */
    public void start() throws IOException {
        if (isActive()) return;
        LOG.log(Level.INFO,
                "PureJavaZenohPublisher.start() endpoint={0} key={1} effectiveKey={2}"
                        + " org={3} verifyHostname={4} lease={5}ms",
                connectEndpoint, keyExpr, getEffectiveKeyExpr(), org,
                verifyHostname, leaseMs);
        Transport t;
        try {
            t = buildTransport();
        } catch (RuntimeException | IOException e) {
            lastError = "transport build failed: " + e.getMessage();
            throw wrapAsIo(e, lastError);
        }
        ZenohSession s = ZenohSession.builder(t)
                .leaseMs(leaseMs)
                .autoConnect(true)
                .build();
        try {
            s.open();
        } catch (SessionException e) {
            lastError = "session open failed: " + e.getMessage();
            s.close();
            t.close();
            throw new IOException(lastError, e);
        }
        this.transport = t;
        this.session   = s;
    }

    /** Stop the session, close the transport. Idempotent. */
    public void stop() {
        ZenohSession s = session;
        if (s != null) {
            s.close();
            session = null;
        }
        transport = null;
        LOG.log(Level.INFO, "PureJavaZenohPublisher.stop()");
    }

    @Override public void close() { stop(); }

    // ----- publish -------------------------------------------------------

    /** Publish to the base key expression (with any {@code org} prefix). */
    public void publish(byte[] data) throws IOException {
        publish(null, data);
    }

    /**
     * Publish to {@code effectiveKeyExpr/subKey} if {@code subKey} is
     * non-null and non-empty, otherwise to the base key.
     */
    public void publish(String subKey, byte[] data) throws IOException {
        ZenohSession s = session;
        if (s == null || s.state() != SessionState.OPEN) {
            throw new IOException("PureJavaZenohPublisher is not started");
        }
        String effective = getEffectiveKeyExpr();
        String key = (subKey == null || subKey.isEmpty())
                ? effective
                : effective + "/" + subKey;
        try {
            s.publish(key, data);
        } catch (SessionException e) {
            lastError = "publish failed: " + e.getMessage();
            throw new IOException(lastError, e);
        }
        sentCount.incrementAndGet();
        lastSendMs.set(System.currentTimeMillis());
    }

    /** Publish a UTF-8 string with the Zenoh string encoding tag. */
    public void publishString(String subKey, String payload) throws IOException {
        publish(subKey, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ----- endpoint parsing + transport factory --------------------------

    /**
     * Parse {@link #connectEndpoint} into a scheme/host/port triple and
     * return the matching {@link Transport}. Package-private for tests.
     */
    Transport buildTransport() throws IOException {
        if (connectEndpoint.isEmpty()) {
            throw new IOException("connectEndpoint is required");
        }
        String scheme;
        String host;
        int    port;
        String path = "";

        // Two forms accepted:
        //   1. Zenoh classic: proto/host:port     (no colon after proto)
        //   2. URI:           proto://host:port[/path]
        if (connectEndpoint.contains("://")) {
            URI uri = URI.create(connectEndpoint);
            scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            host   = uri.getHost();
            port   = uri.getPort();
            path   = uri.getRawPath() == null ? "" : uri.getRawPath();
        } else {
            int slash = connectEndpoint.indexOf('/');
            if (slash <= 0) {
                throw new IOException("connectEndpoint must be proto/host:port or a URI: "
                        + connectEndpoint);
            }
            scheme = connectEndpoint.substring(0, slash).toLowerCase();
            String hostPort = connectEndpoint.substring(slash + 1);
            int colon = hostPort.indexOf(':');
            if (colon <= 0 || colon == hostPort.length() - 1) {
                throw new IOException("connectEndpoint must contain host:port: "
                        + connectEndpoint);
            }
            host = hostPort.substring(0, colon);
            try { port = Integer.parseInt(hostPort.substring(colon + 1)); }
            catch (NumberFormatException nfe) {
                throw new IOException("invalid port in endpoint: " + connectEndpoint);
            }
        }
        if (host == null || host.isEmpty()) {
            throw new IOException("host missing from endpoint: " + connectEndpoint);
        }
        if (port < 1 || port > 65535) {
            throw new IOException("port out of range in endpoint: " + connectEndpoint);
        }

        return switch (scheme) {
            case "tcp" -> new TcpTransport(host, port);
            case "tls" -> new TlsTransport(host, port, buildTlsConfig());
            case "ws"  -> new WsTransport(URI.create("ws://" + host + ":" + port + path), null);
            case "wss" -> new WsTransport(
                    URI.create("wss://" + host + ":" + port + path), buildTlsConfig());
            default -> throw new IOException("unsupported endpoint scheme '" + scheme
                    + "'; expected one of tcp, tls, ws, wss");
        };
    }

    /**
     * Build a {@link TlsConfig} from the builder's rootCa/clientCert/clientKey
     * settings. Two formats are auto-detected by file extension:
     *
     * <ul>
     *   <li>PEM (extensions {@code .pem} / {@code .crt} / {@code .cer} /
     *       {@code .key}) &mdash; matches the JNI sibling's shape:
     *       {@code rootCa.pem}, {@code client.pem}, {@code client.key}.
     *       No password required.</li>
     *   <li>PKCS12 (extensions {@code .p12} / {@code .pfx}) &mdash; combined
     *       trust store OR combined key store, password from
     *       {@link Builder#keyStorePassword(char[])} (default {@code "changeit"}).</li>
     * </ul>
     *
     * <p>You may mix formats: e.g. a PEM CA + a PKCS12 client keystore.
     * If neither {@code rootCaCertPath} nor {@code clientCertPath} are set,
     * falls back to the JVM default trust store (typically {@code cacerts}).</p>
     */
    private TlsConfig buildTlsConfig() throws IOException {
        TlsConfig.Builder tb = TlsConfig.builder().verifyHostname(verifyHostname);

        // ---- trust store ----------------------------------------------
        if (!rootCaCertPath.isEmpty()) {
            Path p = requireReadable(rootCaCertPath, "rootCaCertPath");
            if (isPem(p)) {
                tb.trustStorePem(p);
            } else if (isPkcs12(p)) {
                tb.trustStore(p, keyStorePassword);
            } else {
                throw new IOException(
                        "rootCaCertPath must end in .pem/.crt/.cer (PEM) or .p12/.pfx (PKCS12): "
                                + rootCaCertPath);
            }
        } else {
            tb.trustSystem();
        }

        // ---- key store (mTLS) -----------------------------------------
        // JNI-compatible shape: clientCertPath + clientKeyPath as PEM.
        // PKCS12 shape: clientCertPath OR clientKeyPath as a single .p12.
        if (!clientCertPath.isEmpty() && !clientKeyPath.isEmpty()) {
            Path cert = requireReadable(clientCertPath, "clientCertPath");
            Path key  = requireReadable(clientKeyPath,  "clientKeyPath");
            if (isPem(cert) && isPem(key)) {
                tb.keyStorePem(cert, key);
            } else if (isPkcs12(cert) && cert.equals(key)) {
                tb.keyStore(cert, keyStorePassword, keyStorePassword);
            } else if (isPkcs12(cert) || isPkcs12(key)) {
                throw new IOException(
                        "for PKCS12 client keystore, set clientCertPath and clientKeyPath "
                                + "to the SAME .p12 file (or leave one empty); PEM keys need "
                                + "BOTH clientCertPath (.pem) AND clientKeyPath (.key/.pem) set");
            } else {
                throw new IOException(
                        "clientCertPath / clientKeyPath must be PEM pair (.pem/.crt/.cer + .pem/.key) "
                                + "or PKCS12 (.p12/.pfx)");
            }
        } else if (!clientCertPath.isEmpty() || !clientKeyPath.isEmpty()) {
            // Only ONE side set -- must be PKCS12 (combined keystore) or the user
            // forgot the other half.
            String path = !clientCertPath.isEmpty() ? clientCertPath : clientKeyPath;
            Path p = requireReadable(path,
                    !clientCertPath.isEmpty() ? "clientCertPath" : "clientKeyPath");
            if (isPkcs12(p)) {
                tb.keyStore(p, keyStorePassword, keyStorePassword);
            } else {
                throw new IOException(
                        "PEM client authentication requires BOTH clientCertPath (.pem) AND "
                                + "clientKeyPath (.key or .pem) to be set; got only " + path);
            }
        }
        return tb.build();
    }

    private static Path requireReadable(String pathStr, String argName) throws IOException {
        Path p = Paths.get(pathStr);
        if (!Files.isReadable(p)) {
            throw new IOException(argName + " not readable: " + pathStr);
        }
        return p;
    }

    private static boolean isPem(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".pem") || n.endsWith(".crt") || n.endsWith(".cer") || n.endsWith(".key");
    }

    private static boolean isPkcs12(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".p12") || n.endsWith(".pfx");
    }

    private static IOException wrapAsIo(Throwable e, String message) {
        if (e instanceof IOException io) return io;
        return new IOException(message, e);
    }

    // ----- builder -------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    /**
     * Fluent builder mirroring the shape of
     * {@code io.mdudel.zenoh.ZenohClient.Builder} so switching between the
     * JNI-backed and pure-Java publishers is a one-line change at the call
     * site.
     */
    public static final class Builder {
        private String  connectEndpoint  = "";
        private String  keyExpr;
        private String  org              = "";
        private String  rootCaCertPath   = "";
        private String  clientCertPath   = "";
        private String  clientKeyPath    = "";
        private char[]  keyStorePassword = "changeit".toCharArray();
        private boolean verifyHostname   = true;
        private long    leaseMs          = ZenohSession.DEFAULT_LEASE_MS;

        public Builder connectEndpoint(String v) { this.connectEndpoint = v; return this; }
        public Builder keyExpr(String v)         { this.keyExpr         = v; return this; }
        public Builder org(String v)             { this.org             = v; return this; }
        public Builder rootCaCertPath(String v)  { this.rootCaCertPath  = v; return this; }
        public Builder clientCertPath(String v)  { this.clientCertPath  = v; return this; }
        public Builder clientKeyPath(String v)   { this.clientKeyPath   = v; return this; }
        public Builder keyStorePassword(char[] v){ this.keyStorePassword = v; return this; }
        public Builder verifyHostname(boolean v) { this.verifyHostname  = v; return this; }
        public Builder leaseMs(long v)           { this.leaseMs         = v; return this; }

        public PureJavaZenohPublisher build() { return new PureJavaZenohPublisher(this); }
    }

    // ----- CLI ----------------------------------------------------------

    /**
     * CLI: publish one payload and exit. Same defaults as the JNI sibling.
     *
     * <p>Usage:</p>
     * <pre>
     * java -jar target/java-zenoh-publisher-pure-*.jar
     * java -jar target/java-zenoh-publisher-pure-*.jar tcp/router:7447 my/key
     * java -jar target/java-zenoh-publisher-pure-*.jar tcp/router:7447 my/key "hello"
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String key      = args.length > 1 ? args[1] : "demo/greeting";
        String payload  = args.length > 2 ? args[2] : "hello, zenoh from pure-Java!";

        System.out.println("[pure-java-publisher] endpoint=" + endpoint + " key=" + key);
        try (PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
                .connectEndpoint(endpoint)
                .keyExpr(key)
                .build()) {
            pub.start();
            pub.publishString(null, payload);
            System.out.println("[pure-java-publisher] published " + payload.length()
                    + " chars to key=" + key + " via " + endpoint
                    + " (sent=" + pub.getSentCount() + ")");
        }
    }
}

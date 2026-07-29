/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.connection;

import io.mdudel.zenoh.purejava.connection.auth.ZenohRsaAuthenticator;
import io.mdudel.zenoh.purejava.session.Sample;
import io.mdudel.zenoh.purejava.session.SessionException;
import io.mdudel.zenoh.purejava.session.SessionState;
import io.mdudel.zenoh.purejava.session.Subscription;
import io.mdudel.zenoh.purejava.session.ZenohSession;
import io.mdudel.zenoh.purejava.transport.TcpTransport;
import io.mdudel.zenoh.purejava.transport.TlsConfig;
import io.mdudel.zenoh.purejava.transport.TlsTransport;
import io.mdudel.zenoh.purejava.transport.Transport;
import io.mdudel.zenoh.purejava.transport.WsTransport;
import io.mdudel.zenoh.purejava.wire.Encoding;
import io.mdudel.zenoh.purejava.wire.KeyExpr;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Combined publish/subscribe facade backed by one Zenoh session and one
 * transport connection. This package is additive; the original publisher and
 * subscriber facades are intentionally unchanged.
 */
public final class ZenohConnection implements AutoCloseable {
    private final String endpoint;
    private final String org;
    private final String rootCaCertPath;
    private final String clientCertPath;
    private final String clientKeyPath;
    private final char[] keyStorePassword;
    private final boolean verifyHostname;
    private final long leaseMs;
    private final boolean localRouting;
    private final Path zenohAuthPublicKey;
    private final Path zenohAuthPrivateKey;
    private final List<Path> trustedRouterPublicKeys;

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicLong sentCount = new AtomicLong();
    private volatile ZenohSession session;
    private volatile String lastError;

    private ZenohConnection(Builder b) {
        endpoint = nonNull(b.endpoint);
        org = nonNull(b.org);
        rootCaCertPath = nonNull(b.rootCaCertPath);
        clientCertPath = nonNull(b.clientCertPath);
        clientKeyPath = nonNull(b.clientKeyPath);
        keyStorePassword = b.keyStorePassword == null ? null : b.keyStorePassword.clone();
        verifyHostname = b.verifyHostname;
        leaseMs = b.leaseMs;
        localRouting = b.localRouting;
        zenohAuthPublicKey = b.zenohAuthPublicKey;
        zenohAuthPrivateKey = b.zenohAuthPrivateKey;
        trustedRouterPublicKeys = List.copyOf(b.trustedRouterPublicKeys);
    }

    public static Builder builder() { return new Builder(); }

    public synchronized void start() throws IOException {
        if (isActive()) return;
        Transport transport;
        try {
            transport = buildTransport();
        } catch (RuntimeException | IOException e) {
            lastError = "transport build failed: " + e.getMessage();
            throw e instanceof IOException io ? io : new IOException(lastError, e);
        }
        ZenohSession.Builder sessionBuilder = ZenohSession.builder(transport)
                .leaseMs(leaseMs).localRouting(localRouting).autoConnect(true);
        if (zenohAuthPublicKey != null) {
            sessionBuilder.authenticator(new ZenohRsaAuthenticator(
                    zenohAuthPublicKey, zenohAuthPrivateKey,
                    trustedRouterPublicKeys.toArray(Path[]::new)));
        }
        ZenohSession candidate = sessionBuilder.build();
        try {
            candidate.open();
            session = candidate;
        } catch (SessionException e) {
            candidate.close();
            lastError = "session open failed: " + e.getMessage();
            throw new IOException(lastError, e);
        }
    }

    public boolean isActive() {
        ZenohSession current = session;
        return current != null && current.state() == SessionState.OPEN;
    }

    public String getLastError() { return lastError; }
    public long getSentCount() { return sentCount.get(); }
    public long getReceivedCount() {
        long count = 0;
        for (Subscription sub : subscriptions) count += sub.receivedCount();
        return count;
    }

    public void publish(String keyExpr, byte[] payload) throws IOException {
        publish(keyExpr, payload, Encoding.EMPTY);
    }

    public void publish(String keyExpr, byte[] payload, Encoding encoding) throws IOException {
        Objects.requireNonNull(keyExpr, "keyExpr");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(encoding, "encoding");
        ZenohSession current = requireOpen();
        try {
            current.publish(KeyExpr.resolveKey(org, keyExpr), payload, encoding);
            sentCount.incrementAndGet();
        } catch (SessionException e) {
            lastError = "publish failed: " + e.getMessage();
            throw new IOException(lastError, e);
        }
    }

    public void publishString(String keyExpr, String payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        // Preserve PureJavaZenohPublisher's established wire behavior: UTF-8
        // payload bytes with Encoding.EMPTY.
        publish(keyExpr, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Publish UTF-8 with an explicit Zenoh string encoding tag. */
    public void publishStringEncoded(String keyExpr, String payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        publish(keyExpr, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Encoding.of(Encoding.ID_ZENOH_STRING));
    }

    public Subscription subscribe(String keyExpr) throws IOException {
        Objects.requireNonNull(keyExpr, "keyExpr");
        try {
            Subscription sub = requireOpen().declareSubscriber(
                    KeyExpr.of(KeyExpr.resolveKey(org, keyExpr)));
            subscriptions.add(sub);
            return sub;
        } catch (SessionException e) {
            lastError = "subscribe failed: " + e.getMessage();
            throw new IOException(lastError, e);
        }
    }

    public Subscription subscribe(String keyExpr, Consumer<Sample> callback) throws IOException {
        Objects.requireNonNull(callback, "callback");
        Subscription sub = subscribe(keyExpr);
        try {
            sub.forEach(callback);
            return sub;
        } catch (RuntimeException e) {
            sub.close();
            subscriptions.remove(sub);
            throw e;
        }
    }

    @Override public synchronized void close() {
        for (Subscription sub : subscriptions) sub.close();
        subscriptions.clear();
        ZenohSession current = session;
        session = null;
        if (current != null) current.close();
    }

    private ZenohSession requireOpen() throws IOException {
        ZenohSession current = session;
        if (current == null || current.state() != SessionState.OPEN) {
            throw new IOException("ZenohConnection is not started");
        }
        return current;
    }

    private Transport buildTransport() throws IOException {
        if (endpoint.isEmpty()) throw new IOException("connectEndpoint is required");
        String scheme;
        String host;
        int port;
        String path = "";
        if (endpoint.contains("://")) {
            URI uri = URI.create(endpoint);
            scheme = nonNull(uri.getScheme()).toLowerCase(Locale.ROOT);
            host = uri.getHost();
            port = uri.getPort();
            path = nonNull(uri.getRawPath());
        } else {
            int slash = endpoint.indexOf('/');
            if (slash <= 0) throw new IOException("endpoint must be proto/host:port or a URI: " + endpoint);
            scheme = endpoint.substring(0, slash).toLowerCase(Locale.ROOT);
            String hostPort = endpoint.substring(slash + 1);
            int colon = hostPort.lastIndexOf(':');
            if (colon <= 0 || colon == hostPort.length() - 1) {
                throw new IOException("endpoint must contain host:port: " + endpoint);
            }
            host = hostPort.substring(0, colon);
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            try { port = Integer.parseInt(hostPort.substring(colon + 1)); }
            catch (NumberFormatException e) { throw new IOException("invalid port in endpoint: " + endpoint, e); }
        }
        if (host == null || host.isEmpty()) throw new IOException("host missing from endpoint: " + endpoint);
        if (port < 1 || port > 65535) throw new IOException("port out of range in endpoint: " + endpoint);
        return switch (scheme) {
            case "tcp" -> new TcpTransport(host, port);
            case "tls" -> new TlsTransport(host, port, buildTlsConfig());
            case "ws" -> new WsTransport(URI.create("ws://" + uriHost(host) + ":" + port + path), null);
            case "wss" -> new WsTransport(URI.create("wss://" + uriHost(host) + ":" + port + path), buildTlsConfig());
            default -> throw new IOException("unsupported endpoint scheme '" + scheme + "'");
        };
    }

    private TlsConfig buildTlsConfig() throws IOException {
        TlsConfig.Builder tls = TlsConfig.builder().verifyHostname(verifyHostname);
        if (rootCaCertPath.isEmpty()) tls.trustSystem();
        else {
            Path ca = readable(rootCaCertPath, "rootCaCertPath");
            if (isPem(ca)) tls.trustStorePem(ca);
            else if (isPkcs12(ca)) tls.trustStore(ca, keyStorePassword);
            else throw new IOException("unsupported CA format: " + ca);
        }
        if (!clientCertPath.isEmpty() && !clientKeyPath.isEmpty()) {
            Path cert = readable(clientCertPath, "clientCertPath");
            Path key = readable(clientKeyPath, "clientKeyPath");
            if (isPem(cert) && isPem(key)) tls.keyStorePem(cert, key);
            else if (isPkcs12(cert) && cert.equals(key)) tls.keyStore(cert, keyStorePassword, keyStorePassword);
            else throw new IOException("client identity must be a PEM cert/key pair or the same PKCS12 file");
        } else if (!clientCertPath.isEmpty() || !clientKeyPath.isEmpty()) {
            Path keyStore = readable(clientCertPath.isEmpty() ? clientKeyPath : clientCertPath, "clientKeyStore");
            if (!isPkcs12(keyStore)) throw new IOException("PEM client identity requires both certificate and key");
            tls.keyStore(keyStore, keyStorePassword, keyStorePassword);
        }
        return tls.build();
    }

    private static Path readable(String value, String name) throws IOException {
        Path path = Path.of(value);
        if (!Files.isReadable(path)) throw new IOException(name + " not readable: " + value);
        return path;
    }
    private static boolean isPem(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".pem") || n.endsWith(".crt") || n.endsWith(".cer") || n.endsWith(".key");
    }
    private static boolean isPkcs12(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".p12") || n.endsWith(".pfx");
    }
    private static String uriHost(String host) { return host.indexOf(':') >= 0 ? "[" + host + "]" : host; }
    private static String nonNull(String value) { return value == null ? "" : value; }

    public static final class Builder {
        private String endpoint = "";
        private String org = "";
        private String rootCaCertPath = "";
        private String clientCertPath = "";
        private String clientKeyPath = "";
        private char[] keyStorePassword = "changeit".toCharArray();
        private boolean verifyHostname = true;
        private long leaseMs = ZenohSession.DEFAULT_LEASE_MS;
        private boolean localRouting = true;
        private Path zenohAuthPublicKey;
        private Path zenohAuthPrivateKey;
        private List<Path> trustedRouterPublicKeys = List.of();

        private Builder() {}
        public Builder connectEndpoint(String value) { endpoint = value; return this; }
        public Builder org(String value) { org = value; return this; }
        public Builder rootCaCertPath(String value) { rootCaCertPath = value; return this; }
        public Builder clientCertPath(String value) { clientCertPath = value; return this; }
        public Builder clientKeyPath(String value) { clientKeyPath = value; return this; }
        public Builder keyStorePassword(char[] value) {
            keyStorePassword = value == null ? null : Arrays.copyOf(value, value.length);
            return this;
        }
        public Builder verifyHostname(boolean value) { verifyHostname = value; return this; }
        public Builder leaseMs(long value) { leaseMs = value; return this; }
        /** Same-session publications are delivered locally by default, matching official Zenoh semantics. */
        public Builder localRouting(boolean value) { localRouting = value; return this; }

        /**
         * Enable Zenoh 1.9 RSA pubkey transport authentication. This is
         * independent of TLS/mTLS. Trusted router keys are optional; when
         * supplied, the router's handshake key must match one of them.
         */
        public Builder zenohRsaAuthentication(Path publicKey, Path privateKey,
                                               Path... trustedRouterKeys) {
            zenohAuthPublicKey = Objects.requireNonNull(publicKey, "publicKey");
            zenohAuthPrivateKey = Objects.requireNonNull(privateKey, "privateKey");
            trustedRouterPublicKeys = trustedRouterKeys == null
                    ? List.of() : List.of(trustedRouterKeys.clone());
            for (Path path : trustedRouterPublicKeys) Objects.requireNonNull(path, "trustedRouterKey");
            return this;
        }

        /** Disable Zenoh transport authentication without affecting TLS. */
        public Builder noZenohAuthentication() {
            zenohAuthPublicKey = null;
            zenohAuthPrivateKey = null;
            trustedRouterPublicKeys = List.of();
            return this;
        }

        public ZenohConnection build() {
            if ((zenohAuthPublicKey == null) != (zenohAuthPrivateKey == null)) {
                throw new IllegalStateException("Zenoh RSA authentication requires both public and private keys");
            }
            return new ZenohConnection(this);
        }
    }
}

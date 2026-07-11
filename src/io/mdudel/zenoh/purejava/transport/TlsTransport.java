/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * TLS + optional mTLS implementation of {@link Transport} for the
 * Zenoh 1.x publisher.
 *
 * <p>Same 2-byte little-endian stream framing as {@link TcpTransport}
 * &mdash; TLS is just an encrypted, authenticated pipe under the same
 * wire protocol. All the reader-thread, write-lock, and idempotent-close
 * behaviour comes from {@link AbstractStreamTransport}; this class
 * only supplies the socket via {@link #openSocket()}.</p>
 *
 * <p>Handshake behaviour:</p>
 * <ul>
 *   <li>The plain socket connect timeout comes from
 *       {@link #setConnectTimeoutMs(int)} (default 10 s).</li>
 *   <li>The TLS handshake timeout comes from
 *       {@link TlsConfig#handshakeTimeoutMs()} (default 15 s). It is
 *       applied via a temporary {@code setSoTimeout} around
 *       {@link SSLSocket#startHandshake()} then cleared, so blocking
 *       {@code read()} calls in the reader thread do not time out on
 *       quiet-server keep-alive intervals.</li>
 *   <li>Hostname verification is on by default. Disable via
 *       {@link TlsConfig.Builder#verifyHostname(boolean)} only for
 *       pinned-cert scenarios &mdash; document the risk.</li>
 *   <li>Client certificate presentation (mTLS) is opt-in via
 *       {@link TlsConfig.Builder#keyStore(java.nio.file.Path, char[], char[])}.</li>
 * </ul>
 *
 * <p>ALPN is not set. Zenoh 1.x over TLS is a raw framed stream, not
 * HTTP/2 or anything else that negotiates a protocol at the ALPN
 * layer, so leaving it unset is correct.</p>
 */
public final class TlsTransport extends AbstractStreamTransport {

    /** Default plain-socket connect timeout (ms). */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;

    private final String    host;
    private final int       port;
    private final TlsConfig tls;
    private       int       connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private       boolean   tcpNoDelay       = true;
    private       boolean   configLocked     = false;

    public TlsTransport(String host, int port, TlsConfig tls) {
        this.host = Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.port = port;
        this.tls  = Objects.requireNonNull(tls, "tls");
    }

    /** Set the plain-socket connect timeout in ms. Must be called before {@link #connect()}. */
    public TlsTransport setConnectTimeoutMs(int ms) {
        if (configLocked) {
            throw new IllegalStateException("connectTimeoutMs must be set before connect()");
        }
        if (ms < 0) throw new IllegalArgumentException("ms must be >= 0");
        this.connectTimeoutMs = ms;
        return this;
    }

    /** Enable / disable Nagle. Default: {@code true} (Nagle disabled). Must be called before {@link #connect()}. */
    public TlsTransport setTcpNoDelay(boolean on) {
        if (configLocked) {
            throw new IllegalStateException("tcpNoDelay must be set before connect()");
        }
        this.tcpNoDelay = on;
        return this;
    }

    @Override
    protected Socket openSocket() throws IOException {
        configLocked = true;

        // Two-step handshake: plain TCP connect first, then layered SSL over that.
        // This gives us a distinct connect-timeout vs handshake-timeout knob and
        // lets us apply setSoTimeout for the handshake only.
        Socket plain = new Socket();
        SSLSocket sslSocket = null;
        try {
            plain.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            plain.setTcpNoDelay(tcpNoDelay);

            sslSocket = (SSLSocket) tls.socketFactory().createSocket(
                    plain, host, port, /* autoClose = */ true);

            SSLParameters params = sslSocket.getSSLParameters();
            params.setProtocols(tls.enabledProtocols().toArray(new String[0]));
            if (tls.enabledCipherSuites() != null) {
                params.setCipherSuites(tls.enabledCipherSuites().toArray(new String[0]));
            }
            if (tls.verifyHostname()) {
                // "HTTPS" is the JDK-standard identification algorithm that checks
                // the peer cert's SAN (or CN as fallback) against the connect host.
                params.setEndpointIdentificationAlgorithm("HTTPS");
            } else {
                params.setEndpointIdentificationAlgorithm(null);
            }
            sslSocket.setSSLParameters(params);
            sslSocket.setUseClientMode(true);

            // Apply the handshake timeout only while startHandshake() blocks.
            int prevTimeout = sslSocket.getSoTimeout();
            sslSocket.setSoTimeout(tls.handshakeTimeoutMs());
            try {
                sslSocket.startHandshake();
            } finally {
                sslSocket.setSoTimeout(prevTimeout);
            }
            return sslSocket;
        } catch (IOException e) {
            if (sslSocket != null) {
                try { sslSocket.close(); } catch (IOException ignored) {}
            } else {
                try { plain.close(); } catch (IOException ignored) {}
            }
            throw e;
        }
    }

    @Override
    protected String readerThreadTag() { return "tls-" + host + ":" + port; }

    @Override
    public String describe() { return "tls/" + host + ":" + port; }
}

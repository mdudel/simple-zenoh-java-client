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

/**
 * Plain-TCP implementation of {@link Transport} for the Zenoh 1.x
 * publisher.
 *
 * <p>Inherits the reader thread, write lock, receive inbox, and
 * idempotent-close protocol from {@link AbstractStreamTransport};
 * this class only supplies the socket via {@link #openSocket()}.</p>
 *
 * <p>No TCP-level keepalive by default (Zenoh handles this at the
 * session layer via {@code KeepAlive} messages, added in Turn D).
 * TCP_NODELAY on by default because Zenoh already batches into
 * frames and OS-level Nagle buffering is redundant; toggle via
 * {@link #setTcpNoDelay(boolean)} if latency measurements ever
 * justify the alternative.</p>
 */
public final class TcpTransport extends AbstractStreamTransport {

    /** Default socket connect timeout (ms). Configurable via setter. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;

    private final String  host;
    private final int     port;
    private       int     connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private       boolean tcpNoDelay       = true;
    private       boolean configLocked     = false;

    public TcpTransport(String host, int port) {
        this.host = Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.port = port;
    }

    /** Set the socket-level connect timeout in ms. Must be called before {@link #connect()}. */
    public TcpTransport setConnectTimeoutMs(int ms) {
        if (configLocked) {
            throw new IllegalStateException("connectTimeoutMs must be set before connect()");
        }
        if (ms < 0) throw new IllegalArgumentException("ms must be >= 0");
        this.connectTimeoutMs = ms;
        return this;
    }

    /** Enable / disable Nagle. Default: {@code true} (Nagle disabled). Must be called before {@link #connect()}. */
    public TcpTransport setTcpNoDelay(boolean on) {
        if (configLocked) {
            throw new IllegalStateException("tcpNoDelay must be set before connect()");
        }
        this.tcpNoDelay = on;
        return this;
    }

    @Override
    protected Socket openSocket() throws IOException {
        configLocked = true;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setTcpNoDelay(tcpNoDelay);
            return s;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    @Override
    protected String readerThreadTag() { return "tcp-" + host + ":" + port; }

    @Override
    public String describe() { return "tcp/" + host + ":" + port; }
}

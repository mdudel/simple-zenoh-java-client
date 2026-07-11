/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.transport;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * WebSocket implementation of {@link Transport} for the Zenoh 1.x
 * publisher, backed by the JDK's built-in {@link java.net.http.WebSocket}
 * (JDK 11+). Supports both {@code ws://} and {@code wss://} URIs; the
 * latter requires a {@link TlsConfig} to source an {@link javax.net.ssl.SSLContext}.
 *
 * <p><b>Framing.</b> WebSocket is message-oriented: each
 * {@link WebSocket#sendBinary(ByteBuffer, boolean) sendBinary(buf, true)}
 * emits one binary frame on the wire and each
 * {@link java.net.http.WebSocket.Listener#onBinary onBinary} callback
 * receives one binary frame. Because of that, this transport does
 * <b>not</b> use {@link StreamFramer} &mdash; the WebSocket wire already
 * delimits messages. Callers still see whole batches in and out via
 * the {@link Transport} SPI, exactly as they do with
 * {@link TcpTransport} or {@link TlsTransport}.</p>
 *
 * <p>WebSocket-level message fragmentation (an
 * {@link java.net.http.WebSocket.Listener#onBinary onBinary} call with
 * {@code last=false}) is handled transparently: fragments are
 * accumulated into a temporary buffer and only delivered to the
 * inbox once the {@code FIN} bit is observed. The accumulated size
 * is capped at {@link StreamFramer#MAX_FRAME_BYTES} to match the
 * Zenoh wire cap.</p>
 *
 * <p>Threading:</p>
 * <ul>
 *   <li>{@link #send(byte[])} calls
 *       {@link WebSocket#sendBinary(ByteBuffer, boolean)} and blocks
 *       on the returned {@link CompletableFuture} under a per-instance
 *       monitor, so concurrent publishers serialise on the same
 *       WebSocket exactly as they do on the same TCP socket.</li>
 *   <li>Receive callbacks come in on the JDK
 *       {@link HttpClient}'s internal executor and delegate to
 *       {@link #deliver(byte[])}. The caller thread that calls
 *       {@link #receive(long, TimeUnit)} still blocks on the inbox.</li>
 * </ul>
 *
 * <p><b>Zero third-party dependencies</b>. Everything is JDK stdlib.</p>
 */
public final class WsTransport extends AbstractTransport {

    /** Default {@code HttpClient} connect timeout (ms). */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;

    /** Default WebSocket handshake / send / close await timeout (ms). */
    public static final int DEFAULT_OPERATION_TIMEOUT_MS = 15_000;

    private final URI       uri;
    private final TlsConfig tls;             // null iff scheme=ws
    private       int       connectTimeoutMs   = DEFAULT_CONNECT_TIMEOUT_MS;
    private       int       operationTimeoutMs = DEFAULT_OPERATION_TIMEOUT_MS;
    private       boolean   configLocked       = false;

    private volatile HttpClient httpClient;
    private volatile WebSocket  webSocket;
    private final    Object     writeLock = new Object();
    private final    Object     buildLock = new Object();

    // Fragment accumulator: reused across fragmented onBinary callbacks.
    // Guarded by the JDK dispatch guarantee that a listener sees callbacks
    // in order and never concurrently, so no extra lock is needed.
    private byte[] fragBuf;

    /**
     * @param uri {@code ws://host:port} or {@code wss://host:port} (path optional; Zenoh 1.x uses root)
     * @param tls required for {@code wss}; must be null for {@code ws}
     */
    public WsTransport(URI uri, TlsConfig tls) {
        this.uri = Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean secure = "wss".equals(scheme);
        boolean plain  = "ws".equals(scheme);
        if (!secure && !plain) {
            throw new IllegalArgumentException(
                    "scheme must be ws or wss: " + uri);
        }
        if (secure && tls == null) {
            throw new IllegalArgumentException(
                    "wss:// requires a non-null TlsConfig");
        }
        if (plain && tls != null) {
            throw new IllegalArgumentException(
                    "ws:// must be built with a null TlsConfig; use wss:// for TLS");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("uri must include a host: " + uri);
        }
        if (uri.getPort() < 1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("uri must include a valid port: " + uri);
        }
        this.tls = tls;
    }

    /** Underlying HTTP connect timeout in ms. Must be called before {@link #connect()}. */
    public WsTransport setConnectTimeoutMs(int ms) {
        if (configLocked) throw new IllegalStateException("must be set before connect()");
        if (ms < 0) throw new IllegalArgumentException("ms must be >= 0");
        this.connectTimeoutMs = ms;
        return this;
    }

    /** Await-timeout for handshake, send, and close operations. Must be called before {@link #connect()}. */
    public WsTransport setOperationTimeoutMs(int ms) {
        if (configLocked) throw new IllegalStateException("must be set before connect()");
        if (ms < 0) throw new IllegalArgumentException("ms must be >= 0");
        this.operationTimeoutMs = ms;
        return this;
    }

    @Override
    protected void doConnect() throws TransportException {
        configLocked = true;
        HttpClient.Builder cb = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs));
        if (tls != null) {
            cb.sslContext(tls.sslContext());
        }
        HttpClient client;
        synchronized (buildLock) {
            client = cb.build();
            this.httpClient = client;
        }
        try {
            WebSocket ws = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(operationTimeoutMs))
                    .buildAsync(uri, new Listener())
                    .get(operationTimeoutMs, TimeUnit.MILLISECONDS);
            this.webSocket = ws;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TransportException("interrupted during WebSocket handshake to " + describe(), ie);
        } catch (TimeoutException te) {
            throw new TransportException(
                    "WebSocket handshake timed out to " + describe()
                            + " after " + operationTimeoutMs + " ms", te);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            throw new TransportException(
                    "connect failed to " + describe() + ": " + cause.getMessage(), cause);
        }
    }

    @Override
    protected void doSend(byte[] batch) throws TransportException {
        WebSocket ws = webSocket;
        if (ws == null) {
            throw new TransportException("send on closed transport " + describe());
        }
        synchronized (writeLock) {
            if (!isOpen()) {
                throw new TransportException("send on closed transport " + describe());
            }
            try {
                ws.sendBinary(ByteBuffer.wrap(batch), /* last = */ true)
                        .toCompletableFuture()
                        .get(operationTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TransportException("interrupted during sendBinary on " + describe(), ie);
            } catch (TimeoutException te) {
                close();
                throw new TransportException(
                        "sendBinary timed out on " + describe()
                                + " after " + operationTimeoutMs + " ms", te);
            } catch (ExecutionException ee) {
                close();
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                throw new TransportException(
                        "send failed on " + describe() + ": " + cause.getMessage(), cause);
            }
        }
    }

    @Override
    protected void doClose() {
        WebSocket ws = webSocket;
        if (ws != null && !ws.isOutputClosed()) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
                        .toCompletableFuture()
                        .get(operationTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
                // Best-effort close notification. Fall through to abort.
            }
            ws.abort();
        }
        // HttpClient itself has no explicit close prior to JDK 21; letting it go
        // out of scope releases its internal selector threads once daemon.
        this.webSocket  = null;
        this.httpClient = null;
    }

    @Override public String describe() {
        return uri.getScheme() + "/" + uri.getHost() + ":" + uri.getPort()
                + (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "" : uri.getRawPath());
    }

    // ---- Listener bridge (JDK async → AbstractTransport blocking inbox) ----

    private final class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            // Ask for the first message; JDK WebSocket uses explicit demand.
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            int len = data.remaining();
            byte[] chunk = new byte[len];
            data.get(chunk);
            if (last && fragBuf == null) {
                // Whole message in one frame (the common case).
                deliver(chunk);
            } else {
                // Fragmented: accumulate.
                if (fragBuf == null) {
                    fragBuf = chunk;
                } else {
                    int total = fragBuf.length + chunk.length;
                    if (total > StreamFramer.MAX_FRAME_BYTES) {
                        reportReaderError(new IOException(
                                "fragmented WebSocket message exceeds "
                                        + StreamFramer.MAX_FRAME_BYTES + " bytes"));
                        WsTransport.this.close();
                        return null;
                    }
                    byte[] combined = new byte[total];
                    System.arraycopy(fragBuf, 0, combined, 0, fragBuf.length);
                    System.arraycopy(chunk,   0, combined, fragBuf.length, chunk.length);
                    fragBuf = combined;
                }
                if (last) {
                    deliver(fragBuf);
                    fragBuf = null;
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // Zenoh is binary-only. A text frame is a server-side bug; log-then-close.
            reportReaderError(new IOException(
                    "unexpected text frame on Zenoh WebSocket: " + data));
            WsTransport.this.close();
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // Clean close from the peer.
            reportReaderError(new EOFException(
                    "WebSocket closed by peer: status=" + statusCode + " reason=" + reason));
            WsTransport.this.close();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            reportReaderError(error);
            WsTransport.this.close();
        }
    }

    // Silence unused-import warnings under strict compilers.
    @SuppressWarnings("unused")
    private static final Class<?> UNUSED_CE = CompletionException.class;
    @SuppressWarnings("unused")
    private static final Class<?> UNUSED_CF = CompletableFuture.class;
}

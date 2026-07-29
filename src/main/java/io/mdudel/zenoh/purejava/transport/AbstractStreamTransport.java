/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.transport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared skeleton for all <b>stream-oriented</b> {@link Transport}
 * implementations (TCP, TLS). Subclasses supply the socket via
 * {@link #openSocket()}; this class owns the reader thread, the write
 * lock, and the socket-shutdown-first close protocol on top of the
 * lifecycle skeleton provided by {@link AbstractTransport}.
 *
 * <p>Framing is delegated to {@link StreamFramer}, which enforces the
 * 2-byte little-endian length prefix and the
 * {@link StreamFramer#MAX_FRAME_BYTES} hard wire cap.</p>
 *
 * <p>Message-oriented transports (WebSocket) do NOT extend this class
 * &mdash; WebSocket already delimits messages so {@link StreamFramer}
 * is not applicable. They extend {@link AbstractTransport} directly.</p>
 *
 * <p>Threading contract inherited by every stream subclass:</p>
 * <ul>
 *   <li>{@link #doSend(byte[])} runs under a per-instance monitor so
 *       concurrent publishers serialise cleanly on the same socket.</li>
 *   <li>The reader thread is a daemon; it never blocks JVM shutdown.</li>
 *   <li>{@link #doClose()} shuts input FIRST so the reader unblocks
 *       with {@link EOFException} or {@link SocketException}, then
 *       joins with a short timeout. Input-first is deliberate &mdash;
 *       closing the socket without shutting input first can leave the
 *       reader blocked on some Linux + JDK combinations.</li>
 * </ul>
 */
public abstract class AbstractStreamTransport extends AbstractTransport {

    /** Reader-thread stop-join timeout on close (ms). */
    private static final int READER_JOIN_TIMEOUT_MS = 2_000;

    private final Object writeLock = new Object();
    private final AtomicBoolean readerRunning = new AtomicBoolean(false);

    private volatile Socket       socket;
    private volatile OutputStream out;
    private volatile InputStream  in;
    private volatile Thread       readerThread;

    /**
     * Open the underlying stream socket. Called exactly once from
     * {@link #doConnect()}. Implementations are responsible for connect
     * timeout, TCP tunables, TLS handshake, and any other transport-specific
     * setup. Must return a fully connected socket.
     */
    protected abstract Socket openSocket() throws IOException;

    /** Reader-thread name suffix; typically the endpoint (e.g. {@code "tcp-127.0.0.1:7447"}). */
    protected abstract String readerThreadTag();

    @Override
    protected final void doConnect() throws TransportException {
        Socket s;
        try {
            s = openSocket();
            this.out    = new BufferedOutputStream(s.getOutputStream());
            this.in     = new BufferedInputStream(s.getInputStream());
            this.socket = s;
        } catch (IOException e) {
            throw new TransportException(
                    "connect failed to " + describe() + ": " + e.getMessage(), e);
        }
        Thread t = new Thread(this::readerLoop, "zenoh-" + readerThreadTag() + "-reader");
        t.setDaemon(true);
        this.readerThread = t;
        readerRunning.set(true);
        t.start();
    }

    @Override
    protected final void doSend(byte[] batch) throws TransportException {
        synchronized (writeLock) {
            // Re-check under lock: close() might have raced.
            if (!isOpen()) {
                throw new TransportException("send on closed transport " + describe());
            }
            try {
                StreamFramer.writeFrame(out, batch);
                out.flush();
            } catch (IOException e) {
                // Send failure is always fatal for the link.
                close();
                throw new TransportException("send failed on " + describe(), e);
            }
        }
    }

    // ---- internals ----------------------------------------------------

    private void readerLoop() {
        try {
            while (isOpen()) {
                byte[] batch;
                try {
                    batch = StreamFramer.readFrame(in);
                } catch (EOFException eof) {
                    reportReaderError(eof);
                    return;
                } catch (SocketException se) {
                    // Local close() shuts input which surfaces here; treat as clean.
                    if (!isOpen()) return;
                    reportReaderError(se);
                    return;
                } catch (IOException ioe) {
                    reportReaderError(ioe);
                    return;
                }
                deliver(batch);
            }
        } finally {
            readerRunning.set(false);
            // Any exit from the loop implies the link is down.
            close();
        }
    }

    @Override
    protected final void doClose() {
        Socket s = socket;
        if (s != null) {
            // Shut input first so the reader unblocks with EOF or SocketException.
            try { s.shutdownInput();  } catch (IOException ignored) {}
            try { s.shutdownOutput(); } catch (IOException ignored) {}
            try { s.close();          } catch (IOException ignored) {}
        }
        Thread t = readerThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(READER_JOIN_TIMEOUT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

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
package io.mdudel.zenoh.purejava.scouting;

import io.mdudel.zenoh.purejava.wire.WhatAmI;
import io.mdudel.zenoh.purejava.wire.WhatAmIMatcher;
import io.mdudel.zenoh.purejava.wire.ZenohId;
import io.mdudel.zenoh.purejava.wire.messages.Hello;
import io.mdudel.zenoh.purejava.wire.messages.Scout;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java Zenoh 1.x scouting client.
 *
 * <p>Listens for HELLO messages on the Zenoh multicast group (default
 * {@code udp/224.0.0.224:7446}) and optionally emits SCOUT messages
 * periodically to prod silent nodes into replying. Builds a live registry
 * of {@link DiscoveredNode}s that developers can consume either
 * reactively (via {@link ScoutListener}) or with a pull-model
 * {@link #snapshot()}.</p>
 *
 * <h2>Not a peer, not a router, not a session</h2>
 * <p>This class is a passive observer. It never opens a TCP/TLS/WS session,
 * never sends INIT, never declares itself a Zenoh node. Routers and peers
 * on the mesh see it as (at most) a source of SCOUT queries; if
 * {@link Mode#PASSIVE} is selected, they don't see it at all.</p>
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li>{@link Mode#PASSIVE} &mdash; listen only, never emit. Some routers
 *       advertise themselves via periodic multicast HELLO on their own,
 *       so passive discovery still works, just slower and only for
 *       actively-broadcasting nodes.</li>
 *   <li>{@link Mode#ACTIVE} &mdash; listen AND periodically emit SCOUT
 *       messages every {@link Builder#scoutIntervalMillis(long) scoutInterval}
 *       to prompt any node whose role bit is set in the
 *       {@link Builder#whatAmIMatcher(WhatAmIMatcher) matcher} to reply
 *       with a HELLO. This is the default and matches the {@code zenoh
 *       scout} CLI behaviour. Active always includes listening; you
 *       cannot receive replies to your SCOUTs without a socket bound to
 *       the multicast group.</li>
 * </ul>
 *
 * <h2>Multi-NIC</h2>
 * <p>If {@link Builder#networkInterfaces(java.util.Collection) networkInterfaces}
 * is not set, the scout binds one socket per non-loopback, up, multicast-capable
 * interface it can find. Loopback is skipped by default because most operating
 * systems do not deliver multicast to {@code 127.0.0.1} even when both sender
 * and receiver are on the same host; see the sample README for the workaround.</p>
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>One reader daemon thread per NIC socket.</li>
 *   <li>One scheduler thread for the periodic SCOUT emission and the
 *       staleness sweeper.</li>
 *   <li>{@link ScoutListener} callbacks fire from the reader thread that
 *       received the packet, under the registry lock. They are totally
 *       ordered per ZID.</li>
 *   <li>{@link #snapshot()} is safe from any thread and takes a shallow
 *       copy of the registry.</li>
 * </ul>
 */
public final class PureJavaZenohScout implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PureJavaZenohScout.class.getName());

    /** Zenoh's default scouting multicast group. */
    public static final String  DEFAULT_MULTICAST_ADDRESS = "224.0.0.224";
    /** Zenoh's default scouting UDP port. */
    public static final int     DEFAULT_MULTICAST_PORT    = 7446;
    /** Protocol version this codec targets (matches Turn D handshake). */
    public static final int     DEFAULT_PROTOCOL_VERSION  = 0x09;
    /** Reasonable default active SCOUT interval. */
    public static final long    DEFAULT_SCOUT_INTERVAL_MS = 3_000L;
    /** Reasonable default staleness window. */
    public static final long    DEFAULT_STALE_TIMEOUT_MS  = 15_000L;

    /** Scouting mode. */
    public enum Mode { PASSIVE, ACTIVE }

    // ----- config (immutable) --------------------------------------------
    private final InetAddress            multicastGroup;
    private final int                    multicastPort;
    private final List<NetworkInterface> interfaces;   // never null; may be empty (auto-discover)
    private final WhatAmIMatcher         matcher;
    private final Mode                   mode;
    private final long                   scoutIntervalMs;
    private final long                   staleTimeoutMs;
    private final int                    protocolVersion;
    private final ZenohId                selfZid;      // null => omit ZID from SCOUT
    private final int                    receiveBufferBytes;

    // ----- runtime state -------------------------------------------------
    private final Map<ZenohId, DiscoveredNode> registry = new HashMap<>();
    private final Object                       registryLock = new Object();
    private final List<ScoutListener>          listeners = new CopyOnWriteArrayList<>();
    private final List<MulticastSocket>        sockets   = new ArrayList<>();
    private final List<Thread>                 readers   = new ArrayList<>();
    private final AtomicBoolean                started   = new AtomicBoolean(false);
    private final AtomicBoolean                stopping  = new AtomicBoolean(false);
    private final AtomicLong                   scoutsSent    = new AtomicLong();
    private final AtomicLong                   hellosParsed  = new AtomicLong();
    private final AtomicLong                   hellosMalformed = new AtomicLong();
    private ScheduledExecutorService           scheduler;

    private PureJavaZenohScout(Builder b) {
        this.multicastGroup   = b.multicastGroup;
        this.multicastPort    = b.multicastPort;
        this.interfaces       = b.interfaces == null ? List.of() : List.copyOf(b.interfaces);
        this.matcher          = b.matcher;
        this.mode             = b.mode;
        this.scoutIntervalMs  = b.scoutIntervalMs;
        this.staleTimeoutMs   = b.staleTimeoutMs;
        this.protocolVersion  = b.protocolVersion;
        this.selfZid          = b.selfZid;
        this.receiveBufferBytes = b.receiveBufferBytes;
        if (b.listener != null) this.listeners.add(b.listener);
    }

    public static Builder builder() { return new Builder(); }

    // ----- public API ---------------------------------------------------

    /** Register a listener. May be called before or after {@link #start()}. */
    public PureJavaZenohScout addListener(ScoutListener l) {
        if (l != null) listeners.add(l);
        return this;
    }

    /** Deregister a previously-added listener. */
    public boolean removeListener(ScoutListener l) { return listeners.remove(l); }

    /** Immutable snapshot of the current registry, sorted by first-seen ascending. */
    public List<DiscoveredNode> snapshot() {
        synchronized (registryLock) {
            List<DiscoveredNode> out = new ArrayList<>(registry.values());
            out.sort((a, b) -> a.firstSeen().compareTo(b.firstSeen()));
            return Collections.unmodifiableList(out);
        }
    }

    /** Lookup a specific node by ZID; empty if not (currently) known. */
    public java.util.Optional<DiscoveredNode> get(ZenohId zid) {
        synchronized (registryLock) {
            return java.util.Optional.ofNullable(registry.get(zid));
        }
    }

    public long scoutsSent()      { return scoutsSent.get(); }
    public long hellosParsed()    { return hellosParsed.get(); }
    public long hellosMalformed() { return hellosMalformed.get(); }

    /** Current mode. */
    public Mode mode() { return mode; }

    /** Start listening (and, in {@link Mode#ACTIVE}, emitting SCOUTs). */
    public synchronized void start() throws IOException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("PureJavaZenohScout already started");
        }
        List<NetworkInterface> nifs = resolveInterfaces();
        if (nifs.isEmpty()) {
            throw new IOException("PureJavaZenohScout.start(): no multicast-capable "
                    + "network interfaces found. Explicitly configure one via "
                    + "Builder.networkInterfaces(...).");
        }
        InetSocketAddress groupAddr = new InetSocketAddress(multicastGroup, multicastPort);

        for (NetworkInterface nif : nifs) {
            MulticastSocket s;
            try {
                s = new MulticastSocket(multicastPort);
                s.setReuseAddress(true);
                s.setNetworkInterface(nif);
                s.joinGroup(groupAddr, nif);
            } catch (IOException e) {
                LOG.log(Level.WARNING, () -> "Scout: failed to join " + multicastGroup
                        + " on interface " + nif.getName() + ": " + e.getMessage());
                continue;
            }
            sockets.add(s);
            Thread t = new Thread(new ReaderLoop(s, nif),
                    "zenoh-scout-reader-" + nif.getName());
            t.setDaemon(true);
            readers.add(t);
            t.start();
            LOG.log(Level.INFO, () ->
                    "PureJavaZenohScout: joined " + multicastGroup.getHostAddress()
                            + ":" + multicastPort + " on " + nif.getName());
        }
        if (sockets.isEmpty()) {
            started.set(false);
            throw new IOException("PureJavaZenohScout.start(): could not join "
                    + "multicast group on any interface");
        }

        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "zenoh-scout-sched");
            t.setDaemon(true);
            return t;
        });
        // staleness sweeper: run at ~1/2 the stale timeout
        long sweepMs = Math.max(500L, staleTimeoutMs / 2);
        scheduler.scheduleAtFixedRate(this::sweepStale, sweepMs, sweepMs, TimeUnit.MILLISECONDS);
        if (mode == Mode.ACTIVE) {
            scheduler.scheduleAtFixedRate(this::emitScout,
                    0L, scoutIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /** Send one SCOUT immediately regardless of mode; safe to call anytime after start. */
    public void scoutNow() {
        if (started.get()) emitScout();
    }

    @Override
    public synchronized void close() {
        if (!stopping.compareAndSet(false, true)) return;
        if (scheduler != null) scheduler.shutdownNow();
        for (MulticastSocket s : sockets) {
            try { s.close(); } catch (RuntimeException ignore) {}
        }
        for (Thread t : readers) {
            try { t.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        LOG.log(Level.INFO, () -> "PureJavaZenohScout: closed. sent="
                + scoutsSent.get() + " parsed=" + hellosParsed.get()
                + " malformed=" + hellosMalformed.get());
    }

    // ----- interior --------------------------------------------------

    private List<NetworkInterface> resolveInterfaces() throws IOException {
        if (!interfaces.isEmpty()) return interfaces;
        List<NetworkInterface> out = new ArrayList<>();
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        while (all.hasMoreElements()) {
            NetworkInterface nif = all.nextElement();
            if (!nif.isUp() || nif.isLoopback() || !nif.supportsMulticast()) continue;
            // Skip virtual/no-address interfaces
            if (!nif.getInetAddresses().hasMoreElements()) continue;
            out.add(nif);
        }
        return out;
    }

    private void emitScout() {
        if (!started.get() || stopping.get()) return;
        Scout s = new Scout(protocolVersion, matcher, selfZid, List.of());
        byte[] payload = s.encode();
        for (MulticastSocket sk : sockets) {
            try {
                DatagramPacket p = new DatagramPacket(
                        payload, payload.length, multicastGroup, multicastPort);
                sk.send(p);
                scoutsSent.incrementAndGet();
            } catch (IOException e) {
                LOG.log(Level.WARNING, () -> "Scout: emit failed on socket: " + e.getMessage());
            }
        }
    }

    // Package-private for tests. Directly inject a HELLO as if it arrived on the wire.
    void handleHello(Hello h, InetSocketAddress source) {
        // Filter: if the matcher explicitly excludes the sender's role, ignore.
        if (!matcher.matches(h.whatAmI())) return;

        Instant now = Instant.now();
        DiscoveredNode prev;
        DiscoveredNode current;
        boolean firstTime;
        synchronized (registryLock) {
            prev = registry.get(h.zid());
            if (prev == null) {
                current = new DiscoveredNode(
                        h.zid(), h.whatAmI(), h.locators(), source,
                        h.version(), now, now);
                firstTime = true;
            } else {
                // Preserve firstSeen; refresh lastSeen and locators.
                List<String> locs = h.locators().isEmpty() ? prev.locators() : h.locators();
                current = new DiscoveredNode(
                        h.zid(), h.whatAmI(), locs, source,
                        h.version(), prev.firstSeen(), now);
                firstTime = false;
            }
            registry.put(h.zid(), current);
        }
        if (firstTime) {
            for (ScoutListener l : listeners) safeCall(() -> l.onDiscover(current));
        } else {
            for (ScoutListener l : listeners) safeCall(() -> l.onUpdate(prev, current));
        }
    }

    // Package-private for tests. Runs the stale-sweep pass synchronously.
    void sweepStale() {
        Instant cutoff = Instant.now().minusMillis(staleTimeoutMs);
        List<DiscoveredNode> expired = new ArrayList<>();
        synchronized (registryLock) {
            var it = registry.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getValue().lastSeen().isBefore(cutoff)) {
                    expired.add(e.getValue());
                    it.remove();
                }
            }
        }
        for (DiscoveredNode n : expired) {
            for (ScoutListener l : listeners) safeCall(() -> l.onExpire(n));
        }
    }

    private static void safeCall(Runnable r) {
        try { r.run(); } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Scout: listener threw: " + e);
        }
    }

    private final class ReaderLoop implements Runnable {
        private final MulticastSocket sock;
        private final NetworkInterface nif;
        ReaderLoop(MulticastSocket s, NetworkInterface n) { this.sock = s; this.nif = n; }

        @Override public void run() {
            byte[] buf = new byte[receiveBufferBytes];
            while (started.get() && !stopping.get() && !sock.isClosed()) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(pkt);
                } catch (IOException e) {
                    if (!stopping.get()) {
                        LOG.log(Level.WARNING, () ->
                                "Scout: receive failed on " + nif.getName() + ": " + e.getMessage());
                    }
                    return;
                }
                InetSocketAddress src = (InetSocketAddress) pkt.getSocketAddress();
                byte[] data = new byte[pkt.getLength()];
                System.arraycopy(pkt.getData(), pkt.getOffset(), data, 0, pkt.getLength());
                if (data.length == 0) continue;
                int id = data[0] & 0x1F;
                if (id != Hello.ID) {
                    // A SCOUT from someone else on the wire is normal; skip silently.
                    // Anything else is unknown; also skip.
                    continue;
                }
                try {
                    Hello h = Hello.decode(data);
                    hellosParsed.incrementAndGet();
                    handleHello(h, src);
                } catch (RuntimeException e) {
                    hellosMalformed.incrementAndGet();
                    LOG.log(Level.WARNING, () ->
                            "Scout: malformed HELLO from " + src + ": " + e.getMessage());
                }
            }
        }
    }

    // ----- Builder ------------------------------------------------------

    public static final class Builder {
        private InetAddress            multicastGroup;
        private int                    multicastPort   = DEFAULT_MULTICAST_PORT;
        private List<NetworkInterface> interfaces;
        private WhatAmIMatcher         matcher         = WhatAmIMatcher.any();
        private Mode                   mode            = Mode.ACTIVE;
        private long                   scoutIntervalMs = DEFAULT_SCOUT_INTERVAL_MS;
        private long                   staleTimeoutMs  = DEFAULT_STALE_TIMEOUT_MS;
        private int                    protocolVersion = DEFAULT_PROTOCOL_VERSION;
        private ZenohId                selfZid;         // null by default => SCOUT without ZID
        private int                    receiveBufferBytes = 65_507;   // max UDP datagram payload
        private ScoutListener          listener;

        private Builder() {
            try {
                this.multicastGroup = InetAddress.getByName(DEFAULT_MULTICAST_ADDRESS);
            } catch (java.net.UnknownHostException e) {
                throw new AssertionError("hardcoded default multicast address is invalid", e);
            }
        }

        /** Override the multicast group address. Default {@value #DEFAULT_MULTICAST_ADDRESS}. */
        public Builder multicastAddress(String addr) throws java.net.UnknownHostException {
            this.multicastGroup = InetAddress.getByName(Objects.requireNonNull(addr));
            return this;
        }

        /** Override the multicast group address by {@link InetAddress}. */
        public Builder multicastAddress(InetAddress addr) {
            this.multicastGroup = Objects.requireNonNull(addr);
            return this;
        }

        /** Override the UDP port. Default {@value #DEFAULT_MULTICAST_PORT}. */
        public Builder multicastPort(int port) {
            if (port < 1 || port > 65535)
                throw new IllegalArgumentException("port out of range: " + port);
            this.multicastPort = port;
            return this;
        }

        /** Explicitly bind to the given NICs. If unset, auto-discover all non-loopback, up, multicast-capable NICs. */
        public Builder networkInterfaces(java.util.Collection<NetworkInterface> nifs) {
            this.interfaces = nifs == null ? null : new ArrayList<>(nifs);
            return this;
        }

        /** Convenience: bind to a single NIC by system name (e.g. "eth0", "wlan0", "en0"). */
        public Builder networkInterface(String name) throws java.net.SocketException {
            NetworkInterface n = NetworkInterface.getByName(Objects.requireNonNull(name));
            if (n == null) throw new IllegalArgumentException("no such interface: " + name);
            return networkInterfaces(List.of(n));
        }

        /** Restrict what roles this scout looks for. Default: {@link WhatAmIMatcher#any()}. */
        public Builder whatAmIMatcher(WhatAmIMatcher m) {
            this.matcher = Objects.requireNonNull(m);
            return this;
        }

        /** Shortcut for {@link #whatAmIMatcher(WhatAmIMatcher)} from a single role. */
        public Builder role(WhatAmI role) {
            this.matcher = WhatAmIMatcher.of(role);
            return this;
        }

        /** Shortcut for {@link #whatAmIMatcher(WhatAmIMatcher)} from a set of roles. */
        public Builder roles(Set<WhatAmI> roles) {
            this.matcher = WhatAmIMatcher.of(roles);
            return this;
        }

        /** Scout mode. Default {@link Mode#ACTIVE}. */
        public Builder mode(Mode m) {
            this.mode = Objects.requireNonNull(m);
            return this;
        }

        /**
         * Interval between active SCOUT emissions. Ignored in
         * {@link Mode#PASSIVE}. Minimum 250 ms to avoid melting anyone's
         * network; default {@value #DEFAULT_SCOUT_INTERVAL_MS} ms.
         */
        public Builder scoutIntervalMillis(long ms) {
            if (ms < 250L)
                throw new IllegalArgumentException("scoutIntervalMillis must be >= 250: " + ms);
            this.scoutIntervalMs = ms;
            return this;
        }

        /**
         * How long since the last HELLO before a node is considered gone.
         * Should be at least a few times the scoutInterval so a temporary
         * packet loss doesn't evict a still-live node. Default
         * {@value #DEFAULT_STALE_TIMEOUT_MS} ms.
         */
        public Builder staleTimeoutMillis(long ms) {
            if (ms < 500L)
                throw new IllegalArgumentException("staleTimeoutMillis must be >= 500: " + ms);
            this.staleTimeoutMs = ms;
            return this;
        }

        /** Protocol version to put in emitted SCOUTs. Default {@code 0x09}. */
        public Builder protocolVersion(int v) {
            if (v < 0 || v > 0xFF)
                throw new IllegalArgumentException("protocolVersion must fit in u8: " + v);
            this.protocolVersion = v;
            return this;
        }

        /**
         * Include our own ZID in emitted SCOUTs. Optional; by default the
         * SCOUT's {@code I} flag is 0 and no ZID is sent. A fixed ZID lets
         * counterparties recognise repeated scouts from the same source.
         */
        public Builder selfZid(ZenohId zid) {
            this.selfZid = zid;
            return this;
        }

        /** Receive buffer size per packet. Default 65,507 bytes (UDP max). */
        public Builder receiveBufferBytes(int n) {
            if (n < 512) throw new IllegalArgumentException("receiveBufferBytes must be >= 512");
            this.receiveBufferBytes = n;
            return this;
        }

        /** Attach a single listener at build time. Multiple listeners: use {@link #addListener}. */
        public Builder listener(ScoutListener l) { this.listener = l; return this; }

        public PureJavaZenohScout build() {
            return new PureJavaZenohScout(this);
        }
    }
}

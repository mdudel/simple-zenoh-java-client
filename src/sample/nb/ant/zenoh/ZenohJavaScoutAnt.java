/*
 * -----------------------------------------------------------------------------
 *          UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED
 *                   (C) Copyright 2026 USAREUR CTO TEAM
 *                            AGILE HONEYBADGERS
 *                            ALL RIGHTS RESERVED
 *                    THIS NOTICE DOES NOT IMPLY PUBLICATION
 * -----------------------------------------------------------------------------
 */
package sample.nb.ant.zenoh;

import io.mdudel.zenoh.purejava.scouting.DiscoveredNode;
import io.mdudel.zenoh.purejava.scouting.PureJavaZenohScout;
import io.mdudel.zenoh.purejava.scouting.ScoutListener;
import io.mdudel.zenoh.purejava.wire.WhatAmI;
import io.mdudel.zenoh.purejava.wire.WhatAmIMatcher;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal command-line Zenoh scouting / node-discovery tool built on the
 * pure-Java {@link PureJavaZenohScout} facade.
 *
 * <p>
 * Joins the Zenoh multicast group (default {@code 224.0.0.224:7446}), listens
 * for HELLO frames broadcast by nearby routers and peers, and optionally emits
 * SCOUT frames to prompt silent nodes into replying. Prints one line per
 * discovery event (discover / update / expire) to stdout and shuts down cleanly
 * on Ctrl-C or after an optional timeout.
 *
 * <p>
 * Unlike {@link ZenohJavaPubAnt} and {@link ZenohJavaSubAnt} this tool never
 * opens a TCP/TLS session to a router. It is a pure UDP-multicast observer
 * that decodes HELLOs and (in active mode) emits SCOUTs. Routers do not see it
 * as a Zenoh peer, client, or session.
 *
 * <p>
 * Positional arguments (all optional, in order):
 * <ol>
 * <li>{@code mode} — {@code active} or {@code passive}. Default {@code active}.
 * {@code active} listens AND emits a SCOUT every {@code interval} milliseconds;
 * {@code passive} only listens for nodes that self-advertise on their own.</li>
 * <li>{@code interval} — active-mode SCOUT interval in milliseconds. Default
 * {@code 3000}. Ignored in passive mode. Minimum {@code 250}.</li>
 * <li>{@code roles} — comma-separated filter of roles to accept: any of
 * {@code router}, {@code peer}, {@code client}. Default {@code router,peer,client}
 * (all three).</li>
 * <li>{@code timeoutSeconds} — optional auto-shutdown timer in seconds. Default
 * {@code 0} = run until Ctrl-C.</li>
 * </ol>
 *
 * <p>
 * Pairs with any running Zenoh router on the same L2 segment for a quick
 * end-to-end smoke test.
 *
 * @author Dude-1
 * @implNote <strong>127.0.0.1 does NOT carry multicast on any OS.</strong>
 * Linux, macOS and Windows all agree: loopback never delivers multicast, even
 * for same-host processes. If your router and this scout are on the same host,
 * both must bind to the same real NIC (LAN or VPN). The scout auto-detects and
 * uses every non-loopback, up, multicast-capable NIC on the box, so on a
 * normal workstation "it just works" once the router is up on the same L2.
 * <p>
 * <strong>Windows firewall.</strong> The JVM needs an inbound-UDP allow rule
 * on port 7446, and the active network profile must be Private (not Public).
 * From an elevated PowerShell:
 * <pre>
 * New-NetFirewallRule -DisplayName 'Java multicast inbound' `
 *     -Direction Inbound -Program 'C:\path\to\java.exe' `
 *     -Action Allow -Protocol UDP
 * </pre>
 * <p>
 * <strong>Locked-down containers.</strong> Some container / VM networks refuse
 * IGMP outright (K8s pods with /32 netmasks, hardened bastions). If the scout
 * logs {@code no multicast-capable network interfaces found} or every SCOUT
 * emits {@code Network is unreachable}, that is not fixable inside the JVM —
 * it needs a real host network path with multicast routing.
 * @implNote The main loop catches {@link Exception} and prints the stack trace,
 * but the JVM still exits {@code 0}. If you script this from CI or Ant, wrap
 * main() and set a non-zero exit code on failure.
 * @implNote Callbacks fire on the scout's reader threads (one per NIC socket).
 * Do not block for long-running work inside the {@link ScoutListener} methods —
 * hand off to an executor if you need to. The three callbacks are totally
 * ordered per-ZID under the scout's internal registry lock, so a listener sees
 * onDiscover then any number of onUpdate then finally onExpire, never
 * interleaved for the same node.
 * @implNote Two output surfaces on the same instance: the callback stream
 * printed here as it happens, and the {@link PureJavaZenohScout#snapshot()}
 * pull API for asking "what nodes do you currently know about?" at any moment.
 * The final shutdown line uses snapshot() to dump the surviving registry.
 */
public class ZenohJavaScoutAnt {

    /**
     * Program entry point.
     *
     * @param args positional CLI arguments; see class-level Javadoc for the
     * {@code mode / interval / roles / timeoutSeconds} contract.
     */
    public static void main(String[] args) {
        // Parse positional args with safe defaults so the sample runs
        // out of the box on the standard Zenoh scouting multicast
        // group (224.0.0.224:7446).
        String modeArg = args.length > 0 ? args[0] : "active";
        long interval = args.length > 1 ? Long.parseLong(args[1]) : 3_000L;
        String rolesArg = args.length > 2 ? args[2] : "router,peer,client";
        long timeoutSeconds = args.length > 3 ? Long.parseLong(args[3]) : 0L;

        // Turn the roles CSV into a WhatAmIMatcher bitmap. The matcher
        // is applied on the inbound path too, so HELLOs from
        // non-matching roles are dropped before onDiscover fires.
        EnumSet<WhatAmI> roles = EnumSet.noneOf(WhatAmI.class);
        for (String r : rolesArg.split(",")) {
            roles.add(WhatAmI.valueOf(r.trim().toUpperCase()));
        }
        WhatAmIMatcher matcher = WhatAmIMatcher.of(roles);

        PureJavaZenohScout.Mode mode = PureJavaZenohScout.Mode.valueOf(modeArg.toUpperCase());

        // Fixed-width indent used only for pretty-printing the banner
        // so wrapped lines align under "mode=".
        String pad = "                       ";
        System.out.println("[zenoh-java-ant-scout] mode=" + mode
                + "\n" + pad + "interval(mS)=" + (mode == PureJavaZenohScout.Mode.ACTIVE ? interval : "n/a")
                + "\n" + pad + "roles=" + roles
                + (timeoutSeconds > 0
                        ? "\n" + pad + "timeout=" + timeoutSeconds + "s"
                        : "\n" + pad + "(Ctrl-C to stop)"));

        // One-shot latch flipped either by the shutdown hook (Ctrl-C /
        // SIGTERM) or by the timeout branch below. Using a latch keeps
        // the main thread parked cheaply without a busy loop.
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));

        // Listener prints one line per event. The three methods
        // (onDiscover / onUpdate / onExpire) fire under the scout's
        // registry lock, so per-ZID event ordering is guaranteed. See
        // class Javadoc @implNote for the "don't block in here" rule.
        ScoutListener listener = new ScoutListener() {
            @Override
            public void onDiscover(DiscoveredNode n) {
                System.out.println("[zenoh-java-ant-scout] + " + n.role()
                        + " " + n.zid() + " at " + n.bestLocator());
            }

            @Override
            public void onUpdate(DiscoveredNode prev, DiscoveredNode now) {
                if (!prev.locators().equals(now.locators())) {
                    System.out.println("[zenoh-java-ant-scout] ~ " + now.zid()
                            + " locators=" + now.locators());
                }
                // Silent otherwise: refresh-only updates would spam
                // stdout at the SCOUT interval. Uncomment for a heartbeat:
                // System.out.println("[zenoh-java-ant-scout] . " + now.zid());
            }

            @Override
            public void onExpire(DiscoveredNode n) {
                System.out.println("[zenoh-java-ant-scout] - " + n.zid()
                        + " (last " + n.role() + " at " + n.bestLocator() + ")");
            }
        };

        try {
            // Builder mirrors the pub/sub facades: mode + matcher are
            // the only knobs most callers touch. Multicast group,
            // port, and NIC selection auto-fill with sensible defaults
            // (224.0.0.224:7446, every up + non-loopback + multicast
            // -capable NIC).
            PureJavaZenohScout zenohScout = PureJavaZenohScout.builder()
                    .mode(mode)
                    .scoutIntervalMillis(mode == PureJavaZenohScout.Mode.ACTIVE ? interval : 3_000L)
                    .whatAmIMatcher(matcher)
                    .listener(listener)
                    .build();

            // Joins the multicast group on every candidate NIC (one
            // reader thread per socket) and, if mode=ACTIVE, kicks
            // off the SCOUT scheduler at the configured interval.
            // Blocks only briefly on kernel-level group joins; throws
            // IOException if every NIC join failed.
            zenohScout.start();

            System.out.println("[zenoh-java-ant-scout] listening on 224.0.0.224:7446");

            // Park the main thread. Either the timeout expires or the
            // shutdown hook counts the latch down; either way we fall
            // through to the shutdown message.
            if (timeoutSeconds > 0) {
                stop.await(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                stop.await();
            }

            // Snapshot dump on the way out so operators see the final
            // registry state even if events flew by too fast to catch.
            System.out.println("[zenoh-java-ant-scout] shutting down"
                    + " (scouts=" + zenohScout.scoutsSent()
                    + " hellos=" + zenohScout.hellosParsed()
                    + " malformed=" + zenohScout.hellosMalformed()
                    + ")");
            for (DiscoveredNode n : zenohScout.snapshot()) {
                System.out.println("[zenoh-java-ant-scout]   " + n.role()
                        + " " + n.zid() + " " + n.locators());
            }

            // Graceful stop — shuts the scheduler, closes each
            // multicast socket, joins the reader threads. Idempotent.
            zenohScout.close();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            // Prints the trace for diagnostics; JVM still exits 0.
            ex.printStackTrace();
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("no multicast-capable network interfaces found")) {
                System.out.println("[zenoh-java-ant-scout] Exception\n"
                        + pad + "No suitable NIC on this host. Loopback is skipped\n"
                        + pad + "because 127.0.0.1 never carries multicast. If you\n"
                        + pad + "are on a container / VM with only a virtual NIC,\n"
                        + pad + "run on the host or attach a real / bridged NIC.");
            } else if (msg.contains("could not join multicast group")) {
                System.out.println("[zenoh-java-ant-scout] Exception\n"
                        + pad + "The kernel refused to join 224.0.0.224 on every NIC.\n"
                        + pad + "Common causes: K8s pod with /32 netmask; container\n"
                        + pad + "with IGMP blocked; Windows firewall dropping UDP 7446.\n"
                        + pad + "For Windows, allow inbound UDP on java.exe and set\n"
                        + pad + "the network profile to Private (not Public).");
            }
        }
    }

}

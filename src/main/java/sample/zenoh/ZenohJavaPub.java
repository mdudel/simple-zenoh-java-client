/*
 * -----------------------------------------------------------------------------
 *          UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED
 *                 (C) Copyright 2026 USAREUR G3 MCSD DEVINT
 *                            AGILE HONEYBADGERS
 *                            ALL RIGHTS RESERVED
 *                    THIS NOTICE DOES NOT IMPLY PUBLICATION
 * -----------------------------------------------------------------------------
 */
package sample.zenoh;

import io.mdudel.zenoh.purejava.PureJavaZenohPublisher;
import io.mdudel.zenoh.purejava.wire.CongestionControl;
import io.mdudel.zenoh.purejava.wire.Priority;
import io.mdudel.zenoh.purejava.wire.Qos;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal command-line Zenoh publisher built on the pure-Java
 * {@link PureJavaZenohPublisher} facade.
 *
 * <p>
 * Connects to a Zenoh router over plain TCP, publishes a fixed number of
 * short UTF-8 payloads to a single key expression at a configurable interval,
 * then closes the session cleanly.
 *
 * <p>
 * Positional arguments (all optional, in order):
 * <ol>
 * <li>{@code endpoint} — Zenoh connect endpoint. Default
 * {@code tcp/localhost:7447}. Accepts any scheme the facade supports
 * (tcp/tls/ws/wss); this sample does not wire TLS credentials, so tls/wss will
 * fail without extra config.</li>
 * <li>{@code key} — publish key expression. Default {@code demo/greeting}.</li>
 * <li>{@code count} — number of messages to publish. Default {@code 5}.</li>
 * <li>{@code interval} — inter-message delay in milliseconds. Default
 * {@code 1000}.</li>
 * </ol>
 *
 * <p>
 * Optional named flags (accepted anywhere in args, in any order,
 * stripped from the positional sequence before defaults apply):
 * <ul>
 * <li>{@code --priority=<name>} - one of {@code control}, {@code real_time},
 *     {@code interactive_high}, {@code interactive_low}, {@code data_high},
 *     {@code data} (default, no QoS ext emitted), {@code data_low},
 *     {@code background}. Also accepts the Zenoh Display form with dashes
 *     (e.g. {@code real-time}, {@code interactive-high}), case-insensitive.
 *     Sets a publisher-scope default priority on the builder.</li>
 * <li>{@code --congestion=<drop|block>} - CongestionControl. Default
 *     {@code drop}. Only takes effect when combined with a non-default
 *     priority (or with {@code --express}) because otherwise the resulting
 *     Qos equals the wire default and is suppressed.</li>
 * <li>{@code --express} - set the Express bit on the outbound QoS byte.</li>
 * <li>{@code --auto-timestamp} - stamp every message with a fresh
 *     {@link io.mdudel.zenoh.purejava.wire.Timestamp}. NOTE: current-date
 *     wall-clock timestamps hit a pre-existing bug in {@code VarInt.encode}
 *     (rejects longs with the sign bit set); tracked upstream as
 *     mdudel/java-zenoh-publisher#7. Safe to use once that fix lands.</li>
 * <li>{@code --node-id=<u32>} - publisher-scope origin routing id.
 *     {@code 0} (default) suppresses the NodeId extension.</li>
 * </ul>
 *
 * <p>
 * <b>Backward compatibility.</b> With no {@code --} flags supplied, this
 * sample behaves exactly as before: same positional args, same wire
 * output byte-for-byte. All new flags are strictly opt-in.
 *
 * <p>
 * Pairs with {@link ZenohJavaSub} for a quick end-to-end smoke test against
 * a local {@code zenohd}.
 *
 * @author Marty
 * @implNote <strong>Windows localhost gotcha.</strong> The default
 * {@code zenohd} config listens on {@code tcp/[::]:7447} (IPv6 wildcard only).
 * On Windows the IPv6 wildcard does NOT dual-bind to IPv4, and
 * {@code localhost} typically resolves to {@code 127.0.0.1} first — the client
 * then hits nothing and gets {@code Connection refused: getsockopt}. Two fixes:
 * <ul>
 * <li>Client side: pass {@code tcp/[::1]:7447} as the first arg.</li>
 * <li>Server side: start zenohd with
 * {@code --listen tcp/0.0.0.0:7447 --listen tcp/[::]:7447} so both stacks are
 * bound.</li>
 * </ul>
 * Linux does not have this problem because {@code IPV6_V6ONLY} defaults off
 * there.
 * @implNote The main loop catches {@link Exception} and prints the stack trace,
 * but the JVM still exits {@code 0}. If you script this from CI or Ant, wrap
 * main() and set a non-zero exit code on failure.
 * @implNote The publisher is single-key by design: {@code .keyExpr(key)} on the
 * builder pins the topic and every {@link PureJavaZenohPublisher#publish(byte[])}
 * call sends to it. For multi-key publishes from one session, use the
 * {@code publish(subKey, data)} overload instead.
 */
public class ZenohJavaPub {

    /**
     * Program entry point.
     *
     * @param args positional CLI arguments; see class-level Javadoc for the
     * {@code endpoint / key / count / interval} contract.
     * @throws Exception any unhandled failure is allowed to propagate so the
     * JVM prints a stack trace; the {@code try/catch} inside also prints and
     * swallows so the {@code throws} is defensive only.
     */
    public static void main(String[] args) throws Exception {
        // Split args into named flags (--foo, --foo=bar) and positional.
        // This keeps the historical positional contract intact while
        // allowing new flags to appear anywhere in the arg list.
        Priority          priority       = null;    // null = leave builder default (Data / no QoS ext)
        CongestionControl congestion     = CongestionControl.DROP;
        boolean           express        = false;
        boolean           autoTimestamp  = false;
        long              nodeId         = 0L;

        List<String> positional = new ArrayList<>(args.length);
        for (String a : args) {
            if (a == null) continue;
            if (a.startsWith("--")) {
                String rest = a.substring(2);
                int eq = rest.indexOf('=');
                String name  = (eq >= 0 ? rest.substring(0, eq) : rest).toLowerCase(Locale.ROOT);
                String value = (eq >= 0 ? rest.substring(eq + 1) : "");
                switch (name) {
                    case "priority":
                        priority = Priority.parse(value);
                        break;
                    case "congestion":
                        congestion = value.equalsIgnoreCase("block")
                                ? CongestionControl.BLOCK
                                : CongestionControl.DROP;
                        break;
                    case "express":
                        // Bare --express means true; --express=false / =0 turn it off explicitly.
                        express = value.isEmpty()
                                || value.equalsIgnoreCase("true")
                                || value.equals("1");
                        break;
                    case "auto-timestamp":
                        autoTimestamp = value.isEmpty()
                                || value.equalsIgnoreCase("true")
                                || value.equals("1");
                        break;
                    case "node-id":
                        nodeId = Long.parseLong(value);
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown flag: " + a + " (expected one of --priority, --congestion, --express, --auto-timestamp, --node-id)");
                }
            } else {
                positional.add(a);
            }
        }

        // Parse positional args with safe defaults so the sample runs
        // out of the box against a stock local zenohd on port 7447.
        // NOTE: 'localhost' on Windows can screw you; see class Javadoc
        // "Windows localhost gotcha" @implNote.
        String endpoint = positional.size() > 0 ? positional.get(0) : "tcp/localhost:7447";
        String key      = positional.size() > 1 ? positional.get(1) : "demo/greeting";
        int    count    = positional.size() > 2 ? Integer.parseInt(positional.get(2)) : 5;
        long   interval = positional.size() > 3 ? Long.parseLong(positional.get(3)) : 1_000L;

        // Compose Qos from the flags (or leave it at DEFAULT so the wire
        // byte matches pre-QoS behaviour exactly).
        Qos qos;
        if (priority == null && congestion == CongestionControl.DROP && !express) {
            qos = Qos.DEFAULT;
        } else {
            qos = new Qos(priority != null ? priority : Priority.DATA, congestion, express);
        }

        // Fixed-width indent used only for pretty-printing the banner
        // so wrapped lines align under "endpoint=".
        String pad = "                     ";
        System.out.println("[zenoh-java-ant-pub] endpoint=" + endpoint
                + "\n" + pad + "key=" + key
                + "\n" + pad + "count=" + count
                + "\n" + pad + "interval(mS)=" + interval
                + "\n" + pad + "qos=" + (qos.isDefault() ? "DEFAULT (no ext)" : qos)
                + "\n" + pad + "autoTimestamp=" + autoTimestamp
                + "\n" + pad + "nodeId=" + nodeId
        );

        try {
            // Builder mirrors the subscriber-side facade: endpoint +
            // keyExpr are the only required fields for plain TCP. For
            // TLS/mTLS add .rootCaCertPath(...) / .clientCertPath(...)
            // / .clientKeyPath(...) etc. before .build(). The QoS /
            // Timestamp / NodeId knobs are all opt-in; passing DEFAULT /
            // false / 0 gives byte-identical wire output vs the pre-QoS
            // publisher.
            PureJavaZenohPublisher zenohPublisher = PureJavaZenohPublisher.builder()
                    .connectEndpoint(endpoint)
                    .keyExpr(key)
                    .defaultQos(qos)
                    .autoTimestamp(autoTimestamp)
                    .originNodeId(nodeId)
                    .build();

            // Performs the full transport connect + 4-message Zenoh
            // handshake (InitSyn/Ack, OpenSyn/Ack). Blocks until the
            // session reaches OPEN or throws SessionException (wrapped
            // as IOException at the facade boundary).
            zenohPublisher.start();
            System.out.println("[zenoh-java-ant-pub] session OPEN");

            // Publish loop. Each call sends a PUSH frame to the router
            // for the key bound at build() time. getSentCount() is a
            // best-effort monotonic counter maintained by the facade;
            // handy for smoke-testing that the loop is actually running.
            for (int i = 1; i <= count; i++) {
                String payload = "hello #" + i + " from pure-Java";
                zenohPublisher.publish(payload.getBytes(StandardCharsets.UTF_8));
                System.out.println("[pure-java-simple-publisher] "
                        + i + "/" + count + " -> '" + payload + "'"
                        + " (sent=" + zenohPublisher.getSentCount() + ")");
                if (i < count) {
                    // Simple pacing so the subscriber has time to
                    // print between messages when both are on stdout.
                    Thread.sleep(interval);
                }
            }

            System.out.println("[zenoh-java-ant-pub] done, closing");

            // NOTE: no explicit stop()/close() here — the surrounding
            // try/catch does not wrap the publisher in try-with-resources
            // and the JVM exit after main() returns will tear the
            // transport down. If you extract this into a long-running
            // service, switch to try-with-resources (the facade
            // implements AutoCloseable) so the router sees a clean
            // CLOSE frame instead of a dropped session.
        } catch (Exception ex) {
            // Prints the trace for diagnostics; JVM still exits 0.
            ex.printStackTrace();
        }

    }
}

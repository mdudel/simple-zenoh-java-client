/*
 * -----------------------------------------------------------------------------
 *          UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED
 *                 (C) Copyright 2026 USAREUR G3 MCSD DEVINT
 *                            AGILE HONEYBADGERS
 *                            ALL RIGHTS RESERVED
 *                    THIS NOTICE DOES NOT IMPLY PUBLICATION
 * -----------------------------------------------------------------------------
 */
package sample.nb.ant.zenoh;

import io.mdudel.zenoh.purejava.PureJavaZenohPublisher;
import java.nio.charset.StandardCharsets;

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
 * Pairs with {@link ZenohJavaSubAnt} for a quick end-to-end smoke test against
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
public class ZenohJavaPubAnt {

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
        // Parse positional args with safe defaults so the sample runs
        // out of the box against a stock local zenohd on port 7447.
        // NOTE: 'localhost' on Windows can screw you; see class Javadoc
        // "Windows localhost gotcha" @implNote.
        String endpoint = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String key = args.length > 1 ? args[1] : "demo/greeting";
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        long interval = args.length > 3 ? Long.parseLong(args[3]) : 1_000L;

        // Fixed-width indent used only for pretty-printing the banner
        // so wrapped lines align under "endpoint=".
        String pad = "                     ";
        System.out.println("[zenoh-java-ant-pub] endpoint=" + endpoint
                + "\n" + pad + "key=" + key
                + "\n" + pad + "count=" + count
                + "\n" + pad + "interval(mS)=" + interval
        );

        try {
            // Builder mirrors the subscriber-side facade: endpoint +
            // keyExpr are the only required fields for plain TCP. For
            // TLS/mTLS add .rootCaCertPath(...) / .clientCertPath(...)
            // / .clientKeyPath(...) etc. before .build().
            PureJavaZenohPublisher zenohPublisher = PureJavaZenohPublisher.builder()
                    .connectEndpoint(endpoint)
                    .keyExpr(key)
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

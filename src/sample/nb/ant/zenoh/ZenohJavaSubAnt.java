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

import io.mdudel.zenoh.purejava.PureJavaZenohSubscriber;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal command-line Zenoh subscriber built on the pure-Java
 * {@link PureJavaZenohSubscriber} facade.
 *
 * <p>
 * Connects to a Zenoh router over plain TCP, subscribes to a key expression,
 * prints every received {@code Sample} to stdout, and shuts down cleanly on
 * Ctrl-C or after an optional timeout.
 *
 * <p>
 * Positional arguments (all optional, in order):
 * <ol>
 * <li>{@code endpoint} — Zenoh connect endpoint. Default
 * {@code tcp/localhost:7447}. Accepts any scheme the facade supports
 * (tcp/tls/ws/wss); this sample does not wire TLS credentials, so tls/wss will
 * fail without extra config.</li>
 * <li>{@code keyExpr} — subscription key expression. Default {@code demo/**}
 * (wildcard: everything under {@code demo/}).</li>
 * <li>{@code timeoutSeconds} — optional auto-shutdown timer in seconds. Default
 * {@code 0} = run until Ctrl-C.</li>
 * </ol>
 *
 * <p>
 * Pairs with {@code samples/pure-java-simple-publisher} for a quick end-to-end
 * smoke test against a local {@code zenohd}.
 *
 * @author Dude-1
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
 * @implNote The {@code Subscription} handle returned by
 * {@code subscribeAndConsume} is not stored. The explicit {@code stop()} call
 * in the happy path is enough because it tears the whole session down (and with
 * it every registered subscription). If the try-block throws before
 * {@code stop()}, the JVM exit and transport close serve the same purpose.
 */
public class ZenohJavaSubAnt {

    /**
     * Program entry point.
     *
     * @param args positional CLI arguments; see class-level Javadoc for the
     * {@code endpoint / keyExpr / timeoutSeconds} contract.
     */
    public static void main(String[] args) {
        // Parse positional args with safe defaults so the sample runs
        // out of the box against a stock local zenohd on port 7447.
        // NOTE: 'localhost' on Windows can screw you; see class Javadoc
        // "Windows localhost gotcha" @implNote.
        String endpoint = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String keyExpr = args.length > 1 ? args[1] : "demo/**";
        long timeoutSeconds = args.length > 2 ? Long.parseLong(args[2]) : 0L;

        // Fixed-width indent used only for pretty-printing the banner
        // so wrapped lines align under "endpoint=".
        String pad = "                     ";
        System.out.println("[zenoh-java-ant-sub] endpoint=" + endpoint
                + "\n" + pad + "key=" + keyExpr
                + (timeoutSeconds > 0
                        ? "\n" + pad + "timeout=" + timeoutSeconds + "s"
                        : "\n" + pad + "(Ctrl-C to stop)"));

        // One-shot latch flipped either by the shutdown hook (Ctrl-C /
        // SIGTERM) or by the timeout branch below. Using a latch keeps
        // the main thread parked cheaply without a busy loop.
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));

        try {
            // Builder mirrors the publisher-side facade: only endpoint
            // is required for plain TCP. For TLS/mTLS add
            // .trustStorePem(...) / .keyStorePem(...) / .keyExpr(...)
            // etc. before .build().
            PureJavaZenohSubscriber zenohSubscriber = PureJavaZenohSubscriber.builder()
                    .connectEndpoint(endpoint)
                    .build();

            // Performs the full transport connect + 4-message Zenoh
            // handshake (InitSyn/Ack, OpenSyn/Ack). Blocks until the
            // session reaches OPEN or throws SessionException (wrapped
            // as IOException at the facade boundary).
            zenohSubscriber.start();

            System.out.println("[zenoh-java-ant] session OPEN");

            // Registers a DECLARE + DeclareSubscriber for keyExpr and
            // dispatches every matching inbound Sample to the lambda
            // on the session's reader thread. Do not block inside the
            // callback — it will back-pressure the inbound queue.
            zenohSubscriber.subscribeAndConsume(keyExpr, sample
                    -> System.out.println("[zenoh-java-ant] "
                            + sample.key() + " -> " + sample.payloadAsString()));

            // Park the main thread. Either the timeout expires or the
            // shutdown hook counts the latch down; either way we fall
            // through to the shutdown message.
            if (timeoutSeconds > 0) {
                stop.await(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                stop.await();
            }

            System.out.println("[zenoh-java-ant] shutting down"
                    + " (received=" + zenohSubscriber.getReceivedCount() + ")");

            // Graceful CLOSE — sends the Zenoh CLOSE frame to the
            // router, joins the KEEP_ALIVE scheduler, and shuts the
            // transport. Skipping this only really matters if you
            // want the router log to show a clean disconnect vs. a
            // dropped session on JVM exit.
            zenohSubscriber.stop();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            // Prints the trace for diagnostics; JVM still exits 0.
            ex.printStackTrace();
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("session open failed: handshake failure: connect failed to tcp")) {
                System.out.println("[zenoh-java-ant-sub] Exception\n"
                        + pad + "If this is a Windows OS, either connect via IPv6:\n"
                        + pad + "    java -jar ZenohJavaAnt.jar tcp/[::1]:7447\n"
                        + pad + "or start zenohd bound to both stacks:\n"
                        + pad + "    zenohd.exe --listen tcp/0.0.0.0:7447 --listen tcp/[::]:7447");
            } else {
                if (msg.contains("session open failed: InitAck not received")) {
                    System.out.println("[zenoh-java-ant-sub] Exception\n"
                            + pad + "Either the router is running in TLS/mTLS mode and\n"
                            + pad + "this sample is not configured with certs, or the\n"
                            + pad + "router is plaintext and you used a tls/wss endpoint.\n"
                            + pad + "For a plaintext smoke test, run zenohd without any\n"
                            + pad + "tls.* config and connect with tcp/… (not tls/…).\n"
                            + pad + "    zenohd.exe --listen tcp/0.0.0.0:7447 --listen tcp/[::]:7447");
                }
            }
        }
    }

}

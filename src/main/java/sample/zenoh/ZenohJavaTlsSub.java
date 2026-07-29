/*
 * -----------------------------------------------------------------------------
 *          UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED UNCLASSIFIED
 *                   (C) Copyright 2026 USAREUR CTO TEAM
 *                            AGILE HONEYBADGERS
 *                            ALL RIGHTS RESERVED
 *                    THIS NOTICE DOES NOT IMPLY PUBLICATION
 * -----------------------------------------------------------------------------
 */
package sample.zenoh;

import io.mdudel.zenoh.purejava.PureJavaZenohSubscriber;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * mTLS subscriber sample: connects to a Zenoh router over TLS with
 * client certificate authentication, subscribes to a key expression,
 * and prints every unique key it sees (a pragmatic "list topics" for
 * a bus that has no topic registry).
 *
 * <p>Zenoh, unlike MQTT or Kafka, has <b>no</b> topic list primitive.
 * Keys only exist while something is publishing on them. This sample
 * subscribes to a wildcard key expression, tracks every unique key it
 * observes during the run, and prints a summary on shutdown. Any key
 * that stayed silent during the window will not appear -- that is the
 * best a subscriber can do without a topic-registry service on the
 * router side.
 *
 * <p>Positional CLI arguments (all optional, in order):
 * <ol>
 *   <li>{@code endpoint} - Zenoh connect endpoint. Default
 *       {@code tls/localhost:7447}. Must start with {@code tls/}
 *       (or {@code wss/}) for this sample; use
 *       {@link ZenohJavaSub} for plaintext TCP.</li>
 *   <li>{@code rootCaCertPath} - path to the CA root PEM used to verify
 *       the router's server certificate. Required.</li>
 *   <li>{@code clientCertPath} - path to the client leaf certificate
 *       PEM (mTLS). Required.</li>
 *   <li>{@code clientKeyPath} - path to the client private key PEM
 *       (mTLS). Required. PKCS#8 or SEC1 EC (unencrypted).</li>
 *   <li>{@code keyExpr} - subscription key expression. Default
 *       {@code **} (wildcard: every key on the bus).</li>
 *   <li>{@code timeoutSeconds} - optional auto-shutdown timer.
 *       Default {@code 0} = run until Ctrl-C.</li>
 * </ol>
 *
 * <p><b>Hostname verification is disabled</b> in this sample. The typical
 * field usage is connecting to an IP (e.g. a Tailscale / CGNAT
 * {@code 100.x.x.x} address) where the router's server cert only lists
 * hostnames as SANs -- the JDK's hostname verifier would reject on
 * IP-to-CN mismatch. Trust is still anchored to the CA root; changing
 * this is a one-line edit in {@code main()}.
 *
 * @author Dude-1
 * @implNote The private key file MUST be unencrypted. If yours is
 * password-protected, convert it once with:
 * {@code openssl pkcs8 -topk8 -nocrypt -in enc.pem -out plain.pem}
 * @implNote The topic-discovery counter uses a
 * {@link ConcurrentSkipListSet} so ordered summary printing on
 * shutdown is O(n log n) sorted-natural without a second sort pass.
 * The subscribe callback runs on the session's reader thread; the
 * set is thread-safe by construction so no explicit lock is needed.
 */
public class ZenohJavaTlsSub {

    /**
     * Program entry point.
     *
     * @param args positional CLI arguments; see class-level Javadoc.
     */
    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage:");
            System.err.println("  java -cp target/classes sample.zenoh.ZenohJavaTlsSub \\");
            System.err.println("    <endpoint> <rootCaCertPath> <clientCertPath> <clientKeyPath> \\");
            System.err.println("    [keyExpr] [timeoutSeconds]");
            System.err.println();
            System.err.println("Example (Windows):");
            System.err.println("  java -cp target/classes sample.zenoh.ZenohJavaTlsSub ^");
            System.err.println("    tls/100.64.165.203:7447 ^");
            System.err.println("    \"d:\\DEV\\GOAT NET ONBOARDING\\efdi-onboarding\\efdi-ca-root.pem\" ^");
            System.err.println("    \"d:\\DEV\\GOAT NET ONBOARDING\\efdi-onboarding\\0472940bda1695cb078cd8e927b7afed-cert.pem\" ^");
            System.err.println("    \"d:\\DEV\\GOAT NET ONBOARDING\\efdi-onboarding\\0472940bda1695cb078cd8e927b7afed-key.pem\"");
            System.exit(2);
        }
        String endpoint       = args[0];
        String rootCaCertPath = args[1];
        String clientCertPath = args[2];
        String clientKeyPath  = args[3];
        String keyExpr        = args.length > 4 ? args[4] : "**";
        long   timeoutSeconds = args.length > 5 ? Long.parseLong(args[5]) : 0L;

        String pad = "                       ";
        System.out.println("[zenoh-java-tls-sub] endpoint=" + endpoint
                + "\n" + pad + "keyExpr=" + keyExpr
                + "\n" + pad + "rootCA=" + rootCaCertPath
                + "\n" + pad + "cert=" + clientCertPath
                + "\n" + pad + "key=" + clientKeyPath
                + "\n" + pad + "verifyHostname=false (typical for IP-only endpoints)"
                + (timeoutSeconds > 0
                        ? "\n" + pad + "timeout=" + timeoutSeconds + "s"
                        : "\n" + pad + "(Ctrl-C to stop)"));

        // Track unique keys observed during the run. Sorted so the
        // final summary reads naturally. Thread-safe because the
        // subscribe callback runs on the reader thread and the
        // shutdown-hook thread reads the same set.
        Set<String> observedKeys = new ConcurrentSkipListSet<>();

        // One-shot latch flipped by Ctrl-C / SIGTERM or the timeout.
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop.countDown();
            printTopicSummary(observedKeys);
        }));

        try {
            // mTLS builder: server-cert verification via rootCA PEM,
            // client-cert authentication via cert+key PEM pair.
            // verifyHostname(false) because the typical field target
            // is an IP endpoint (CGNAT / Tailscale) where the cert
            // CN/SAN will not match the connect IP. Trust is still
            // anchored to the CA root; MITM protection is intact.
            PureJavaZenohSubscriber zenohSubscriber = PureJavaZenohSubscriber.builder()
                    .connectEndpoint(endpoint)
                    .rootCaCertPath(rootCaCertPath)
                    .clientCertPath(clientCertPath)
                    .clientKeyPath(clientKeyPath)
                    .verifyHostname(false)
                    .build();

            zenohSubscriber.start();
            System.out.println("[zenoh-java-tls-sub] session OPEN (mTLS)");
            System.out.println("[zenoh-java-tls-sub] listening for topics on '" + keyExpr + "'...");

            zenohSubscriber.subscribeAndConsume(keyExpr, sample -> {
                String key = sample.key();
                // Print a one-line "[NEW]" the first time we see a key;
                // print samples normally for everything else. Zero
                // duplicate prints on hot-topic bursts.
                if (observedKeys.add(key)) {
                    System.out.println("[zenoh-java-tls-sub] [NEW TOPIC] " + key);
                }
                System.out.println("[zenoh-java-tls-sub] " + key
                        + " -> " + sample.payloadAsString());
            });

            if (timeoutSeconds > 0) {
                stop.await(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                stop.await();
            }

            System.out.println("[zenoh-java-tls-sub] shutting down"
                    + " (received=" + zenohSubscriber.getReceivedCount()
                    + ", unique-topics=" + observedKeys.size() + ")");
            zenohSubscriber.stop();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            ex.printStackTrace();
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("session open failed: handshake failure")
                    || msg.contains("SSL") || msg.contains("TLS")
                    || msg.contains("PKIX") || msg.contains("certificate")) {
                System.out.println("[zenoh-java-tls-sub] TLS/mTLS handshake diagnostics:\n"
                        + pad + "1. Verify the router is listening on tls/... (not tcp/...)\n"
                        + pad + "   and configured with server-side TLS.\n"
                        + pad + "2. Verify the CA root PEM is the CA that signed the router's\n"
                        + pad + "   server certificate (openssl verify -CAfile root.pem server.pem).\n"
                        + pad + "3. Verify the client key is UNENCRYPTED PKCS#8 or SEC1 EC.\n"
                        + pad + "   If it starts with '-----BEGIN ENCRYPTED PRIVATE KEY-----'\n"
                        + pad + "   run: openssl pkcs8 -topk8 -nocrypt -in enc.pem -out plain.pem\n"
                        + pad + "4. Verify the client cert has been enrolled on the router side\n"
                        + pad + "   (mTLS requires the server to trust the client's CA too).");
            }
        }
    }

    /**
     * Called from the shutdown hook to print a sorted list of every
     * key expression that received at least one sample during the run.
     * This is the pragmatic "list topics" answer for Zenoh, which has
     * no topic-registry primitive.
     */
    private static void printTopicSummary(Set<String> observedKeys) {
        System.out.println();
        System.out.println("========== TOPIC DISCOVERY SUMMARY ==========");
        if (observedKeys.isEmpty()) {
            System.out.println("(no topics observed during the run)");
            System.out.println("Possible reasons:");
            System.out.println("  - no publishers active on the bus during the window");
            System.out.println("  - subscribed key expression did not match any active keys");
            System.out.println("  - session never fully opened (check stderr for errors)");
        } else {
            System.out.println(observedKeys.size() + " unique topic(s) observed:");
            // Wrap TreeSet around ConcurrentSkipListSet snapshot for
            // stable iteration order in the printed output.
            for (String k : new TreeSet<>(observedKeys)) {
                System.out.println("  " + k);
            }
        }
        System.out.println("=============================================");
    }

}

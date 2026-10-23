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
import io.mdudel.zenoh.purejava.TopicInfo;
import io.mdudel.zenoh.purejava.TopicRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * mTLS subscriber sample: connects to a Zenoh router over TLS with
 * client certificate authentication, subscribes to a key expression to
 * print live incoming samples, and enables {@link TopicRegistry}
 * recording ({@link PureJavaZenohSubscriber.Builder#recordTopics(boolean)})
 * to assemble a complete topic-discovery summary on shutdown.
 *
 * <p>Zenoh, unlike MQTT or Kafka, has <b>no</b> authoritative topic list
 * primitive. This sample combines two mechanisms:
 * <ul>
 *   <li>Continuous {@link TopicRegistry} recording via
 *       {@code recordTopics(true)}, which merges router
 *       {@code INTEREST}/{@code DECLARE} declarations (subscribers,
 *       queryables, and liveliness tokens) with observed traffic and
 *       tags each {@link TopicInfo} with its provenance
 *       ({@link TopicInfo#sources()}).</li>
 *   <li>A live {@code subscribeAndConsume} callback that prints each
 *       incoming payload and logs a {@code [NEW TOPIC]} line the first
 *       time data arrives on a key.</li>
 * </ul>
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
 * @implNote The live-traffic key set uses a
 * {@link ConcurrentSkipListSet} so first-seen detection on the session's
 * reader thread is thread-safe without an explicit lock, and serves as a
 * fallback summary if the router ACL refuses discovery interest.
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

        // Defense-in-depth against a Windows-argv trap: the java.exe
        // launcher applies MSVCRT-style glob expansion to unquoted
        // ** / * arguments BEFORE main() sees them. Symptom is that
        // args[4] becomes a filename from the launch directory
        // (".github" if that's alphabetically first) and args[5]+
        // become more filenames -- so a bare `runTlsSub.bat` with
        // no explicit keyExpr would silently expand ** into a
        // directory listing and blow up parseLong on a filename.
        // If keyExpr looks like a filesystem path (contains backslash
        // or forward-slash-relative starts with '.') AND the .bat
        // did not intentionally quote it that way, fail loudly with
        // an actionable hint instead of subscribing to garbage.
        String keyExpr;
        if (args.length > 4) {
            keyExpr = args[4];
            if (looksLikeGlobbedFilename(keyExpr)) {
                System.err.println("[zenoh-java-tls-sub] REFUSING to subscribe to '" + keyExpr
                        + "' -- looks like the Windows java launcher glob-expanded a bare **/*");
                System.err.println("  Fix: either quote the wildcard as \"**\" on the command line,");
                System.err.println("       or omit the keyExpr arg entirely to let the sample default to **.");
                System.err.println("  Received argv: " + java.util.Arrays.toString(args));
                System.exit(3);
            }
        } else {
            keyExpr = "**";
        }

        long timeoutSeconds;
        if (args.length > 5) {
            try {
                timeoutSeconds = Long.parseLong(args[5]);
            } catch (NumberFormatException nfe) {
                System.err.println("[zenoh-java-tls-sub] timeoutSeconds must be a whole number, got '"
                        + args[5] + "'");
                System.err.println("  (If you're seeing a filename here, the Windows java launcher");
                System.err.println("   probably glob-expanded a bare ** in your .bat file -- see the");
                System.err.println("   Windows-argv note in runTlsSub.bat.)");
                System.err.println("  Received argv: " + java.util.Arrays.toString(args));
                System.exit(3);
                return; // unreachable; keeps the compiler happy
            }
        } else {
            timeoutSeconds = 0L;
        }

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

        // Track unique keys observed via live samples during the run,
        // and hold a reference to the subscriber / recorded TopicInfo
        // snapshot for the shutdown hook.
        Set<String> observedKeys = new ConcurrentSkipListSet<>();
        AtomicReference<PureJavaZenohSubscriber> subRef = new AtomicReference<>();
        AtomicReference<List<TopicInfo>> recordedSnapshotRef = new AtomicReference<>();

        // One-shot latch flipped by Ctrl-C / SIGTERM or the timeout.
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop.countDown();
            List<TopicInfo> snapshot = recordedSnapshotRef.get();
            if (snapshot == null) {
                PureJavaZenohSubscriber sub = subRef.get();
                if (sub != null && sub.isRecordingTopics()) {
                    try {
                        snapshot = sub.recordedTopics();
                    } catch (IllegalStateException ignored) {
                        snapshot = Collections.emptyList();
                    }
                }
            }
            printTopicSummary(snapshot, observedKeys);
        }));

        try {
            // mTLS builder: server-cert verification via rootCA PEM,
            // client-cert authentication via cert+key PEM pair.
            // verifyHostname(false) because the typical field target
            // is an IP endpoint (CGNAT / Tailscale) where the cert
            // CN/SAN will not match the connect IP. Trust is still
            // anchored to the CA root; MITM protection is intact.
            // recordTopics(true) enables the cumulative TopicRegistry
            // across declared subscribers, queryables, liveliness tokens,
            // and observed traffic.
            PureJavaZenohSubscriber zenohSubscriber = PureJavaZenohSubscriber.builder()
                    .connectEndpoint(endpoint)
                    .rootCaCertPath(rootCaCertPath)
                    .clientCertPath(clientCertPath)
                    .clientKeyPath(clientKeyPath)
                    .verifyHostname(false)
                    .recordTopics(true)
                    .recordTopicsPattern(keyExpr)
                    .build();
            subRef.set(zenohSubscriber);

            zenohSubscriber.start();
            System.out.println("[zenoh-java-tls-sub] session OPEN (mTLS)");
            System.out.println("[zenoh-java-tls-sub] listening for topics on '" + keyExpr + "'...");

            zenohSubscriber.subscribeAndConsume(keyExpr, sample -> {
                String key = sample.key();
                // Print a one-line "[NEW TOPIC]" the first time we see a key;
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

            if (zenohSubscriber.isRecordingTopics()) {
                recordedSnapshotRef.set(zenohSubscriber.recordedTopics());
            }
            List<TopicInfo> snap = recordedSnapshotRef.get();
            int topicCount = (snap != null && !snap.isEmpty()) ? snap.size() : observedKeys.size();
            System.out.println("[zenoh-java-tls-sub] shutting down"
                    + " (received=" + zenohSubscriber.getReceivedCount()
                    + ", unique-topics=" + topicCount + ")");
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
     * Heuristic: does {@code keyExpr} look like a file or directory name
     * that the Windows java launcher accidentally globbed onto our
     * argv from an unquoted {@code **}? Legitimate Zenoh key expressions
     * don't contain backslashes and don't start with a leading dot.
     */
    private static boolean looksLikeGlobbedFilename(String keyExpr) {
        if (keyExpr == null || keyExpr.isEmpty()) return false;
        if (keyExpr.startsWith(".")) return true;              // .github, .gitignore, etc.
        if (keyExpr.contains("\\")) return true;               // Windows path
        // Files with common extensions -- if the batch dir has files
        // like API.md, README.md, pom.xml the launcher may pass those
        // as argv entries too.
        if (keyExpr.endsWith(".md") || keyExpr.endsWith(".xml")
                || keyExpr.endsWith(".bat") || keyExpr.endsWith(".jar")
                || keyExpr.endsWith(".zip")) return true;
        return false;
    }

    /**
     * Called from the shutdown hook to print the sorted list of topics
     * recorded by the {@link TopicRegistry} (declarations plus observed
     * traffic), or the fallback set of observed sample keys if topic
     * recording was not active.
     */
    private static void printTopicSummary(List<TopicInfo> recordedTopics, Set<String> observedKeys) {
        System.out.println();
        System.out.println("========== TOPIC DISCOVERY SUMMARY ==========");
        if (recordedTopics != null && !recordedTopics.isEmpty()) {
            System.out.println(recordedTopics.size() + " topic(s) recorded (declarations + traffic):");
            for (TopicInfo t : recordedTopics) {
                System.out.printf("  %-45s %-8s %-14s %s%n",
                        t.keyExpr(),
                        t.active() ? "active" : "gone",
                        t.hasTraffic() ? t.sampleCount() + " sample(s)" : "no traffic",
                        t.sources());
            }
        } else if (!observedKeys.isEmpty()) {
            System.out.println(observedKeys.size() + " unique topic(s) observed:");
            for (String k : new TreeSet<>(observedKeys)) {
                System.out.println("  " + k);
            }
        } else {
            System.out.println("(no topics discovered or observed during the run)");
            System.out.println("Possible reasons:");
            System.out.println("  - no publishers, subscribers, queryables, or tokens active on the bus");
            System.out.println("  - subscribed key expression did not match any active keys");
            System.out.println("  - session never fully opened (check stderr for errors)");
        }
        System.out.println("=============================================");
    }

}

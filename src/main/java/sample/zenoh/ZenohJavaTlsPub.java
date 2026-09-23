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

import io.mdudel.zenoh.purejava.PureJavaZenohPublisher;
import io.mdudel.zenoh.purejava.wire.CongestionControl;
import io.mdudel.zenoh.purejava.wire.Priority;
import io.mdudel.zenoh.purejava.wire.Qos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * mTLS publisher sample: connects to a Zenoh router over TLS with
 * client certificate authentication, publishes a fixed number of
 * short UTF-8 payloads to a single key expression at a configurable
 * interval, then closes the session cleanly. Sibling of
 * {@link ZenohJavaTlsSub}; use {@link ZenohJavaPub} for plaintext TCP.
 *
 * <p>Positional CLI arguments (in order, only the first four are
 * required):
 * <ol>
 *   <li>{@code endpoint} - Zenoh connect endpoint. Must start with
 *       {@code tls/} (or {@code wss/}). No default; you must supply
 *       one, since a plaintext default here would defeat the point of
 *       this sample.</li>
 *   <li>{@code rootCaCertPath} - path to the CA root PEM used to verify
 *       the router's server certificate. Required.</li>
 *   <li>{@code clientCertPath} - path to the client leaf certificate
 *       PEM (mTLS). Required.</li>
 *   <li>{@code clientKeyPath} - path to the client private key PEM
 *       (mTLS). Required. PKCS#8 or SEC1 EC (unencrypted).</li>
 *   <li>{@code keyExpr} - publish key expression. Default
 *       {@code demo/greeting}.</li>
 *   <li>{@code count} - number of messages to publish. Default
 *       {@code 5}.</li>
 *   <li>{@code interval} - inter-message delay in milliseconds.
 *       Default {@code 1000}.</li>
 * </ol>
 *
 * <p>Optional named QoS / Timestamp / NodeId flags (accepted anywhere
 * in args, in any order, stripped from the positional sequence before
 * defaults apply): see {@link ZenohJavaPub}'s class-level Javadoc for
 * the full list. Same names, same semantics, same skip-when-default
 * rules -- omitting them produces byte-identical wire output to the
 * pre-QoS client.
 *
 * <ul>
 *   <li>{@code --priority=<name>}</li>
 *   <li>{@code --congestion=<drop|block>}</li>
 *   <li>{@code --express}</li>
 *   <li>{@code --auto-timestamp}</li>
 *   <li>{@code --node-id=<u32>}</li>
 * </ul>
 *
 * <p><b>Hostname verification is disabled</b> in this sample. The
 * typical field usage is connecting to an IP (e.g. a Tailscale / CGNAT
 * {@code 100.x.x.x} address) where the router's server cert only lists
 * hostnames as SANs -- the JDK's hostname verifier would reject on
 * IP-to-CN mismatch. Trust is still anchored to the CA root; changing
 * this is a one-line edit in {@code main()}.
 *
 * <p>Pairs with {@link ZenohJavaTlsSub} for a quick end-to-end mTLS
 * smoke test against a TLS-enabled {@code zenohd}.
 *
 * @author Marty
 * @implNote The private key file MUST be unencrypted. If yours is
 * password-protected, convert it once with:
 * {@code openssl pkcs8 -topk8 -nocrypt -in enc.pem -out plain.pem}
 * @implNote The main loop catches {@link Exception} and prints the
 * stack trace, but the JVM still exits {@code 0}. If you script this
 * from CI or Ant, wrap main() and set a non-zero exit code on failure.
 */
public class ZenohJavaTlsPub {

    /**
     * Program entry point.
     *
     * @param args positional CLI arguments plus optional named flags;
     * see class-level Javadoc for the contract.
     */
    public static void main(String[] args) {
        // ----- 1. Split args into named flags + positional list --------
        // Named flags parsed and stripped first so the positional
        // contract (endpoint / rootCa / cert / key / keyExpr / count /
        // interval) is not disturbed by flag placement.
        Priority          priority       = null;
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
                        System.err.println("[zenoh-java-tls-pub] Unknown flag: " + a);
                        System.err.println("  Expected one of --priority, --congestion, --express,"
                                + " --auto-timestamp, --node-id");
                        System.exit(2);
                        return;
                }
            } else {
                positional.add(a);
            }
        }

        // ----- 2. Required positional args (fail loud if missing) ------
        if (positional.size() < 4) {
            System.err.println("Usage:");
            System.err.println("  java -cp target/classes sample.zenoh.ZenohJavaTlsPub \\");
            System.err.println("    <endpoint> <rootCaCertPath> <clientCertPath> <clientKeyPath> \\");
            System.err.println("    [keyExpr] [count] [interval-ms] \\");
            System.err.println("    [--priority=<name>] [--congestion=<drop|block>] [--express] \\");
            System.err.println("    [--auto-timestamp] [--node-id=<u32>]");
            System.err.println();
            System.err.println("Example (Windows):");
            System.err.println("  java -cp target/classes sample.zenoh.ZenohJavaTlsPub ^");
            System.err.println("    tls/100.64.165.203:7447 ^");
            System.err.println("    \"d:\\DEV\\GOAT NET ONBOARDING\\efdi-onboarding\\efdi-ca-root.pem\" ^");
            System.err.println("    \"d:\\DEV\\GOAT NET ONBOARDING\\efdi-onboarding\\0472...-cert.pem\" ^");
            System.err.println("    \"d:\\DEV\\GOAT NET ONBOARDING\\efdi-onboarding\\0472...-key.pem\" ^");
            System.err.println("    demo/greeting 5 1000 --priority=real_time");
            System.exit(2);
            return;
        }

        String endpoint       = positional.get(0);
        String rootCaCertPath = positional.get(1);
        String clientCertPath = positional.get(2);
        String clientKeyPath  = positional.get(3);
        String key            = positional.size() > 4 ? positional.get(4) : "demo/greeting";
        int    count;
        long   interval;
        try {
            count = positional.size() > 5 ? Integer.parseInt(positional.get(5)) : 5;
        } catch (NumberFormatException nfe) {
            System.err.println("[zenoh-java-tls-pub] count must be a whole number, got '"
                    + positional.get(5) + "'");
            System.err.println("  Received argv: " + java.util.Arrays.toString(args));
            System.exit(3);
            return;
        }
        try {
            interval = positional.size() > 6 ? Long.parseLong(positional.get(6)) : 1_000L;
        } catch (NumberFormatException nfe) {
            System.err.println("[zenoh-java-tls-pub] interval-ms must be a whole number, got '"
                    + positional.get(6) + "'");
            System.err.println("  Received argv: " + java.util.Arrays.toString(args));
            System.exit(3);
            return;
        }

        // ----- 3. Compose Qos ------------------------------------------
        // Leave qos at DEFAULT if the caller passed no QoS flags at
        // all; that suppresses the QoS extension entirely so the wire
        // byte matches pre-QoS behaviour exactly.
        Qos qos;
        if (priority == null && congestion == CongestionControl.DROP && !express) {
            qos = Qos.DEFAULT;
        } else {
            qos = new Qos(priority != null ? priority : Priority.DATA, congestion, express);
        }

        // ----- 4. Banner -----------------------------------------------
        String pad = "                     ";
        System.out.println("[zenoh-java-tls-pub] endpoint=" + endpoint
                + "\n" + pad + "key=" + key
                + "\n" + pad + "count=" + count
                + "\n" + pad + "interval(mS)=" + interval
                + "\n" + pad + "rootCA=" + rootCaCertPath
                + "\n" + pad + "cert=" + clientCertPath
                + "\n" + pad + "key(pem)=" + clientKeyPath
                + "\n" + pad + "verifyHostname=false (typical for IP-only endpoints)"
                + "\n" + pad + "qos=" + (qos.isDefault() ? "DEFAULT (no ext)" : qos)
                + "\n" + pad + "autoTimestamp=" + autoTimestamp
                + "\n" + pad + "nodeId=" + nodeId
        );

        // ----- 5. Publish loop -----------------------------------------
        try {
            // mTLS builder: server-cert verification via rootCA PEM,
            // client-cert authentication via cert+key PEM pair.
            // verifyHostname(false) matches the sibling sub sample for
            // the typical IP-endpoint field case. All QoS / Timestamp /
            // NodeId knobs are opt-in; passing DEFAULT / false / 0
            // gives byte-identical wire output vs the pre-QoS
            // publisher.
            PureJavaZenohPublisher zenohPublisher = PureJavaZenohPublisher.builder()
                    .connectEndpoint(endpoint)
                    .keyExpr(key)
                    .rootCaCertPath(rootCaCertPath)
                    .clientCertPath(clientCertPath)
                    .clientKeyPath(clientKeyPath)
                    .verifyHostname(false)
                    .defaultQos(qos)
                    .autoTimestamp(autoTimestamp)
                    .originNodeId(nodeId)
                    .build();

            zenohPublisher.start();
            System.out.println("[zenoh-java-tls-pub] session OPEN (mTLS)");

            for (int i = 1; i <= count; i++) {
                String payload = "hello #" + i + " from pure-Java (mTLS)";
                zenohPublisher.publish(payload.getBytes(StandardCharsets.UTF_8));
                System.out.println("[zenoh-java-tls-pub] "
                        + i + "/" + count + " -> '" + payload + "'"
                        + " (sent=" + zenohPublisher.getSentCount() + ")");
                if (i < count) {
                    Thread.sleep(interval);
                }
            }

            System.out.println("[zenoh-java-tls-pub] done, closing");
            zenohPublisher.stop();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            ex.printStackTrace();
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("session open failed: handshake failure")
                    || msg.contains("SSL") || msg.contains("TLS")
                    || msg.contains("PKIX") || msg.contains("certificate")) {
                System.out.println("[zenoh-java-tls-pub] TLS/mTLS handshake diagnostics:\n"
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
}

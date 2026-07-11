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
 *
 * @author Marty
 */
public class ZenohJavaPubAnt {

    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 0 ? args[0] : "tcp/localhost:7447";
        String key = args.length > 1 ? args[1] : "demo/greeting";
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        long interval = args.length > 3 ? Long.parseLong(args[3]) : 1_000L;

        String pad = "                     ";
        System.out.println("[zenoh-java-ant-pub] endpoint=" + endpoint
                + "\n" + pad + "key=" + key
                + "\n" + pad + "count=" + count
                + "\n" + pad + "interval(mS)=" + interval
        );

        try {
            PureJavaZenohPublisher zenohPublisher = PureJavaZenohPublisher.builder()
                    .connectEndpoint(endpoint)
                    .keyExpr(key)
                    .build();

            zenohPublisher.start();
            System.out.println("[zenoh-java-ant-pub] session OPEN");

            for (int i = 1; i <= count; i++) {
                String payload = "hello #" + i + " from pure-Java";
                zenohPublisher.publish(payload.getBytes(StandardCharsets.UTF_8));
                System.out.println("[pure-java-simple-publisher] "
                        + i + "/" + count + " -> '" + payload + "'"
                        + " (sent=" + zenohPublisher.getSentCount() + ")");
                if (i < count) {
                    Thread.sleep(interval);
                }
            }

            System.out.println("[zenoh-java-ant-pub] done, closing");
        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }
}

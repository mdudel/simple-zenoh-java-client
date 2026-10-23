# simple-zenoh-java-client

A simple project using the pure-Java Zenoh client from
[java-zenoh-publisher](https://github.com/mdudel/java-zenoh-publisher).

It is a simple Maven project used to show how to create a simple Java
Zenoh Publisher, Subscriber and Scout (multicast discovery of nearby
routers and peers).

For the full list of builder options (TLS, mTLS, PEM/PKCS12 certs,
WebSocket, hostname verification, scout modes, etc.) see [API.md](API.md).

## API docs

HTML Javadoc is regenerated on every push to `main` and published to
GitHub Pages:

- **API reference**: https://mdudel.github.io/simple-zenoh-java-client/

Covers both `io.mdudel.zenoh.purejava.*` (the client) and
`sample.zenoh.*` (the runnable samples). The generator is
[.github/workflows/javadoc.yml](.github/workflows/javadoc.yml).

## Layout

Standard Maven layout (converted from Ant/NetBeans on 2026-07-29):

```
pom.xml
src/
├── main/java/
│   ├── io/mdudel/zenoh/purejava/       the pure-Java Zenoh client
│   └── sample/zenoh/
│       ├── ZenohJavaPub.java           minimal publisher sample
│       ├── ZenohJavaSub.java           minimal subscriber sample
│       ├── ZenohJavaScout.java         minimal scout / discovery sample
│       ├── ZenohJavaTlsPub.java        mTLS publisher with QoS support
│       └── ZenohJavaTlsSub.java        mTLS subscriber + topic-discovery mode
└── test/java/
    └── io/mdudel/zenoh/purejava/       unit tests (PemLoader, etc.)
```

Runtime dependencies: **zero** (pure JDK 17). Test scope: JUnit 5.

## Build & run

```
mvn -q package                              # compile + test + produce target/*.jar
java -cp target/classes sample.zenoh.ZenohJavaPub
```

That first command leaves a JAR in `target/`. That's the thing you
actually ship with: put it on your classpath, or `mvn install` it into
your local repo and depend on it, then write against the API the way
the code examples further down do. This is the path you want in almost
every case.

You can also skip the JAR and copy the `io/mdudel/zenoh/purejava`
source tree straight into your own project. It's pure JDK 17 with zero
runtime dependencies, so nothing tags along. The tradeoff is that your
build now owns this code. Compile times, warnings, and keeping up with
changes here all become your problem.

Or run the sample code (the `.bat` files use `target/classes`, so
`mvn compile` is enough):

```
runPub.bat    [endpoint] [key] [message] [count] [interval-ms]
runSub.bat    [endpoint] [keyExpr] [timeout-seconds]
runScout.bat  [mode] [interval-ms] [roles-csv] [timeout-seconds]
runTlsPub.bat [keyExpr] [count] [interval-ms] [--topic=<keyExpr>] [--flag=value ...]
                                                 # mTLS + QoS; cert paths hard-wired in the .bat
runTlsSub.bat [keyExpr] [timeout-seconds]        # mTLS; cert paths
                                                 # hard-wired in the .bat
```

### mTLS publisher with QoS

`runTlsPub.bat` (and the class it wraps, `ZenohJavaTlsPub`) connects
to a Zenoh router over TLS with client-certificate authentication,
reads the CA root, client certificate, and client key from PEM files,
and publishes a fixed number of messages to a key expression at a
configurable interval.

The topic (key expression) can be set three ways, in order of
priority: the `--topic=<keyExpr>` flag, the positional `keyExpr`
argument, or the batch file's own `TOPIC` default
(`996dfb6c880346559dff117458d27b66/zenoh-client/test-topic`) when
neither is given. When `--topic` is used, the positional slots shift:
the first two positional arguments become `count` and `interval-ms`
instead of `keyExpr` and `count`.

The priority (one of `control`, `real_time`, `interactive_high`,
`interactive_low`, `data_high`, `data`, `data_low`, `background`) can
be set through `--qos=<name>` or the older `--priority=<name>` (same
names, `--qos` wins if both are given), or falls back to the batch
file's own `QOS` default (`data`, which composes to `Qos.DEFAULT` and
emits no QoS extension at all, so nothing extra goes out on the wire).
Congestion control and the Express bit are separate flags:

```
runTlsPub.bat demo/hello 10 500
runTlsPub.bat --topic=skylord/tracks 20 250 --qos=real_time
runTlsPub.bat skylord/cmd 1 0 --qos=control --express
runTlsPub.bat bulk/dump 1 0 --priority=background --congestion=block
```

The resolved QoS also appears in each published message body (e.g.
`hello #1 from pure-Java (mTLS) [qos=DEFAULT (no ext)]`) so a
subscriber printing payloads can see what QoS the message was sent
with, not just the console banner on the publisher side.

The shipped `.bat` is hard-wired to the GOAT NET onboarding certs +
router (`tls/100.64.165.203:7447`); to point it elsewhere, either edit
the `set ROUTER=` / `set CERTDIR=` / `set CA=` / `set CERT=` /
`set KEY=` / `set TOPIC=` / `set QOS=` lines at the top of the batch
file, or invoke the class directly with all four required positional
arguments plus the optional ones:

```
java -cp target/classes sample.zenoh.ZenohJavaTlsPub ^
  tls/host:port ^
  path\to\rootCa.pem ^
  path\to\client-cert.pem ^
  path\to\client-key.pem ^
  [keyExpr] [count] [interval-ms] ^
  [--priority=<name>] [--congestion=<drop|block>] [--express] ^
  [--auto-timestamp] [--node-id=<u32>]
```

Hostname verification is disabled in the sample for the same reason as
`ZenohJavaTlsSub` (see below): the typical field endpoint is an IP
where the router's cert only lists hostnames as SANs. Trust is still
anchored to the CA root.

Pairs with `runTlsSub.bat` for a quick end-to-end mTLS smoke test.

### mTLS subscriber + topic discovery

`runTlsSub.bat` (and the class it wraps, `ZenohJavaTlsSub`) connects
to a Zenoh router over TLS with client-certificate authentication.
Zenoh has no topic-registry primitive, so "list topics" here means:
subscribe to a wildcard key expression, track every unique key that
receives at least one sample during the run, and print the sorted
summary on Ctrl-C or after the optional timeout.

The shipped `.bat` is hard-wired to the GOAT NET onboarding certs +
router (`tls/100.64.165.203:7447`); to point it elsewhere, either
edit the `set ROUTER=` / `set CERTDIR=` / `set CA=` / `set CERT=` /
`set KEY=` lines at the top of the batch file, or invoke the class
directly with all four positional arguments:

```
java -cp target/classes sample.zenoh.ZenohJavaTlsSub ^
  tls/host:port ^
  path\to\rootCa.pem ^
  path\to\client-cert.pem ^
  path\to\client-key.pem ^
  [keyExpr] [timeoutSeconds]
```

Hostname verification is disabled in the sample because the typical
field endpoint is an IP (Tailscale / CGNAT `100.x.x.x`) where the
server cert's CN/SAN would not match. Trust is still anchored to the
CA root; MITM protection is intact. Flip `.verifyHostname(true)` in
`ZenohJavaTlsSub` for hostname-based deployments.

Defaults assume a local `zenohd` on `tcp/localhost:7447`. On Windows,
if you hit `Connection refused: getsockopt`, use `tcp/[::1]:7447` or
start `zenohd` with `--listen tcp/0.0.0.0:7447 --listen tcp/[::]:7447`.

The scout sample uses UDP multicast (default `224.0.0.224:7446`) and
does NOT open any TCP session, so the `Connection refused` note above
does not apply. It has its own multicast-specific gotchas covered in
the scout section of [API.md](API.md).

## Publisher: core sample code

The minimal case needs two builder values, an endpoint and a key
expression. Everything else has a working default.

```java
PureJavaZenohPublisher zenohPublisher = PureJavaZenohPublisher.builder()
        .connectEndpoint("tcp/localhost:7447")
        .keyExpr("demo/greeting")
        .build();

zenohPublisher.start();          // TCP connect + Zenoh handshake

zenohPublisher.publish("hello from pure-Java"
        .getBytes(StandardCharsets.UTF_8));

zenohPublisher.stop();           // clean CLOSE (or use try-with-resources)
```

### Every builder parameter

This example sets every option the builder exposes. You would not
normally set all of them at once, because most defaults are already
correct. It is shown this way so the full surface is visible in one
place.

```java
try (PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()

        // ---- connection ------------------------------------------
        .connectEndpoint("tls/router.example.com:7447")  // required
        .keyExpr("zenoh-client/test-topic")              // default: demo/example/zenoh-java
        .org("996dfb6c880346559dff117458d27b66")         // optional key prefix
        .leaseMs(10_000L)                                // default: 10000

        // ---- TLS and mTLS ----------------------------------------
        // Only consulted for tls/ and wss/ endpoints.
        .rootCaCertPath("D:\\GOAT\\efdi-ca-root.pem")    // default: JVM trust store
        .clientCertPath("D:\\GOAT\\client-cert.pem")     // default: none (no mTLS)
        .clientKeyPath("D:\\GOAT\\client-key.pem")       // default: none (no mTLS)
        .keyStorePassword("changeit".toCharArray())      // PKCS12 only; default: changeit
        .verifyHostname(false)                           // default: true

        // ---- publisher-scope QoS defaults ------------------------
        .defaultQos(new Qos(Priority.DATA_HIGH,
                            CongestionControl.BLOCK,
                            true))                       // default: Qos.DEFAULT
        .autoTimestamp(true)                             // default: false
        .originNodeId(42L)                               // default: 0 (u32 range)

        .build()) {

    pub.start();

    byte[] payload = "hello from pure-Java".getBytes(StandardCharsets.UTF_8);

    // Publish to the effective key, using the publisher defaults above.
    pub.publish(payload);

    // Append a sub-key: <org>/<keyExpr>/alerts
    pub.publish("alerts", payload);

    // Override just the priority for one message.
    pub.publish("alerts", payload, Priority.CONTROL);

    // Override the whole QoS triple for one message.
    pub.publish("alerts", payload, Qos.of(Priority.BACKGROUND));

    // Full control: QoS, Timestamp, and NodeId, all explicit.
    pub.publish("alerts", payload, Qos.DEFAULT, null, 0L);

    // UTF-8 string convenience wrapper.
    pub.publishString("alerts", "hello from pure-Java");
}
```

Builder reference:

| Method | Default | Notes |
| --- | --- | --- |
| `connectEndpoint(String)` | none, required | `tcp/`, `tls/`, `ws/`, or `wss/` followed by `host:port`. Full `ws://` and `wss://` URIs with a path are also accepted. |
| `keyExpr(String)` | `demo/example/zenoh-java` | The key to publish on, before any `org` prefix. |
| `org(String)` | empty | Prefix joined to `keyExpr` with a single slash. Read the result with `getEffectiveKeyExpr()`. |
| `leaseMs(long)` | `10000` | Lease proposed to the router during the handshake. |
| `rootCaCertPath(String)` | empty | CA root as PEM (`.pem`, `.crt`, `.cer`) or PKCS12 (`.p12`, `.pfx`). When empty, the JVM default trust store is used. |
| `clientCertPath(String)` | empty | Client certificate for mTLS. |
| `clientKeyPath(String)` | empty | Client private key for mTLS. For PEM you must set both this and `clientCertPath`. For PKCS12 set both to the same `.p12` file. |
| `keyStorePassword(char[])` | `changeit` | Used for PKCS12 stores only. PEM files need no password. |
| `verifyHostname(boolean)` | `true` | Set to `false` when connecting to a bare IP whose server certificate lists only hostnames as SANs. Trust is still anchored to the CA root. |
| `defaultQos(Qos)` | `Qos.DEFAULT` | Priority, congestion control, and the Express bit, applied to every publish call unless overridden. |
| `defaultPriority(Priority)` | `Priority.DATA` | Convenience wrapper for `defaultQos(Qos.of(p))`. |
| `autoTimestamp(boolean)` | `false` | When true, every message carries a fresh timestamp built from the wall clock and the session ZenohId. |
| `originNodeId(long)` | `0` | Routing origin as a u32. Values outside `0..4294967295` throw `IllegalArgumentException`. |

`Priority` has eight values, listed here from highest to lowest:
`CONTROL`, `REAL_TIME`, `INTERACTIVE_HIGH`, `INTERACTIVE_LOW`,
`DATA_HIGH`, `DATA`, `DATA_LOW`, `BACKGROUND`. `CongestionControl` has
two, `DROP` and `BLOCK`.

The QoS, timestamp, and node-id settings are all opt-in. Leaving them
at their defaults suppresses the corresponding wire extension entirely,
so the bytes on the wire are identical to those produced before QoS
support was added. Note that `Qos.isDefault()` compares by value, so
explicitly setting `Priority.DATA` with `CongestionControl.DROP` and
Express off also emits no extension.

## Subscriber: core sample code

```java
PureJavaZenohSubscriber zenohSubscriber = PureJavaZenohSubscriber.builder()
        .connectEndpoint("tcp/localhost:7447")
        .build();

zenohSubscriber.start();         // TCP connect + Zenoh handshake

zenohSubscriber.subscribeAndConsume("demo/**", sample ->
        System.out.println(sample.key() + " -> " + sample.payloadAsString()));

// ... park the main thread (CountDownLatch, Thread.sleep, whatever) ...

zenohSubscriber.stop();          // clean CLOSE
```

See [`ZenohJavaPub.java`](src/main/java/sample/zenoh/ZenohJavaPub.java)
and [`ZenohJavaSub.java`](src/main/java/sample/zenoh/ZenohJavaSub.java)
for the full runnable versions with CLI args, error handling, and TLS
notes in the class Javadoc.

## Topic discovery: listing active topics

Zenoh doesn't have a topic registry. There's no list to fetch and no
call that'll hand you one, so anybody promising you a definitive answer
is making it up.

What this client does instead is watch four different things and merge
them. Each one sees a slice of the network, each one is blind to
something else, and every topic you get back is tagged with which ones
saw it. That way you can decide for yourself how much to trust it.

| Source | What it proves | What it misses |
| --- | --- | --- |
| `DECLARED_SUBSCRIBER` | Someone is listening on this key. | A key with a publisher but no subscriber. |
| `DECLARED_QUERYABLE` | Someone answers queries on this key. | Anything that isn't a queryable. |
| `LIVENESS_TOKEN` | A peer is explicitly saying "I'm alive here". | Apps that never bother declaring tokens. |
| `OBSERVED_TRAFFIC` | Data is really flowing on this key. | Any topic that stayed quiet while you were watching. |

### Always-on recording (start here)

Flip one builder flag and the subscriber keeps a running tally of every
topic it sees. Then just ask it, whenever, no blocking:

```java
PureJavaZenohSubscriber zenohSubscriber = PureJavaZenohSubscriber.builder()
        .connectEndpoint("tcp/localhost:7447")
        .recordTopics(true)              // record for the life of the subscriber
        .recordTopicsPattern("**")       // default: everything
        .recordTopicTraffic(true)        // default: also watch real messages
        .build();

zenohSubscriber.start();                 // recording begins here

// ... minutes or hours later, from any thread ...

for (TopicInfo t : zenohSubscriber.recordedTopics()) {
    System.out.printf("%-40s %-9s %-12s %s%n",
            t.keyExpr(),
            t.active() ? "active" : "gone",
            t.hasTraffic() ? t.sampleCount() + " samples" : "no traffic",
            t.sources());
}
```

You get everything seen since `start()`, not just whatever happens to
be there the second you ask. A topic that was hammering away an hour
ago and has gone quiet since? Still in the list, with `active()` false
once its declarations get withdrawn. Want only what's still there right
now? `zenohSubscriber.topicRegistry().activeOnly()`.

This is the one to reach for. The longer your process runs, the better
the answer gets, which is sort of the whole trick. You can also toggle
it at runtime with `startRecordingTopics()` and `stopRecordingTopics()`,
and `isRecordingTopics()` tells you where you stand.

Now, a router might let you open a session and still refuse the
discovery interest or the wildcard subscription. If that happens,
`start()` logs a warning and keeps going instead of blowing up, because
your actual subscriptions shouldn't die just because discovery isn't
allowed. `recordedTopics()` will throw `IllegalStateException` in that
case. That's on purpose. An empty list would look exactly like "I
looked and found nothing", and that's a lie.

### Just asking once

Don't want a recorder sitting there? Block for a window and take what
you get:

```java
List<TopicInfo> topics = zenohSubscriber.listTopics("**", Duration.ofSeconds(5));
```

You'll see what showed up in those five seconds. That's it. The timeout
isn't optional, and there's no way around that: traffic observation has
no finish line. A topic sitting silent looks identical to a topic that
doesn't exist. Wait longer, find more. The answer is always a floor,
never proof that something isn't out there. Which is exactly why the
recorder above is the better habit.

### Polling a live view

For "what's out there this instant", with departures dropping off as
they happen:

```java
try (TopicRegistry registry = zenohSubscriber.topics("**", true)) {
    registry.awaitCurrent(Duration.ofSeconds(2));   // router finished the replay
    for (TopicInfo t : registry.snapshot()) {
        System.out.println(t);
    }
}
```

Registries come in two flavours. Live ones drop a topic once its last
declaration goes away. Cumulative ones never drop anything.
`topics(pattern, observeTraffic)` hands you a live one, the recorder
uses a cumulative one, and `topics(pattern, observeTraffic, cumulative)`
lets you say which. Observed traffic sticks around either way, because
a message that genuinely arrived isn't something a later event gets to
take back.

One gotcha that applies to all of these. That `observeTraffic` boolean
decides whether a real subscription gets opened. Point a broad pattern
at a busy network and you're hauling in every matching message just to
read its key off the top. An ACL might also let you discover but not
subscribe. Pass `false` and you stay passive, living off declarations
alone.

All of it works the same over plain TCP, TLS, mTLS, and WebSocket.
Discovery rides your ordinary session, so you don't need the router's
admin space or its REST plugin.

## Scout: core sample code

The scout is a passive/active UDP-multicast observer. It never opens
a Zenoh session, never sends INIT, and does not appear as a peer on
the routers it discovers.

```java
PureJavaZenohScout zenohScout = PureJavaZenohScout.builder()
        .mode(PureJavaZenohScout.Mode.ACTIVE)      // or PASSIVE for listen-only
        .scoutIntervalMillis(3_000)                // SCOUT emit rate in ACTIVE
        .whatAmIMatcher(WhatAmIMatcher.any())      // any role: router, peer, client
        .listener(new ScoutListener() {
            @Override public void onDiscover(DiscoveredNode n) {
                System.out.println("+ " + n.role() + " " + n.zid()
                        + " at " + n.bestLocator());
            }
            @Override public void onExpire(DiscoveredNode n) {
                System.out.println("- " + n.zid() + " gone");
            }
        })
        .build();

zenohScout.start();          // join 224.0.0.224:7446 on every up NIC

// ... park the main thread (CountDownLatch, Thread.sleep, whatever) ...

// Snapshot pull-API works alongside the callback stream, any time:
for (DiscoveredNode n : zenohScout.snapshot()) {
    System.out.println(n.role() + " " + n.zid() + " " + n.locators());
}

zenohScout.close();          // idempotent; safe from any thread
```

See [`ZenohJavaScout.java`](src/main/java/sample/zenoh/ZenohJavaScout.java)
for the full runnable version with CLI args, error handling, and the
Windows firewall / loopback-multicast gotchas in the class Javadoc.

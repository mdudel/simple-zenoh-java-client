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

Or run the platform helpers (they use `target/classes`, so
`mvn compile` is enough):

```
runPub.bat    [endpoint] [key] [message] [count] [interval-ms]
runSub.bat    [endpoint] [keyExpr] [timeout-seconds]
runScout.bat  [mode] [interval-ms] [roles-csv] [timeout-seconds]
runTlsSub.bat [keyExpr] [timeout-seconds]        # mTLS; cert paths
                                                 # hard-wired in the .bat
```

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

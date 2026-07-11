# API reference: Publisher & Subscriber options

This page documents every builder option on `PureJavaZenohPublisher` and
`PureJavaZenohSubscriber`, plus examples for TCP, TLS, mTLS, WebSocket,
and the certificate formats the client accepts.

For a quick-start "just show me the code" version, see the
[README](README.md). For end-to-end runnable samples, see
[`ZenohJavaPubAnt.java`](src/sample/nb/ant/zenoh/ZenohJavaPubAnt.java)
and [`ZenohJavaSubAnt.java`](src/sample/nb/ant/zenoh/ZenohJavaSubAnt.java).

---

## Endpoint syntax

Both client classes take a single `connectEndpoint(String)` in either
the classic Zenoh form or a URI:

| Scheme | Classic form         | URI form                        | Transport      | TLS args required |
|--------|----------------------|---------------------------------|----------------|-------------------|
| `tcp`  | `tcp/host:port`      | —                               | Plain TCP      | no                |
| `tls`  | `tls/host:port`      | —                               | TCP + TLS      | see below         |
| `ws`   | `ws/host:port`       | `ws://host:port/path`           | WebSocket      | no                |
| `wss`  | `wss/host:port`      | `wss://host:port/path`          | WebSocket + TLS| see below         |

Any other scheme throws `IOException("unsupported endpoint scheme …")`.

---

## Builder options: Publisher

`PureJavaZenohPublisher.builder()`:

| Method                                    | Default                        | Purpose |
|-------------------------------------------|--------------------------------|---------|
| `connectEndpoint(String)`                 | *(required)*                   | Router endpoint. See table above. |
| `keyExpr(String)`                         | `demo/example/zenoh-java`      | Base key expression that `publish(byte[])` sends to. |
| `org(String)`                             | `""`                           | Optional key-expression prefix; effective key becomes `org/keyExpr` via `KeyExpr.resolveKey`. |
| `rootCaCertPath(String)`                  | `""` (JVM default trust store) | Trust anchor for the router's cert. PEM or PKCS12, auto-detected by extension. |
| `clientCertPath(String)`                  | `""`                           | Client cert chain for mTLS. PEM `.pem`/`.crt`/`.cer`, or a PKCS12 `.p12`/`.pfx`. |
| `clientKeyPath(String)`                   | `""`                           | Client private key for mTLS. PEM `.key`/`.pem`, or same `.p12` as `clientCertPath`. |
| `keyStorePassword(char[])`                | `"changeit"`                   | Password for PKCS12 trust and key stores. Ignored for PEM. |
| `verifyHostname(boolean)`                 | `true`                         | Verify the router's cert SAN against the endpoint host. Turn off ONLY for lab/dev. |
| `leaseMs(long)`                           | `ZenohSession.DEFAULT_LEASE_MS`| Session lease (keepalive interval). |

## Builder options: Subscriber

`PureJavaZenohSubscriber.builder()`:

Identical to the publisher **minus `keyExpr`** (the subscriber takes
the key expression per-subscription, not at build time). Same TLS/mTLS
options, same defaults, same `org` prefix behaviour.

| Method                                    | Default                        | Purpose |
|-------------------------------------------|--------------------------------|---------|
| `connectEndpoint(String)`                 | *(required)*                   | Router endpoint. |
| `org(String)`                             | `""`                           | Prefix applied to every `subscribe(keyExpr)` call. |
| `rootCaCertPath(String)`                  | `""` (JVM default)             | Trust anchor. |
| `clientCertPath(String)`                  | `""`                           | Client cert for mTLS. |
| `clientKeyPath(String)`                   | `""`                           | Client key for mTLS. |
| `keyStorePassword(char[])`                | `"changeit"`                   | PKCS12 password. |
| `verifyHostname(boolean)`                 | `true`                         | Hostname verification. |
| `leaseMs(long)`                           | `ZenohSession.DEFAULT_LEASE_MS`| Session lease. |

---

## Certificate formats

The client auto-detects PEM vs PKCS12 by file extension. There is no
setting to choose the format: it is decided from the file name, so
just point at the right file.

| Extension                       | Treated as | Password used? |
|---------------------------------|------------|----------------|
| `.pem`, `.crt`, `.cer`, `.key`  | PEM        | no             |
| `.p12`, `.pfx`                  | PKCS12     | yes (`keyStorePassword`) |

Anything else throws `IOException` at `start()`.

### Trust store rules
- `rootCaCertPath` empty → JVM default trust store (`$JAVA_HOME/lib/security/cacerts`).
- `rootCaCertPath` set to PEM → load one or more CA certs from that PEM (or bundle).
- `rootCaCertPath` set to PKCS12 → load as a trust-only PKCS12.

### Client keystore (mTLS) rules
Three legal shapes:

1. **PEM pair**: set both `clientCertPath` (`.pem`/`.crt`/`.cer`) and
   `clientKeyPath` (`.key`/`.pem`).
2. **PKCS12 combined**: set BOTH `clientCertPath` and `clientKeyPath`
   to the **same** `.p12` file.
3. **PKCS12 with one side set**: set exactly one of
   `clientCertPath` or `clientKeyPath` to a `.p12`; the client treats
   it as a combined keystore. Password: `keyStorePassword`.

Illegal shapes throw `IOException` at `start()` with an explicit
"you probably meant X" message.

---

## Examples

### 1. Plain TCP (no TLS)

```java
PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
        .connectEndpoint("tcp/router.example:7447")
        .keyExpr("demo/telemetry")
        .build();
pub.start();
pub.publish("payload".getBytes(StandardCharsets.UTF_8));
pub.stop();
```

### 2. TLS with a private CA (PEM)

```java
PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
        .connectEndpoint("tls/router.example:7447")
        .rootCaCertPath("/etc/pki/private-ca.pem")
        .build();
sub.start();
sub.subscribeAndConsume("demo/**", s ->
        System.out.println(s.key() + " -> " + s.payloadAsString()));
```

### 3. TLS with the JVM's default trust store

Omit `rootCaCertPath` entirely. Useful when the router presents a cert
chain that a public CA (already in `cacerts`) can validate.

```java
PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
        .connectEndpoint("tls/router.public.example:7447")
        .keyExpr("demo/greeting")
        .build();
pub.start();
```

### 4. mTLS with a PEM pair

```java
PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
        .connectEndpoint("tls/router.example:7447")
        .rootCaCertPath("/etc/pki/router-ca.pem")
        .clientCertPath("/etc/pki/client.pem")
        .clientKeyPath ("/etc/pki/client.key")
        .keyExpr("demo/telemetry")
        .build();
pub.start();
```

### 5. mTLS with a combined PKCS12

Same `.p12` referenced from both sides; password picked from
`keyStorePassword`.

```java
PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
        .connectEndpoint("tls/router.example:7447")
        .rootCaCertPath  ("/etc/pki/router-ca.p12")   // trust
        .clientCertPath  ("/etc/pki/client.p12")      // key material
        .clientKeyPath   ("/etc/pki/client.p12")      // same file
        .keyStorePassword("s3cret".toCharArray())
        .keyExpr("demo/telemetry")
        .build();
pub.start();
```

### 6. WebSocket, plaintext

```java
PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
        .connectEndpoint("ws://router.example:8000/zenoh")
        .build();
sub.start();
```

### 7. WebSocket over TLS (wss) with mTLS

```java
PureJavaZenohPublisher pub = PureJavaZenohPublisher.builder()
        .connectEndpoint("wss://router.example:8443/zenoh")
        .rootCaCertPath("/etc/pki/router-ca.pem")
        .clientCertPath("/etc/pki/client.pem")
        .clientKeyPath ("/etc/pki/client.key")
        .keyExpr("demo/telemetry")
        .build();
pub.start();
```

### 8. Lab / dev: skip hostname verification

**Never do this against production.** Only useful when the router
presents a cert whose SAN doesn't match the endpoint you're using
(e.g. connecting via a jump host).

```java
PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
        .connectEndpoint("tls/10.0.0.5:7447")
        .rootCaCertPath("/etc/pki/router-ca.pem")
        .verifyHostname(false)
        .build();
```

---

## Publisher API: beyond `start()` / `publish()`

| Method                                    | Returns  | Notes |
|-------------------------------------------|----------|-------|
| `publish(byte[] data)`                    | `void`   | Publishes to the effective key (`org/keyExpr`). |
| `publish(String subKey, byte[] data)`     | `void`   | Publishes to `effectiveKeyExpr/subKey` (single session, many keys). |
| `publishString(String subKey, String s)`  | `void`   | UTF-8 convenience wrapper. |
| `getSentCount()`                          | `long`   | Monotonic counter, best-effort. |
| `getLastSendMs()`                         | `long`   | `System.currentTimeMillis()` of last successful publish. |
| `getEffectiveKeyExpr()`                   | `String` | Result of `KeyExpr.resolveKey(org, keyExpr)`. |
| `isActive()`                              | `boolean`| `true` when the session state is `OPEN`. |
| `getLastError()`                          | `String` | Last recorded failure message, or `null`. |
| `stop()` / `close()`                      | `void`   | Sends CLOSE, tears down transport. Safe to call more than once (no-op after the first). Implements `AutoCloseable`. |

---

## Subscriber API: beyond `start()` / `subscribeAndConsume()`

The subscriber gives you two ways to receive samples:

- **Push (callback)**: you hand in a function, and the client calls it
  for every sample. Zero threading code on your side.
- **Pull (loop)**: you get a `Subscription` handle and call
  `take()` / `poll(...)` on it yourself.

Both use the same underlying queue. Pick whichever fits your app.

### Push style: your own callback function

`subscribeAndConsume(keyExpr, callback)` IS the callback path. The
second argument is any `Consumer<Sample>`, meaning a plain lambda, a
method reference, or a class that implements the interface. The client
spins up a daemon thread and calls your function once per sample; the
`subscribeAndConsume` call itself returns immediately.

```java
// Lambda form -- what the sample uses.
subscriber.subscribeAndConsume("demo/**", sample ->
        System.out.println(sample.key() + " -> " + sample.payloadAsString()));

// Method reference to your own handler.
subscriber.subscribeAndConsume("demo/**", MyApp::handleSample);

// Or a full class if the handler carries state.
subscriber.subscribeAndConsume("demo/**", new Consumer<Sample>() {
    @Override public void accept(Sample s) {
        myBuffer.add(s);
        myMetrics.increment();
    }
});
```

Rules for the callback:
- It runs on the subscription's daemon thread, NOT the caller's.
- Don't block inside it: slow callbacks back up the inbound queue and
  eventually stall the whole session. If work is heavy, hand samples
  off to your own `ExecutorService` inside the callback.
- Exceptions thrown from the callback are logged and swallowed; the
  subscription keeps running.

If you want the same push behaviour but already have a `Subscription`
handle from `subscribe(keyExpr)`, call `sub.forEach(callback)`; it
does exactly what `subscribeAndConsume` does under the hood.

### Pull style (caller drives the loop)

```java
Subscription sub = subscriber.subscribe("demo/**");
while (running) {
    Sample s = sub.take();              // blocks
    // Sample s = sub.poll(500, TimeUnit.MILLISECONDS);  // or with timeout
    myOwnHandler(s.key(), s.payload()); // <- your code, whatever you call it
}
sub.close();
```

`Subscription` methods:

| Method                              | Returns  | Notes |
|-------------------------------------|----------|-------|
| `take()`                            | `Sample` | Blocks until a sample arrives. Throws `InterruptedException`. |
| `poll(long timeout, TimeUnit unit)` | `Sample` | Returns `null` on timeout. |
| `forEach(Consumer<Sample>)`         | `void`   | Push-style; starts a daemon thread pumping samples into the callback. `subscribeAndConsume` calls this internally. |
| `receivedCount()`                   | `long`   | Cumulative for THIS subscription. |
| `isOpen()`                          | `boolean`| |
| `close()`                           | `void`   | Sends UNDECLARE_SUBSCRIBER, drains the queue. Safe to call more than once. |
| `id()` / `keyExpr()`                | `long` / `KeyExpr` | Diagnostic. |

`Sample`:

| Method             | Returns    | Notes |
|--------------------|------------|-------|
| `key()`            | `String`   | Full resolved key the router matched. |
| `payload()`        | `byte[]`   | Returns a defensive clone; safe to mutate. |
| `payloadAsString()`| `String`   | UTF-8 decode. |
| `encoding()`       | `Encoding` | Encoding hint from the sender (may be null / unknown). |

### Topic discovery (find what's out there)

```java
try (PureJavaZenohSubscriber.TopicDiscovery td =
         subscriber.discoverTopics("demo/**", new PureJavaZenohSubscriber.TopicListener() {
             @Override public void onTopicDeclared(String key, long id) {
                 System.out.println("+ " + key);
             }
             @Override public void onTopicUndeclared(long id) {
                 System.out.println("- id=" + id);
             }
             @Override public void onDiscoveryComplete() {
                 System.out.println("(replay done, watching live)");
             }
         })) {
    Thread.sleep(30_000);
}
```

- Fires once per `DeclareSubscriber` the router knows about matching
  the pattern, then `onDiscoveryComplete()`, then live add/remove
  events until you `close()` the returned handle.
- Shows **subscriptions**, not publishers. If nobody is subscribing
  to a key, it won't appear here even if there is an active publisher.
  To catch publishers, subscribe to `**` and observe `Sample.key()`.
- Sugar: `discoverAllTopics(listener)` is exactly
  `discoverTopics("**", listener)`.

---

## Threading

Both client classes are safe to share across threads once `start()` has
returned:

- `publish(...)`: safe from any thread.
- `subscribe(...)`: safe from any thread; each returned `Subscription`
  has its own queue.
- `Subscription.take()` / `.poll()`: one consumer thread per
  subscription is the sane pattern; multiple consumers race on the
  same queue and each sample only goes to one of them.
- `Subscription.forEach(...)`: starts an internal daemon thread; do
  not block inside the callback (back-pressures the inbound queue).
- `close()` / `stop()`: safe to call from any thread, and safe to call
  more than once (extra calls are no-ops).

---

## Errors you will actually see

| Message (substring)                                                             | Cause / fix |
|---------------------------------------------------------------------------------|-------------|
| `connectEndpoint is required`                                                   | Builder was built without `connectEndpoint(...)`. |
| `unsupported endpoint scheme '…'`                                               | Typo or unsupported proto. Only `tcp`/`tls`/`ws`/`wss` are legal. |
| `session open failed: handshake failure: connect failed to tcp`                 | Router unreachable. Windows: try `tcp/[::1]:7447`; see the class Javadoc on the sample. |
| `session open failed: InitAck not received`                                     | You connected `tls/...` to a plaintext router, or `tcp/...` to a TLS router. Match the scheme to the router config. |
| `rootCaCertPath must end in .pem/.crt/.cer (PEM) or .p12/.pfx (PKCS12)`         | Unknown extension: rename or convert. |
| `for PKCS12 client keystore, set clientCertPath and clientKeyPath to the SAME .p12 file` | Point both at the one `.p12` (or leave one empty). |
| `PEM client authentication requires BOTH clientCertPath AND clientKeyPath`      | You gave a `.pem` cert without the matching `.key`. |
| `PureJavaZenohPublisher is not started`                                         | Called `publish` before `start()`, or after `stop()`. |

---

## What is NOT (yet) exposed on the client class

For advanced TLS settings (enabled protocols, cipher suites, handshake
timeout, PKCS11) you drop below the client class and build a
`TlsConfig` directly, then construct `TlsTransport` / `WsTransport`
by hand. See `TlsConfig.Builder` in the source
(`src/io/mdudel/zenoh/purejava/transport/TlsConfig.java`); the
builder methods there include `enabledProtocols(...)`,
`enabledCipherSuites(...)`, `handshakeTimeoutMs(...)`,
`needClientAuth(...)`, `trustStoreType(...)`, `keyStoreType(...)`.

If you find yourself needing one of these often, ping mdudel to
promote it onto the client builder, or open an issue on the repo;
anybody is welcome to. Response is not guaranteed to be timely: this
is coded at home, in my underwear, while drinking beer.

---

## What is missing vs the official Zenoh client

This pure-Java client covers the everyday publish / subscribe use case
against a Zenoh 1.x router. It is intentionally NOT a feature-complete
replacement for the official Rust/JNI Zenoh client. If your app needs
any of the items below, use the JNI-backed sibling in
[java-zenoh-publisher](https://github.com/mdudel/java-zenoh-publisher)
instead.

**Transports not supported:**
- **QUIC** (`quic/`, `quic://`): official Zenoh supports QUIC as a
  first-class transport; this client does not.
- **UDP unicast / multicast**: the official client can peer over UDP
  and multicast; this client is TCP/TLS/WS/WSS only.
- **Unix domain sockets** (`unixsock-stream/...`).
- **Serial / VSOCK** and other exotic transports the Rust client
  exposes via feature flags.

**Zenoh features not implemented:**
- **Peer mode**: this client only speaks Zenoh **client** mode
  (connects to a router). It cannot act as a peer or a router itself.
- **Multicast discovery / scouting**: no gossip discovery; you must
  point `connectEndpoint` at a known router.
- **Queryables and `get()` queries**: you can publish and subscribe,
  but you cannot serve or issue Zenoh queries.
- **Liveliness tokens**: declare / undeclare liveliness is not
  exposed.
- **Storage / replication / alignment**: no built-in storage
  backends, no replication protocol, no eventual-consistency alignment.
- **Attachments** on publications (per-sample metadata beyond payload
  + encoding).
- **Priority, congestion-control, and reliability settings** on
  publish: samples go with router defaults.
- **Batching / compression**: samples are sent one PUSH per call;
  the official client can batch and optionally compress.
- **Access-control / authentication tokens** beyond mTLS: no
  username/password auth, no OIDC.
- **Session recovery / auto-reconnect**: if the transport dies you
  get an `IOException` on the next publish and must call `stop()` +
  `start()` yourself.
- **Shared-memory transport**: the SHM optimisation is native-only.

**Wire-protocol coverage:**
- Implements Zenoh 1.x INIT / OPEN / CLOSE / KEEPALIVE / FRAME / PUSH /
  DECLARE (subscribers only) / UNDECLARE / INTEREST (for topic
  discovery). Everything else is out of scope.
- Extension fields on messages are decoded when needed and otherwise
  passed through unchanged; unknown extensions do not fail parsing.

**When to use which:**

| You need…                                       | This client | JNI (official) |
|-------------------------------------------------|:-----------:|:--------------:|
| Publish + subscribe over TCP/TLS/WS/WSS         | ✅          | ✅             |
| mTLS with PEM or PKCS12                         | ✅          | ✅             |
| Any-JDK-17 platform (Apple Silicon, ARM, etc.)  | ✅          | ⚠ x86_64 only |
| Zero native binaries / accreditable source-only | ✅          | ❌             |
| QUIC / UDP / multicast                          | ❌          | ✅             |
| Peer or router mode                             | ❌          | ✅             |
| Queryables + get queries                        | ❌          | ✅             |
| Storage / replication                           | ❌          | ✅             |
| Shared-memory transport                         | ❌          | ✅             |

# simple-zenoh-java-client

A simple project using the pure-Java Zenoh client from
[java-zenoh-publisher](https://github.com/mdudel/java-zenoh-publisher).

It is a simple ANT project used to show how to create a simple Java
Zenoh Publisher and Subscriber.

For the full list of builder options (TLS, mTLS, PEM/PKCS12 certs,
WebSocket, hostname verification, etc.) see [API.md](API.md).

## Layout

```
src/
├── io/mdudel/zenoh/purejava/          the pure-Java Zenoh client
└── sample/nb/ant/zenoh/
    ├── ZenohJavaPubAnt.java           minimal publisher sample
    └── ZenohJavaSubAnt.java           minimal subscriber sample
```

## Build & run

```
ant clean jar
java -jar dist/ZenohJavaAnt.jar          # runs whichever main-class is set in manifest.mf
```

Or run the platform helpers:

```
runPub.bat [endpoint] [key] [count] [interval-ms]
runSub.bat [endpoint] [keyExpr] [timeout-seconds]
```

Defaults assume a local `zenohd` on `tcp/localhost:7447`. On Windows,
if you hit `Connection refused: getsockopt`, use `tcp/[::1]:7447` or
start `zenohd` with `--listen tcp/0.0.0.0:7447 --listen tcp/[::]:7447`.

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

See [`ZenohJavaPubAnt.java`](src/sample/nb/ant/zenoh/ZenohJavaPubAnt.java)
and [`ZenohJavaSubAnt.java`](src/sample/nb/ant/zenoh/ZenohJavaSubAnt.java)
for the full runnable versions with CLI args, error handling, and TLS
notes in the class Javadoc.

/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.session;

import io.mdudel.zenoh.purejava.wire.Extension;
import java.util.List;

/** Optional hook for Zenoh transport-authentication handshake extensions. */
public interface SessionAuthenticator {
    List<Extension> initSynExtensions() throws SessionException;
    void receiveInitAck(List<Extension> extensions) throws SessionException;
    List<Extension> openSynExtensions() throws SessionException;
    void receiveOpenAck(List<Extension> extensions) throws SessionException;
}

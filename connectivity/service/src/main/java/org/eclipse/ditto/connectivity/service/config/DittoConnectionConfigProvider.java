/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.connectivity.service.config;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.signals.events.Event;
import org.eclipse.ditto.connectivity.model.ConnectionId;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;

/**
 * Default implementation of {@link ConnectionConfigProvider} which simply builds and returns a
 * {@link ConnectivityConfig}.
 */
public class DittoConnectionConfigProvider implements ConnectionConfigProvider {

    private final ActorSystem actorSystem;

    public DittoConnectionConfigProvider(final ActorSystem actorSystem) {
        this.actorSystem = actorSystem;
    }

    @Override
    public CompletionStage<Config> getConnectivityConfigOverwrites(final ConnectionId connectionId) {
        return CompletableFuture.completedFuture(ConfigFactory.empty());
    }

    @Override
    public CompletionStage<ConnectivityConfig> getConnectivityConfig(final ConnectionId connectionId) {
        return getConnectivityConfigOverwrites(connectionId)
                .thenApply(overwrites -> {
                    final Config defaultConfig = actorSystem.settings().config();
                    final Config withOverwrites = overwrites.withFallback(defaultConfig);
                    return ConnectivityConfig.of(withOverwrites);
                });
    }

    @Override
    public CompletionStage<Void> registerForConnectivityConfigChanges(final ConnectionId connectionId,
            final ActorRef subscriber) {
        // nothing to do, config changes are not supported by the default implementation
        return CompletableFuture.completedStage(null);
    }

    @Override
    public boolean canHandle(final Event<?> event) {
        return false;
    }

    @Override
    public Optional<Config> handleEvent(final Event<?> event) {
        return Optional.empty();
    }

}

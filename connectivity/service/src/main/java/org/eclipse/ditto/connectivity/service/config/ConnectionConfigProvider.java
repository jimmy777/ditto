/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
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
import java.util.concurrent.CompletionStage;

import org.atteo.classindex.IndexSubclasses;
import org.eclipse.ditto.base.model.signals.events.Event;
import org.eclipse.ditto.connectivity.model.ConnectionId;

import com.typesafe.config.Config;

import akka.actor.ActorRef;

/**
 * Provides methods to load {@link ConnectivityConfig} and register for changes to {@link ConnectivityConfig}.
 */
@IndexSubclasses
public interface ConnectionConfigProvider {

    CompletionStage<Config> getConnectivityConfigOverwrites(ConnectionId connectionId);

    /**
     * Loads a {@link ConnectivityConfig} by a connection ID.
     *
     * @param connectionId the connection id for which to load the {@link ConnectivityConfig}
     * @return the future connectivity config
     */
    CompletionStage<ConnectivityConfig> getConnectivityConfig(ConnectionId connectionId);

    /**
     * Register the given {@code subscriber} for changes to the {@link ConnectivityConfig} of the given {@code
     * connectionId}. The given {@link ActorRef} will receive {@link Event}s to build the modified
     * {@link ConnectivityConfig}.
     *
     * @param connectionId the connection id
     * @param subscriber the subscriber that will receive {@link org.eclipse.ditto.base.model.signals.events.Event}s
     * @return a future that succeeds or fails depending on whether registration was successful.
     */
    CompletionStage<Void> registerForConnectivityConfigChanges(ConnectionId connectionId, ActorRef subscriber);

    /**
     * Returns {@code true} if the implementation can handle the given {@code event} to generate a modified {@link
     * ConnectivityConfig} when passed to {@link #handleEvent(Event)}.
     *
     * @param event the event that may be used to generate modified config
     * @return {@code true} if the event is compatible
     */
    boolean canHandle(Event<?> event);

    /**
     * Uses the given {@code event} to create a config which should overwrite the default connectivity config.
     *
     * @param event the event used to create a config which should overwrite the default connectivity config.
     * @return Potentially empty config which holds the overwrites for the default connectivity config.
     */
    Optional<Config> handleEvent(Event<?> event);

}

/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.connectivity.service.messaging.httppush;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.eclipse.ditto.connectivity.service.messaging.TestConstants.Authorization.AUTHORIZATION_CONTEXT;
import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.connectivity.model.Connection;
import org.eclipse.ditto.connectivity.model.ConnectionConfigurationInvalidException;
import org.eclipse.ditto.connectivity.model.ConnectionId;
import org.eclipse.ditto.connectivity.model.ConnectionType;
import org.eclipse.ditto.connectivity.model.ConnectivityModelFactory;
import org.eclipse.ditto.connectivity.model.ConnectivityStatus;
import org.eclipse.ditto.connectivity.model.Source;
import org.eclipse.ditto.connectivity.model.Topic;
import org.eclipse.ditto.connectivity.service.config.ConnectivityConfig;
import org.eclipse.ditto.connectivity.service.messaging.TestConstants;
import org.eclipse.ditto.connectivity.service.messaging.kafka.KafkaValidator;
import org.eclipse.ditto.internal.utils.config.DefaultScopedConfig;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorSystem;
import akka.testkit.javadsl.TestKit;

/**
 * Unit test for {@link HttpPushValidator}.
 */
public final class HttpPushValidatorTest {

    private static final ConnectionId CONNECTION_ID = TestConstants.createRandomConnectionId();
    private static Map<String, String> defaultSpecificConfig = new HashMap<>();
    private static ActorSystem actorSystem;
    private static ConnectivityConfig connectivityConfig;

    private HttpPushValidator underTest;

    @BeforeClass
    public static void initTestFixture() {
        defaultSpecificConfig = new HashMap<>();
        defaultSpecificConfig.put("parallelism", "1");
        actorSystem = ActorSystem.create("AkkaTestSystem", TestConstants.CONFIG);
        connectivityConfig = TestConstants.CONNECTIVITY_CONFIG;
    }

    @AfterClass
    public static void tearDown() {
        if (actorSystem != null) {
            TestKit.shutdownActorSystem(actorSystem, scala.concurrent.duration.Duration.apply(5, TimeUnit.SECONDS),
                    false);
        }
    }

    @Before
    public void setUp() {
        underTest = HttpPushValidator.newInstance();
    }

    @Test
    public void testImmutability() {
        assertInstancesOf(KafkaValidator.class, areImmutable());
    }

    @Test
    public void testSourcesAreInvalid() {
        final Source source = ConnectivityModelFactory.newSource(AUTHORIZATION_CONTEXT, "any");

        assertThatExceptionOfType(ConnectionConfigurationInvalidException.class)
                .isThrownBy(() -> underTest.validateSource(source, DittoHeaders.empty(), () -> ""));
    }

    @Test
    public void testValidTargetAddress() {
        final DittoHeaders emptyDittoHeaders = DittoHeaders.empty();
        underTest.validate(getConnectionWithTarget("POST:events"), emptyDittoHeaders, actorSystem, connectivityConfig);
        underTest.validate(getConnectionWithTarget("PUT:ditto/{{thing:id}}"), emptyDittoHeaders, actorSystem,
                connectivityConfig);
        underTest.validate(getConnectionWithTarget("PUT:ditto/{{entity:id}}"), emptyDittoHeaders, actorSystem,
                connectivityConfig);
        underTest.validate(getConnectionWithTarget("PATCH:/{{thing:namespace}}/{{thing:name}}"), emptyDittoHeaders,
                actorSystem, connectivityConfig);
        underTest.validate(getConnectionWithTarget("PATCH:/{{thing:namespace}}/{{thing:name}}/{{ feature:id }}"),
                emptyDittoHeaders,
                actorSystem, connectivityConfig);
        underTest.validate(getConnectionWithTarget("PUT:events#{{topic:full}}"), emptyDittoHeaders, actorSystem,
                connectivityConfig);
        underTest.validate(getConnectionWithTarget("POST:ditto?{{header:x}}"), emptyDittoHeaders, actorSystem,
                connectivityConfig);
        underTest.validate(getConnectionWithTarget("POST:"), emptyDittoHeaders, actorSystem, connectivityConfig);
        underTest.validate(getConnectionWithTarget("GET:foo"), emptyDittoHeaders, actorSystem, connectivityConfig);
    }

    @Test
    public void testInvalidTargetAddress() {
        verifyConnectionConfigurationInvalidExceptionIsThrown(getConnectionWithTarget(""));
        verifyConnectionConfigurationInvalidExceptionIsThrown(getConnectionWithTarget("events"));
        verifyConnectionConfigurationInvalidExceptionIsThrown(getConnectionWithTarget("DELETE:/bar"));
    }

    private static Connection getConnectionWithTarget(final String target) {
        return getConnectionWithHostAndTarget("8.8.4.4", target);
    }

    private static Connection getConnectionWithHostAndTarget(final String host, final String target) {
        return ConnectivityModelFactory.newConnectionBuilder(CONNECTION_ID, ConnectionType.HTTP_PUSH,
                        ConnectivityStatus.OPEN, "http://" + host + ":80")
                .targets(singletonList(ConnectivityModelFactory.newTargetBuilder()
                        .address(target)
                        .authorizationContext(AUTHORIZATION_CONTEXT)
                        .qos(1)
                        .topics(Topic.LIVE_EVENTS)
                        .build()))
                .specificConfig(defaultSpecificConfig)
                .build();
    }

    private void verifyConnectionConfigurationInvalidExceptionIsThrown(final Connection connection) {
        assertThatExceptionOfType(ConnectionConfigurationInvalidException.class)
                .isThrownBy(
                        () -> underTest.validate(connection, DittoHeaders.empty(), actorSystem, connectivityConfig));
    }

}

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
package org.eclipse.ditto.connectivity.service.messaging.monitoring.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import org.eclipse.ditto.connectivity.model.ConnectionId;
import org.eclipse.ditto.connectivity.model.ConnectionType;
import org.eclipse.ditto.connectivity.model.MetricDirection;
import org.eclipse.ditto.connectivity.model.MetricType;
import org.eclipse.ditto.connectivity.service.config.ConnectionThrottlingConfig;
import org.eclipse.ditto.connectivity.service.config.ConnectivityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry to keep track and update existing {@code MetricsAlerts}.
 */
final class MetricAlertRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricAlertRegistry.class);

    /**
     * Defines which measurement window is used to detect throttling i.e. what is the maximum allowed messages per
     * interval. The throttling limits from ConnectivityConfig must be adjusted to the resolution of this window
     * (see {@link #calculateThrottlingLimitFromConfig}).
     */
    private static final MeasurementWindow THROTTLING_DETECTION_WINDOW =
            MeasurementWindow.ONE_MINUTE_WITH_TEN_SECONDS_RESOLUTION;

    /**
     * An alert can be registered for a combination of MetricType and MetricDirection e.g. CONSUMED + INBOUND. These
     * alerts will be instantiated using the registered Creator and passed to created SlidingWindowCounters.
     */
    private static final Map<MetricsAlert.Key, AlertsCreator> alertDefinitions = Map.of(
            MetricsAlert.Key.CONSUMED_INBOUND,
            (connectionId, connectionType, address, config) -> {
                final ConnectivityCounterRegistry.MapKey
                        target = new ConnectivityCounterRegistry.MapKey(connectionId, MetricType.THROTTLED,
                        MetricDirection.INBOUND, address);

                return new ThrottledMetricsAlert(THROTTLING_DETECTION_WINDOW,
                        calculateThrottlingLimitFromConfig(connectionType, config),
                        () -> ConnectivityCounterRegistry.lookup(target));
            }
    );

    private static final ConcurrentMap<ConnectivityCounterRegistry.MapKey, MetricsAlert> alerts = new ConcurrentHashMap<>();

    void updateAlert(final MetricsAlert.Key key,
            final ConnectionId connectionId, final ConnectionType connectionType, final String address,
            final ConnectivityConfig connectivityConfig) {
        Optional.of(key)
                .map(alertDefinitions::get)
                .ifPresent(creator -> {
                    final ConnectivityCounterRegistry.MapKey mapKey =
                            new ConnectivityCounterRegistry.MapKey(connectionId, key.getMetricType(),
                                    key.getMetricDirection(), address);
                    final MetricsAlert metricsAlert = creator.create(connectionId, connectionType, address,
                            connectivityConfig);
                    LOGGER.debug("Updating {} alert for connection {} to {}.", MetricsAlert.Key.CONSUMED_INBOUND,
                            connectionId, metricsAlert);
                    alerts.replace(mapKey, metricsAlert);
                });
    }

    @Nullable
    MetricsAlert getAlert(final MetricDirection metricDirection, final MetricType metricType,
            final ConnectionId connectionId, final ConnectionType connectionType, final String address,
            final ConnectivityConfig connectivityConfig) {
        return MetricsAlert.Key.from(metricDirection, metricType)
                .map(alertDefinitions::get)
                .map(creator -> {
                    final ConnectivityCounterRegistry.MapKey mapKey =
                            new ConnectivityCounterRegistry.MapKey(connectionId, metricType,
                                    metricDirection, address);
                    return alerts.computeIfAbsent(mapKey,
                            mk -> creator.create(connectionId, connectionType, address, connectivityConfig));
                })
                .orElse(null);
    }

    private static long calculateThrottlingLimitFromConfig(final ConnectionType connectionType,
            final ConnectivityConfig config) {
        switch (connectionType) {
            case AMQP_10:
                final ConnectionThrottlingConfig amqp10ThrottlingConfig =
                        config.getConnectionConfig().getAmqp10Config().getConsumerConfig().getThrottlingConfig();
                return perInterval(amqp10ThrottlingConfig, THROTTLING_DETECTION_WINDOW.getResolution());
            case KAFKA:
                final ConnectionThrottlingConfig kafkaThrottlingConfig =
                        config.getConnectionConfig().getKafkaConfig().getConsumerConfig().getThrottlingConfig();
                return perInterval(kafkaThrottlingConfig, THROTTLING_DETECTION_WINDOW.getResolution());
            case MQTT:
            case AMQP_091:
            case HTTP_PUSH:
            case MQTT_5:
            default:
                // effectively no limit
                return Integer.MAX_VALUE;
        }
    }

    private static long perInterval(final ConnectionThrottlingConfig throttlingConfig, final Duration resolution) {
        final double tolerance = throttlingConfig.getThrottlingDetectionTolerance();
        final Duration interval = throttlingConfig.getInterval();
        // calculate factor to adjust the limit to the given resolution
        final double factor = (double) resolution.toMillis() / interval.toMillis();
        final int limit = throttlingConfig.getLimit();
        final double limitAdjustedToResolution = limit * factor;
        // apply the configured tolerance to the resulting limit
        return (long) (limitAdjustedToResolution * (1 - tolerance));
    }

    /**
     * Creator interface for MetricsAlerts which are stored in the map of existing {@link #alertDefinitions}.
     */
    @FunctionalInterface
    interface AlertsCreator {

        /**
         * Create a new instantiation of a metrics alert.
         *
         * @param connectionId the connection id
         * @param connectionType the connection type
         * @param address the address
         * @param connectivityConfig the connectivity config
         * @return the new metrics alert
         */
        MetricsAlert create(final ConnectionId connectionId, final ConnectionType connectionType, final String address,
                final ConnectivityConfig connectivityConfig);
    }
}

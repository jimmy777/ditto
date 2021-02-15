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
package org.eclipse.ditto.services.policies.common.config;

import java.time.Duration;
import java.util.Objects;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.services.base.config.supervision.DefaultSupervisorConfig;
import org.eclipse.ditto.services.base.config.supervision.SupervisorConfig;
import org.eclipse.ditto.services.utils.config.ConfigWithFallback;
import org.eclipse.ditto.services.utils.config.ScopedConfig;
import org.eclipse.ditto.services.utils.persistence.mongo.config.ActivityCheckConfig;
import org.eclipse.ditto.services.utils.persistence.mongo.config.DefaultActivityCheckConfig;
import org.eclipse.ditto.services.utils.persistence.mongo.config.DefaultSnapshotConfig;
import org.eclipse.ditto.services.utils.persistence.mongo.config.SnapshotConfig;

import com.typesafe.config.Config;

/**
 * This class is the default implementation of the policy config.
 */
@Immutable
public final class DefaultPolicyConfig implements PolicyConfig {

    private static final String CONFIG_PATH = "policy";

    private final SupervisorConfig supervisorConfig;
    private final ActivityCheckConfig activityCheckConfig;
    private final SnapshotConfig snapshotConfig;
    private final Duration policySubjectExpiryGranularity;
    private final Duration policySubjectExpiryNotificationGranularity;
    private final String subjectIdResolver;

    private DefaultPolicyConfig(final ScopedConfig scopedConfig) {
        supervisorConfig = DefaultSupervisorConfig.of(scopedConfig);
        activityCheckConfig = DefaultActivityCheckConfig.of(scopedConfig);
        snapshotConfig = DefaultSnapshotConfig.of(scopedConfig);
        policySubjectExpiryGranularity =
                scopedConfig.getDuration(PolicyConfigValue.SUBJECT_EXPIRY_GRANULARITY.getConfigPath());
        policySubjectExpiryNotificationGranularity =
                scopedConfig.getDuration(PolicyConfigValue.SUBJECT_EXPIRY_NOTIFICATION_GRANULARITY.getConfigPath());
        subjectIdResolver = scopedConfig.getString(PolicyConfigValue.SUBJECT_ID_RESOLVER.getConfigPath());
    }

    /**
     * Returns an instance of the policy config based on the settings of the specified Config.
     *
     * @param config is supposed to provide the settings of the policy config at {@value #CONFIG_PATH}.
     * @return the instance.
     * @throws org.eclipse.ditto.services.utils.config.DittoConfigError if {@code config} is invalid.
     */
    public static DefaultPolicyConfig of(final Config config) {
        final ConfigWithFallback mappingScopedConfig =
                ConfigWithFallback.newInstance(config, CONFIG_PATH, PolicyConfigValue.values());
        return new DefaultPolicyConfig(mappingScopedConfig);
    }

    @Override
    public SupervisorConfig getSupervisorConfig() {
        return supervisorConfig;
    }

    @Override
    public ActivityCheckConfig getActivityCheckConfig() {
        return activityCheckConfig;
    }

    @Override
    public SnapshotConfig getSnapshotConfig() {
        return snapshotConfig;
    }

    @Override
    public Duration getSubjectExpiryGranularity() {
        return policySubjectExpiryGranularity;
    }

    @Override
    public Duration getSubjectExpiryNotificationGranularity() {
        return policySubjectExpiryNotificationGranularity;
    }

    @Override
    public String getSubjectIdResolver() {
        return subjectIdResolver;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DefaultPolicyConfig that = (DefaultPolicyConfig) o;
        return Objects.equals(supervisorConfig, that.supervisorConfig) &&
                Objects.equals(activityCheckConfig, that.activityCheckConfig) &&
                Objects.equals(snapshotConfig, that.snapshotConfig) &&
                Objects.equals(policySubjectExpiryGranularity, that.policySubjectExpiryGranularity) &&
                Objects.equals(policySubjectExpiryNotificationGranularity,
                        that.policySubjectExpiryNotificationGranularity) &&
                Objects.equals(subjectIdResolver, that.subjectIdResolver);
    }

    @Override
    public int hashCode() {
        return Objects.hash(supervisorConfig, activityCheckConfig, snapshotConfig, policySubjectExpiryGranularity,
                policySubjectExpiryNotificationGranularity, subjectIdResolver);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                " supervisorConfig=" + supervisorConfig +
                ", activityCheckConfig=" + activityCheckConfig +
                ", snapshotConfig=" + snapshotConfig +
                ", policySubjectExpiryGranularity=" + policySubjectExpiryGranularity +
                ", policySubjectExpiryNotificationGranularity=" + policySubjectExpiryNotificationGranularity +
                ", subjectIdResolver=" + subjectIdResolver +
                "]";
    }

}

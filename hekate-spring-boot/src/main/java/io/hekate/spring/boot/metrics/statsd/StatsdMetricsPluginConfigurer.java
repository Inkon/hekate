/*
 * Copyright 2017 The Hekate Project
 *
 * The Hekate Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.hekate.spring.boot.metrics.statsd;

import io.hekate.metrics.statsd.StatsdMetricsConfig;
import io.hekate.metrics.statsd.StatsdMetricsPlugin;
import io.hekate.spring.boot.ConditionalOnHekateEnabled;
import io.hekate.spring.boot.HekateConfigurer;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for {@link StatsdMetricsPlugin}.
 *
 * <p>
 * This auto-configuration is disabled by default and can be enabled by setting the {@code 'hekate.metrics.statsd.enable'} property to
 * {@code true} in the application's configuration.
 * </p>
 *
 * <p>
 * The following properties can be used to customize the auto-configured {@link StatsdMetricsPlugin} instance:
 * </p>
 * <ul>
 * <li>{@link StatsdMetricsConfig#setHost(String) 'hekate.metrics.statsd.host'}</li>
 * <li>{@link StatsdMetricsConfig#setPort(int) 'hekate.metrics.statsd.port'}</li>
 * <li>{@link StatsdMetricsConfig#setBatchSize(int) 'hekate.metrics.statsd.batch-size'}</li>
 * <li>{@link StatsdMetricsConfig#setMaxQueueSize(int) 'hekate.metrics.statsd.max-queue-size'}</li>
 * </ul>
 *
 * @see StatsdMetricsPlugin
 */
@Configuration
@ConditionalOnHekateEnabled
@AutoConfigureBefore(HekateConfigurer.class)
@ConditionalOnClass(StatsdMetricsPlugin.class)
@ConditionalOnProperty(value = "hekate.metrics.statsd.enable", havingValue = "true")
public class StatsdMetricsPluginConfigurer {
    /**
     * Conditionally constructs a new configuration for {@link StatsdMetricsPlugin} if application doesn't provide its own {@link Bean} of
     * {@link StatsdMetricsConfig} type.
     *
     * @return New configuration.
     */
    @Bean
    @ConditionalOnMissingBean(StatsdMetricsConfig.class)
    @ConfigurationProperties(prefix = "hekate.metrics.statsd")
    public StatsdMetricsConfig statsdMetricsConfig() {
        return new StatsdMetricsConfig();
    }

    /**
     * Constructs new {@link StatsdMetricsPlugin}.
     *
     * @param cfg Configuration (see {@link #statsdMetricsConfig()}).
     *
     * @return New plugin.
     */
    @Bean
    public StatsdMetricsPlugin statsdMetricsPlugin(StatsdMetricsConfig cfg) {
        return new StatsdMetricsPlugin(cfg);
    }
}
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

package io.hekate.metrics.local;

import io.hekate.metrics.Metric;

/**
 * Metric with incrementing/decrementing value.
 *
 * <p>
 * This interface represents a metric with the explicitly managed value. Counters can be registered within the {@link LocalMetricsService}
 * either {@link LocalMetricsServiceFactory#withMetric(MetricConfigBase)}  statically} or {@link
 * LocalMetricsService#register(CounterConfig) dynamically}. Once registered they can be accessed via {@link
 * LocalMetricsService#getCounter(String)}.
 * </p>
 *
 * <p>
 * All operations on counters are atomic and thread safe.
 * </p>
 *
 * <p>
 * Counters can be configured to be automatically reset to 0 every time when {@link LocalMetricsService} performs recalculation of metric
 * values (which happens every {@link LocalMetricsServiceFactory#getRefreshInterval()}). This behavior is controlled by the {@link
 * CounterConfig#setAutoReset(boolean)} flag.
 * </p>
 *
 * <p>
 * For more details about metric types and their usage please see the documentation of {@link LocalMetricsService} interface.
 * </p>
 *
 * @see LocalMetricsService
 * @see CounterConfig
 */
public interface CounterMetric extends Metric {
    /**
     * Increments this counter by 1.
     */
    void increment();

    /**
     * Decrements this counter by 1.
     */
    void decrement();

    /**
     * Adds the specified value to this counter.
     *
     * @param value Value to add.
     */
    void add(long value);

    /**
     * Subtracts the specified value from this counter.
     *
     * @param value Value to subtract.
     */
    void subtract(long value);
}
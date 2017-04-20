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

package io.hekate.messaging.internal;

import io.hekate.metrics.local.CounterConfig;
import io.hekate.metrics.local.CounterMetric;
import io.hekate.metrics.local.LocalMetricsService;

class MetricsCallback {
    private final CounterMetric pending;

    private final CounterMetric asyncQueue;

    private final CounterMetric retry;

    public MetricsCallback(String name, LocalMetricsService metrics) {
        pending = metrics.register(new CounterConfig(name + ".msg.pending"));
        asyncQueue = metrics.register(new CounterConfig(name + ".msg.work.queue"));
        retry = metrics.register(new CounterConfig(name + ".msg.retry").withAutoReset(true));
    }

    public void onPendingRequestsRemoved(int i) {
        pending.subtract(i);
    }

    public void onPendingRequestAdded() {
        pending.increment();
    }

    public void onAsyncEnqueue() {
        asyncQueue.increment();
    }

    public void onAsyncDequeue() {
        asyncQueue.decrement();
    }

    public void onRetry() {
        retry.increment();
    }
}

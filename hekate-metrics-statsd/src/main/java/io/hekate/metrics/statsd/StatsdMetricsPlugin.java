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

package io.hekate.metrics.statsd;

import io.hekate.cluster.ClusterNode;
import io.hekate.core.Hekate;
import io.hekate.core.HekateBootstrap;
import io.hekate.core.HekateException;
import io.hekate.core.plugin.Plugin;
import io.hekate.metrics.Metric;
import io.hekate.metrics.MetricFilter;
import io.hekate.metrics.MetricsService;
import io.hekate.metrics.MetricsServiceFactory;
import io.hekate.util.format.ToString;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * <span class="startHere">&laquo; start here</span>StatsD metrics publisher plugin.
 *
 * <h2>Overview</h2>
 * <p>
 * This plugin provides support for publishing metrics from {@link MetricsService} to
 * <a href="https://github.com/etsy/statsd" target="_blank">StatsD</a>.
 * </p>
 *
 * <p>
 * Metrics are asynchronously published once per {@link MetricsServiceFactory#setRefreshInterval(long)} interval. On each tick the snapshot
 * of current metrics is placed into a bounded queue of {@link StatsdMetricsConfig#setMaxQueueSize(int)} size. If queue is full (i.e.
 * publishing is slow due to some networking issues) then new metrics are dropped until there is more space in the queue.
 * </p>
 *
 * <p>
 * All metrics are published via UDP protocol. Each UDP packet can contain no more than {@link StatsdMetricsConfig#setBatchSize(int)}
 * metrics. If the size of a metrics snapshot is greater than this value then snapshot will be split into multiple UPD packets.
 * </p>
 *
 * <h2>Module dependency</h2>
 * <p>
 * StatsD support is provided by the 'hekate-metrics-statsd' module and can be imported into the project dependency management
 * system as in the example below:
 * </p>
 * <div class="tabs">
 * <ul>
 * <li><a href="#maven">Maven</a></li>
 * <li><a href="#gradle">Gradle</a></li>
 * <li><a href="#ivy">Ivy</a></li>
 * </ul>
 * <div id="maven">
 * <pre>{@code
 * <dependency>
 *   <groupId>io.hekate</groupId>
 *   <artifactId>hekate-metrics-statsd</artifactId>
 *   <version>REPLACE_VERSION</version>
 * </dependency>
 * }</pre>
 * </div>
 * <div id="gradle">
 * <pre>{@code
 * compile group: 'io.hekate', name: 'hekate-metrics-statsd', version: 'REPLACE_VERSION'
 * }</pre>
 * </div>
 * <div id="ivy">
 * <pre>{@code
 * <dependency org="io.hekate" name="hekate-metrics-statsd" rev="REPLACE_VERSION"/>
 * }</pre>
 * </div>
 * </div>
 *
 * <h2>Configuration</h2>
 * <p>
 * Configuration options of this plugin are represented by the {@link StatsdMetricsConfig} class. Please see the documentation of its
 * properties for more details.
 * </p>
 *
 * <h2>Registering plugin</h2>
 * <p>
 * This plugin can be registered via {@link HekateBootstrap#setPlugins(List)} method as in the example below:
 * </p>
 *
 * <p>
 * 1) Prepare plugin configuration with StatsD connectivity options.
 * ${source: StatsdMetricsPluginJavadocTest.java#configure}
 * </p>
 *
 * <p>
 * 2) Register plugin and start new node.
 * ${source: StatsdMetricsPluginJavadocTest.java#boot}
 * </p>
 *
 * <h2>Metrics names and tags</h2>
 * <p>
 * Metric {@link Metric#getName() names} are escaped so that all characters that are not letters, digits or dots (.) are replaced with
 * underscores (_).
 * </p>
 *
 * <h2>Metrics filtering</h2>
 * <p>
 * It is possible to filter out metrics that should not be published to StatsD by {@link StatsdMetricsConfig#setFilter(MetricFilter)
 * registering} an instance of {@link MetricFilter} interface. Only those metrics that do match the specified filter will be published to
 * StatsD.
 * </p>
 *
 * @see StatsdMetricsConfig
 * @see HekateBootstrap#setPlugins(List)
 */
public class StatsdMetricsPlugin implements Plugin {
    private final StatsdMetricsPublisher publisher;

    /**
     * Constructs new instance.
     *
     * @param cfg Configuration.
     */
    public StatsdMetricsPlugin(StatsdMetricsConfig cfg) {
        publisher = new StatsdMetricsPublisher(cfg);
    }

    @Override
    public void install(HekateBootstrap boot) {
        boot.withService(MetricsServiceFactory.class, metrics ->
            metrics.withListener(event ->
                publisher.publish(event.allMetrics().values())
            )
        );
    }

    @Override
    public void start(Hekate hekate) throws HekateException {
        ClusterNode node = hekate.getNode();

        InetSocketAddress netAddress = node.getNetAddress();

        try {
            publisher.start(netAddress.getAddress().getHostAddress(), netAddress.getPort());
        } catch (UnknownHostException e) {
            throw new HekateException("Failed to start StatsD metrics publisher.", e);
        }
    }

    @Override
    public void stop() throws HekateException {
        publisher.stop();
    }

    @Override
    public String toString() {
        return ToString.format(this);
    }
}

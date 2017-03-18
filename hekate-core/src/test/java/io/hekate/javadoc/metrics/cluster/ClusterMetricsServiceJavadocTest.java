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

package io.hekate.javadoc.metrics.cluster;

import io.hekate.HekateInstanceTestBase;
import io.hekate.cluster.ClusterService;
import io.hekate.core.Hekate;
import io.hekate.core.HekateBootstrap;
import io.hekate.metrics.cluster.ClusterMetricsService;
import io.hekate.metrics.cluster.ClusterMetricsServiceFactory;
import org.junit.Test;

public class ClusterMetricsServiceJavadocTest extends HekateInstanceTestBase {
    @Test
    public void exampleBootstrap() throws Exception {
        // Start:configure
        // Prepare service factory.
        ClusterMetricsServiceFactory factory = new ClusterMetricsServiceFactory()
            // Replicate metrics once per second.
            .withReplicationInterval(1000);

        // Start node.
        Hekate hekate = new HekateBootstrap()
            .withService(factory)
            .join();
        // End:configure

        // Start:access
        ClusterMetricsService metrics = hekate.get(ClusterMetricsService.class);
        // End:access

        // Start:usage
        // Get cluster service.
        ClusterService cluster = hekate.get(ClusterService.class);

        // Iterate over all remote nodes in the cluster.
        cluster.forRemotes().forEach(node -> {
            // Use cluster metrics service to get metrics of each node.
            metrics.forNode(node).ifPresent(m ->
                System.out.println("Metric on node " + m.getNode() + " = " + m.get("example.metric"))
            );
        });
        // End:usage

        hekate.leave();
    }
}
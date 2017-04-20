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

package io.hekate.metrics.cluster.internal;

import io.hekate.HekateNodeContextTestBase;
import io.hekate.HekateTestContext;
import io.hekate.core.internal.HekateTestNode;
import io.hekate.metrics.cluster.ClusterMetricsService;
import io.hekate.metrics.cluster.ClusterMetricsServiceFactory;
import io.hekate.metrics.cluster.ClusterNodeMetrics;
import io.hekate.metrics.local.CounterConfig;
import io.hekate.metrics.local.CounterMetric;
import io.hekate.metrics.local.LocalMetricsService;
import io.hekate.metrics.local.LocalMetricsServiceFactory;
import io.hekate.metrics.local.ProbeConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClusterMetricsServiceTest extends HekateNodeContextTestBase {
    public interface ClusterMetricsConfigurer {
        void configure(ClusterMetricsServiceFactory factory);
    }

    public static final int TEST_METRICS_REFRESH_INTERVAL = 25;

    public ClusterMetricsServiceTest(HekateTestContext params) {
        super(params);
    }

    @Test
    public void testAddMetrics() throws Exception {
        HekateTestNode node1 = createNodeWithMetrics().join();
        HekateTestNode node2 = createNodeWithMetrics().join();
        HekateTestNode node3 = createNodeWithMetrics().join();

        awaitForTopology(node1, node2, node3);

        repeat(5, i -> {
            CounterMetric c1 = node1.localMetrics().register(new CounterConfig("c1_" + i));
            CounterMetric c2 = node2.localMetrics().register(new CounterConfig("c2_" + i));
            CounterMetric c3 = node3.localMetrics().register(new CounterConfig("c3_" + i));

            awaitForClusterMetric("c1_" + i, 0, node1, Arrays.asList(node1, node2, node3));
            awaitForClusterMetric("c2_" + i, 0, node2, Arrays.asList(node1, node2, node3));
            awaitForClusterMetric("c3_" + i, 0, node3, Arrays.asList(node1, node2, node3));

            c1.add(100);
            c2.add(200);
            c3.add(300);

            awaitForClusterMetric("c1_" + i, 100, node1, Arrays.asList(node1, node2, node3));
            awaitForClusterMetric("c2_" + i, 200, node2, Arrays.asList(node1, node2, node3));
            awaitForClusterMetric("c3_" + i, 300, node3, Arrays.asList(node1, node2, node3));

            c1.add(1000);
            c2.add(2000);
            c3.add(3000);

            awaitForClusterMetric("c1_" + i, 1100, node1, Arrays.asList(node1, node2, node3));
            awaitForClusterMetric("c2_" + i, 2200, node2, Arrays.asList(node1, node2, node3));
            awaitForClusterMetric("c3_" + i, 3300, node3, Arrays.asList(node1, node2, node3));
        });
    }

    @Test
    public void testMetricsWithNoMetricsNode() throws Exception {
        HekateTestNode node1 = createNodeWithMetrics().join();
        HekateTestNode node2 = createNodeWithMetrics().join();

        HekateTestNode noMetricsNode = createNode().join();

        awaitForTopology(node1, node2, noMetricsNode);

        repeat(5, i -> {
            CounterMetric c1 = node1.localMetrics().register(new CounterConfig("c1_" + i));
            CounterMetric c2 = node2.localMetrics().register(new CounterConfig("c2_" + i));

            awaitForClusterMetric("c1_" + i, 0, node1, Arrays.asList(node1, node2));
            awaitForClusterMetric("c2_" + i, 0, node2, Arrays.asList(node1, node2));

            c1.add(100);
            c2.add(200);

            awaitForClusterMetric("c1_" + i, 100, node1, Arrays.asList(node1, node2));
            awaitForClusterMetric("c2_" + i, 200, node2, Arrays.asList(node1, node2));

            c1.add(1000);
            c2.add(2000);

            awaitForClusterMetric("c1_" + i, 1100, node1, Arrays.asList(node1, node2));
            awaitForClusterMetric("c2_" + i, 2200, node2, Arrays.asList(node1, node2));
        });
    }

    @Test
    public void testMetricsFilter() throws Exception {
        HekateTestNode node1 = createNodeWithMetrics(f -> f.setReplicationFilter(m -> m.getName().startsWith("c"))).join();
        HekateTestNode node2 = createNodeWithMetrics(f -> f.setReplicationFilter(m -> m.getName().startsWith("c"))).join();

        awaitForTopology(node1, node2);

        repeat(5, i -> {
            CounterMetric no1 = node1.localMetrics().register(new CounterConfig("no1_" + i));
            CounterMetric no2 = node2.localMetrics().register(new CounterConfig("no2_" + i));

            CounterMetric c1 = node1.localMetrics().register(new CounterConfig("c1_" + i));
            CounterMetric c2 = node2.localMetrics().register(new CounterConfig("c2_" + i));

            awaitForClusterMetric("c1_" + i, 0, node1, Arrays.asList(node1, node2));
            awaitForClusterMetric("c2_" + i, 0, node2, Arrays.asList(node1, node2));

            assertNull(node1.clusterMetrics().forNode(node2.getLocalNode()).get().metric("no_2" + i));
            assertNull(node2.clusterMetrics().forNode(node1.getLocalNode()).get().metric("no_1" + i));

            c1.add(100);
            c2.add(200);

            no1.add(1000);
            no2.add(1000);

            awaitForClusterMetric("c1_" + i, 100, node1, Arrays.asList(node1, node2));
            awaitForClusterMetric("c2_" + i, 200, node2, Arrays.asList(node1, node2));

            assertNull(node1.clusterMetrics().forNode(node2.getLocalNode()).get().metric("no_2" + i));
            assertNull(node2.clusterMetrics().forNode(node1.getLocalNode()).get().metric("no_1" + i));

            c1.add(1000);
            c2.add(2000);

            awaitForClusterMetric("c1_" + i, 1100, node1, Arrays.asList(node1, node2));
            awaitForClusterMetric("c2_" + i, 2200, node2, Arrays.asList(node1, node2));

            assertNull(node1.clusterMetrics().forNode(node2.getLocalNode()).get().metric("no_2" + i));
            assertNull(node2.clusterMetrics().forNode(node1.getLocalNode()).get().metric("no_1" + i));
        });
    }

    @Test
    public void testGetMetricsWithFilter() throws Exception {
        HekateTestNode node1 = createNodeWithMetrics().join();
        HekateTestNode node2 = createNodeWithMetrics().join();

        ClusterMetricsService cs1 = node1.clusterMetrics();
        ClusterMetricsService cs2 = node2.clusterMetrics();

        awaitForTopology(node1, node2);

        repeat(5, i -> {
            node1.localMetrics().register(new CounterConfig("no1_" + i));
            node2.localMetrics().register(new CounterConfig("no2_" + i));

            node1.localMetrics().register(new CounterConfig("c1_" + i));
            node2.localMetrics().register(new CounterConfig("c2_" + i));

            awaitForClusterMetric("c1_" + i, 0, node1, Arrays.asList(node1, node2));
            awaitForClusterMetric("c2_" + i, 0, node2, Arrays.asList(node1, node2));

            assertTrue(cs1.forAll(m -> m.getName().startsWith("c")).stream().anyMatch(n -> n.getNode().equals(node1.getLocalNode())));
            assertTrue(cs2.forAll(m -> m.getName().startsWith("c")).stream().anyMatch(n -> n.getNode().equals(node1.getLocalNode())));

            assertTrue(cs1.forAll(m -> m.getName().startsWith("no1")).stream().noneMatch(n -> n.getNode().equals(node2.getLocalNode())));
            assertTrue(cs2.forAll(m -> m.getName().startsWith("no2")).stream().noneMatch(n -> n.getNode().equals(node1.getLocalNode())));

            assertTrue(cs1.forAll(m -> false).isEmpty());
            assertTrue(cs2.forAll(m -> false).isEmpty());
        });
    }

    @Test
    public void testAddRemoveNodes() throws Exception {
        List<HekateTestNode> nodes = new LinkedList<>();
        List<LocalMetricsService> services = new LinkedList<>();
        List<ClusterMetricsService> clusterServices = new LinkedList<>();
        List<AtomicInteger> probes = new LinkedList<>();

        sayHeader("Start nodes.");

        repeat(5, i -> {
            for (LocalMetricsService metrics : services) {
                metrics.getCounter("c").add(1);
            }

            for (AtomicInteger probe : probes) {
                probe.set(i);
            }

            HekateTestNode node = createNodeWithMetrics().join();

            nodes.add(node);

            services.add(node.localMetrics());
            clusterServices.add(node.clusterMetrics());

            AtomicInteger probe = new AtomicInteger(i);

            probes.add(probe);

            node.localMetrics().register(new CounterConfig("c"));
            node.localMetrics().register(new ProbeConfig("p").withProbe(probe::get).withInitValue(i));

            node.localMetrics().getCounter("c").add(i);

            awaitForClusterMetric("c", i, nodes);
            awaitForClusterMetric("p", i, nodes);

            for (ClusterMetricsService service : clusterServices) {
                assertEquals(nodes.size(), service.forAll().size());

                for (HekateTestNode n : nodes) {
                    ClusterNodeMetrics nodeMetrics = service.forNode(n.getLocalNode()).get();

                    assertNotNull(nodeMetrics.metric("c"));
                    assertTrue(nodeMetrics.allMetrics().containsKey("c"));
                    assertEquals(n.getLocalNode(), nodeMetrics.getNode());
                    assertEquals(i, nodeMetrics.metric("c").getValue());

                    assertNotNull(nodeMetrics.metric("p"));
                    assertTrue(nodeMetrics.allMetrics().containsKey("p"));
                    assertEquals(n.getLocalNode(), nodeMetrics.getNode());
                    assertEquals(i, nodeMetrics.metric("p").getValue());
                }
            }
        });

        sayHeader("Update metrics.");

        repeat(5, i -> {
            long oldVal = 4;

            for (LocalMetricsService metrics : services) {
                metrics.getCounter("c").add(100000);
            }

            for (AtomicInteger probe : probes) {
                probe.set(100000 + i);
            }

            awaitForClusterMetric("c", 100000 + oldVal, nodes);
            awaitForClusterMetric("p", 100000 + i, nodes);

            for (LocalMetricsService metrics : services) {
                metrics.getCounter("c").subtract(100000);
            }

            for (AtomicInteger probe : probes) {
                probe.set(i);
            }

            awaitForClusterMetric("c", oldVal, nodes);
            awaitForClusterMetric("p", i, nodes);
        });

        sayHeader("Stop nodes.");

        repeat(5, i -> {
            nodes.remove(0).leave();
            services.remove(0);
            clusterServices.remove(0);

            awaitForTopology(nodes);

            long oldVal = 4;

            for (LocalMetricsService metrics : services) {
                metrics.getCounter("c").add(1000);
            }

            for (AtomicInteger probe : probes) {
                probe.set(1000 + i);
            }

            awaitForClusterMetric("c", 1000 + oldVal, nodes);
            awaitForClusterMetric("p", 1000 + i, nodes);

            for (ClusterMetricsService service : clusterServices) {
                assertEquals(nodes.size(), service.forAll().size());

                for (HekateTestNode node : nodes) {
                    ClusterNodeMetrics nodeMetrics = service.forNode(node.getLocalNode()).get();

                    assertNotNull(nodeMetrics.metric("c"));
                    assertTrue(nodeMetrics.allMetrics().containsKey("c"));
                    assertEquals(node.getLocalNode(), nodeMetrics.getNode());
                    assertEquals(1000 + oldVal, nodeMetrics.metric("c").getValue());

                    assertNotNull(nodeMetrics.metric("p"));
                    assertTrue(nodeMetrics.allMetrics().containsKey("p"));
                    assertEquals(node.getLocalNode(), nodeMetrics.getNode());
                    assertEquals(1000 + i, nodeMetrics.metric("p").getValue());
                }
            }

            for (LocalMetricsService metrics : services) {
                metrics.getCounter("c").subtract(1000);
            }

            for (AtomicInteger probe : probes) {
                probe.set(i);
            }

            awaitForClusterMetric("c", oldVal, nodes);
            awaitForClusterMetric("p", i, nodes);
        });
    }

    @Test
    public void testTerminateNode() throws Exception {
        disableNodeFailurePostCheck();

        HekateTestNode node1 = createNodeWithMetrics().join();
        HekateTestNode node2 = createNodeWithMetrics().join();
        HekateTestNode node3 = createNodeWithMetrics().join();

        awaitForTopology(node1, node2, node3);

        CounterMetric c1 = node1.localMetrics().register(new CounterConfig("c1"));
        CounterMetric c2 = node2.localMetrics().register(new CounterConfig("c2"));

        c1.add(1);
        c2.add(2);

        awaitForClusterMetric("c1", 1, node1, Arrays.asList(node1, node2, node3));
        awaitForClusterMetric("c2", 2, node2, Arrays.asList(node1, node2, node3));

        say("Terminating node...");

        node3.terminate();

        awaitForTopology(node1, node2);

        c1.add(100);
        c2.add(200);

        awaitForClusterMetric("c1", 101, node1, Arrays.asList(node1, node2));
        awaitForClusterMetric("c2", 202, node2, Arrays.asList(node1, node2));
    }

    protected HekateTestNode createNodeWithMetrics() throws Exception {
        return createNodeWithMetrics(null);
    }

    protected HekateTestNode createNodeWithMetrics(ClusterMetricsConfigurer configurer) throws Exception {
        return createNode(boot -> {
            boot.withService(LocalMetricsServiceFactory.class, metrics ->
                metrics.setRefreshInterval(TEST_METRICS_REFRESH_INTERVAL)
            );

            boot.withService(ClusterMetricsServiceFactory.class, metrics -> {
                metrics.setReplicationInterval(TEST_METRICS_REFRESH_INTERVAL);

                if (configurer != null) {
                    configurer.configure(metrics);
                }
            });
        });
    }

    private void awaitForClusterMetric(String metric, long value, HekateTestNode fromNode, List<HekateTestNode> nodes)
        throws Exception {
        awaitForClusterMetric(metric, value, Collections.singletonList(fromNode), nodes);
    }

    private void awaitForClusterMetric(String metric, long value, List<HekateTestNode> nodes) throws Exception {
        awaitForClusterMetric(metric, value, nodes, nodes);
    }

    private void awaitForClusterMetric(String metric, long value, List<HekateTestNode> checkNodes, List<HekateTestNode> nodes)
        throws Exception {
        busyWait("metric value [name=" + metric + ", value=" + value + ", nodes=" + nodes + ']', () -> {
            for (HekateTestNode node : nodes) {
                for (HekateTestNode checkNode : checkNodes) {
                    ClusterNodeMetrics metrics = node.clusterMetrics().forNode(checkNode.getLocalNode()).orElse(null);

                    if (metrics == null || metrics.metric(metric) == null || metrics.metric(metric).getValue() != value) {
                        return false;
                    }
                }
            }

            return true;
        });
    }
}

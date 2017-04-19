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

package io.hekate.cluster.internal;

import io.hekate.HekateTestBase;
import io.hekate.HekateTestContext;
import io.hekate.cluster.ClusterJoinRejectedException;
import io.hekate.cluster.ClusterNode;
import io.hekate.cluster.ClusterServiceFactory;
import io.hekate.cluster.ClusterTopology;
import io.hekate.cluster.ClusterView;
import io.hekate.cluster.event.ClusterChangeEvent;
import io.hekate.cluster.event.ClusterEvent;
import io.hekate.cluster.event.ClusterEventType;
import io.hekate.cluster.event.ClusterJoinEvent;
import io.hekate.cluster.event.ClusterLeaveEvent;
import io.hekate.cluster.internal.gossip.GossipNodeStatus;
import io.hekate.cluster.internal.gossip.GossipSpyAdaptor;
import io.hekate.core.Hekate;
import io.hekate.core.HekateFutureException;
import io.hekate.core.JoinFuture;
import io.hekate.core.LeaveFuture;
import io.hekate.core.TerminateFuture;
import io.hekate.core.internal.HekateTestNode;
import io.hekate.core.service.Service;
import io.hekate.network.NetworkServiceFactory;
import io.hekate.network.address.DefaultAddressSelector;
import io.hekate.network.address.DefaultAddressSelectorConfig;
import io.hekate.network.address.IpVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.Test;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ClusterServiceMultipleNodesTest extends ClusterServiceMultipleNodesTestBase {
    private interface DummyService extends Service {
        // No-op.
    }

    public ClusterServiceMultipleNodesTest(HekateTestContext params) {
        super(params);
    }

    @Test
    public void testJoinLeave() throws Exception {
        repeat(10, i -> {
            List<HekateTestNode> nodes = new ArrayList<>();

            AtomicInteger failuresCount = new AtomicInteger();

            for (int j = 0; j < i + 1; j++) {
                HekateTestNode node = createNode();

                node.setGossipSpy(new GossipSpyAdaptor() {
                    @Override
                    public void onNodeFailure(ClusterNode failed, GossipNodeStatus status) {
                        failuresCount.incrementAndGet();
                    }
                });

                node.startRecording();

                nodes.add(node);

                sayTime("Node " + j + " join", node::join);
            }

            nodes.forEach(n -> n.awaitForTopology(nodes));

            verifyJoinOrder(nodes);

            int j = 0;

            for (HekateTestNode node : nodes) {
                sayTime("Node " + j++ + " leave", node::leave);
            }

            assertEquals(0, failuresCount.get());
        });
    }

    @Test
    public void testJoinLeaveNoWait() throws Exception {
        repeat(10, i -> {
            List<HekateTestNode> nodes = new ArrayList<>();
            List<JoinFuture> joins = new ArrayList<>();

            AtomicInteger failuresCount = new AtomicInteger();

            sayTime("Join", () -> {
                for (int j = 0; j < i + 1; j++) {
                    HekateTestNode node = createNode();

                    node.setGossipSpy(new GossipSpyAdaptor() {
                        @Override
                        public void onNodeFailure(ClusterNode failed, GossipNodeStatus status) {
                            failuresCount.incrementAndGet();
                        }
                    });

                    nodes.add(node);

                    joins.add(node.joinAsync());
                }

                for (JoinFuture future : joins) {
                    future.get();
                }
            });

            sayTime("Wait for topology", () -> nodes.forEach(n -> n.awaitForTopology(nodes)));

            verifyJoinOrder(nodes);

            sayTime("Leave", () -> {
                List<LeaveFuture> leaves = nodes.stream().map(HekateTestNode::leaveAsync).collect(toList());

                for (LeaveFuture future : leaves) {
                    get(future);
                }
            });

            assertEquals(0, failuresCount.get());
        });
    }

    @Test
    public void testTopologyEventsWithTerminate() throws Exception {
        disableNodeFailurePostCheck();

        List<HekateTestNode> nodes = createAndJoinNodes(3);

        repeat(3, i -> {
            nodes.forEach(HekateTestNode::startRecording);

            HekateTestNode terminated = createNode();

            terminated.join();

            ClusterNode terminatedNode = terminated.getLocalNode();

            List<HekateTestNode> nodesWithTerminated = new ArrayList<>(nodes);

            nodesWithTerminated.add(terminated);

            awaitForTopology(nodesWithTerminated);

            terminated.terminate();

            awaitForTopology(nodes);

            nodes.forEach(n -> {
                List<ClusterEvent> events = n.getEvents();

                assertEquals(events.toString(), 3, events.size());

                ClusterEvent e1 = events.get(0);
                ClusterEvent e2 = events.get(1);
                ClusterEvent e3 = events.get(2);

                assertSame(ClusterEventType.JOIN, e1.getType());
                assertEquals(nodes.size(), e1.getTopology().size());

                assertSame(ClusterEventType.CHANGE, e2.getType());
                assertEquals(nodes.size() + 1, e2.getTopology().size());
                assertTrue(e2.getTopology().contains(terminatedNode));

                assertSame(ClusterEventType.CHANGE, e3.getType());
                assertEquals(nodes.size(), e3.getTopology().size());
                assertFalse(e3.getTopology().contains(terminatedNode));
            });

            nodes.forEach(HekateTestNode::clearEvents);
            nodes.forEach(HekateTestNode::stopRecording);
        });
    }

    @Test
    public void testTopologyEvents() throws Exception {
        List<HekateTestNode> nodes = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            HekateTestNode node = createNode();

            node.startRecording();

            nodes.add(node);

            node.join();

            nodes.forEach(n -> n.awaitForTopology(nodes));

            for (HekateTestNode oldNode : nodes) {
                int ver = 0;

                for (ClusterEvent event : oldNode.getEvents()) {
                    assertTrue(ver < event.getTopology().getVersion());

                    ver++;
                }

                assertEquals(oldNode.getTopology().getNodes(), nodes.stream().map(Hekate::getLocalNode).collect(Collectors.toSet()));
            }
        }

        List<HekateTestNode> stopped = new ArrayList<>(nodes);

        for (Iterator<HekateTestNode> it = nodes.iterator(); it.hasNext(); ) {
            HekateTestNode node = it.next();

            it.remove();

            node.leave();

            nodes.forEach(n -> n.awaitForTopology(nodes));

            for (HekateTestNode oldNode : nodes) {
                int ver = 0;

                for (ClusterEvent event : oldNode.getEvents()) {
                    assertTrue(ver < event.getTopology().getVersion());

                    ver++;
                }

                assertEquals(oldNode.getTopology().getNodes(), nodes.stream().map(Hekate::getLocalNode).collect(Collectors.toSet()));
            }
        }

        for (HekateTestNode node : stopped) {
            assertSame(ClusterEventType.JOIN, node.getEvents().get(0).getType());
            assertSame(ClusterEventType.LEAVE, node.getLastEvent().getType());

            assertEquals(1, node.getEvents().stream().filter(e -> e.getType() == ClusterEventType.JOIN).count());
            assertEquals(1, node.getEvents().stream().filter(e -> e.getType() == ClusterEventType.LEAVE).count());
        }
    }

    @Test
    public void testTopologyFuture() throws Exception {
        HekateTestNode node = createNode();

        CompletableFuture<ClusterTopology> beforeJoin = node.cluster().futureOf(topology -> !topology.isEmpty());

        assertFalse(beforeJoin.isDone());

        node.join();

        get(beforeJoin);

        CompletableFuture<ClusterTopology> afterJoin = node.cluster().futureOf(topology -> topology.size() == 1);

        get(afterJoin);

        CompletableFuture<ClusterTopology> afterJoinOther = node.cluster().futureOf(topology -> topology.size() == 2);

        assertFalse(afterJoinOther.isDone());

        createNode().join().leave();

        get(afterJoinOther);

        CompletableFuture<ClusterTopology> afterLeave = node.cluster().futureOf(topology -> false);

        node.leave();

        assertTrue(afterLeave.isCancelled());

        CompletableFuture<ClusterTopology> afterRejoin = node.cluster().futureOf(topology -> topology.size() == 1);

        assertFalse(afterRejoin.isDone());

        node.join();

        get(afterRejoin);

        assertTrue(afterRejoin.isDone());
        assertFalse(afterRejoin.isCancelled());
    }

    @Test
    public void testTopologyChangeEvents() throws Exception {
        List<HekateTestNode> nodes = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            HekateTestNode added = createNode();

            added.startRecording();

            nodes.add(added);

            added.join();

            nodes.forEach(n -> n.awaitForTopology(nodes));

            nodes.stream().filter(n -> !n.getLocalNode().equals(added.getLocalNode())).forEach(n -> {
                List<ClusterEvent> events = n.getEvents(ClusterEventType.CHANGE);

                assertEquals(1, events.size());

                ClusterChangeEvent event = events.iterator().next().asChange();

                assertEquals(1, event.getAdded().size());
                assertEquals(0, event.getRemoved().size());

                assertEquals(added.getLocalNode(), event.getAdded().iterator().next());

                n.clearEvents();
            });
        }

        for (Iterator<HekateTestNode> it = nodes.iterator(); it.hasNext(); ) {
            HekateTestNode removed = it.next();

            ClusterNode removedNode = removed.getLocalNode();

            it.remove();

            removed.leave();

            List<ClusterEvent> leaveEvents = removed.getEvents(ClusterEventType.LEAVE);

            assertEquals(1, leaveEvents.size());
            assertEquals(0, leaveEvents.get(0).asLeave().getAdded().size());
            assertEquals(0, leaveEvents.get(0).asLeave().getRemoved().size());

            nodes.forEach(n -> n.awaitForTopology(nodes));

            nodes.forEach(n -> {
                List<ClusterEvent> events = n.getEvents(ClusterEventType.CHANGE);

                assertEquals(1, events.size());

                ClusterChangeEvent event = events.iterator().next().asChange();

                assertEquals(0, event.getAdded().size());
                assertEquals(1, event.getRemoved().size());

                assertEquals(removedNode, event.getRemoved().iterator().next());

                n.clearEvents();
            });
        }
    }

    @Test
    public void testJoinLeaveWithPermanentNodes() throws Exception {
        repeat(5, i -> {
            List<HekateTestNode> permanentNodes = createAndJoinNodes(i + 1);

            List<HekateTestNode> allNodes = new ArrayList<>();

            allNodes.addAll(permanentNodes);

            repeat(3, j -> {
                HekateTestNode node = createNode();

                allNodes.add(node);

                node.join();

                allNodes.forEach(n -> n.awaitForTopology(allNodes));

                node.leave();

                allNodes.remove(node);

                allNodes.forEach(n -> n.awaitForTopology(allNodes));
            });

            for (HekateTestNode node : permanentNodes) {
                node.leave();
            }
        });
    }

    @Test
    public void testJoinLeaveSameTimeWithPermanentNodes() throws Exception {
        repeat(5, i -> {
            List<HekateTestNode> permanentNodes = createAndJoinNodes(i + 1);

            List<HekateTestNode> allNodes = new ArrayList<>();

            allNodes.addAll(permanentNodes);

            AtomicReference<HekateTestNode> toLeave = new AtomicReference<>(createNode());

            toLeave.get().join();

            allNodes.add(toLeave.get());

            repeat(3, j -> {
                HekateTestNode newNode = createNode();

                JoinFuture join = newNode.joinAsync();
                LeaveFuture leave = toLeave.get().leaveAsync();

                join.get();
                leave.get();

                allNodes.remove(toLeave.get());
                toLeave.set(newNode);

                allNodes.add(newNode);

                allNodes.forEach(n -> n.awaitForTopology(allNodes));
            });

            for (HekateTestNode node : allNodes) {
                node.leave();
            }
        });
    }

    @Test
    public void testJoinLeaveNoWaitWithPermanentNodes() throws Exception {
        repeat(5, i -> {
            List<HekateTestNode> permanentNodes = createAndJoinNodes(i + 1);

            repeat(3, j -> {
                HekateTestNode newNode = createNode();

                JoinFuture join = newNode.joinAsync();
                LeaveFuture leave = newNode.leaveAsync();

                join.get();
                leave.get();
            });

            for (HekateTestNode node : permanentNodes) {
                node.leave();
            }
        });
    }

    @Test
    public void testJoinTerminateNoWaitWithPermanentNodes() throws Exception {
        repeat(5, i -> {
            List<HekateTestNode> permanentNodes = createAndJoinNodes(i + 1);

            repeat(3, j -> {
                HekateTestNode newNode = createNode();

                JoinFuture join = newNode.joinAsync();
                TerminateFuture terminate = newNode.terminateAsync();

                join.get();
                terminate.get();
            });

            for (HekateTestNode node : permanentNodes) {
                node.leave();
            }
        });
    }

    @Test
    public void testLeaveJoinSameTimeWithPermanentNodes() throws Exception {
        repeat(5, i -> {
            List<HekateTestNode> permanentNodes = createAndJoinNodes(i + 1);

            List<HekateTestNode> allNodes = new ArrayList<>();

            allNodes.addAll(permanentNodes);

            AtomicReference<HekateTestNode> toLeave = new AtomicReference<>(createNode());

            toLeave.get().join();

            allNodes.add(toLeave.get());

            repeat(3, j -> {
                HekateTestNode newNode = createNode();

                LeaveFuture leave = toLeave.get().leaveAsync();
                JoinFuture join = newNode.joinAsync();

                join.get();
                leave.get();

                allNodes.remove(toLeave.get());
                toLeave.set(newNode);

                allNodes.add(newNode);

                allNodes.forEach(n -> n.awaitForTopology(allNodes));
            });

            for (HekateTestNode node : allNodes) {
                node.leave();
            }
        });
    }

    @Test
    public void testJoinTerminateSameTimeWithPermanentNodes() throws Exception {
        disableNodeFailurePostCheck();

        repeat(3, i -> {
            List<HekateTestNode> permanentNodes = createAndJoinNodes(i + 1);

            List<HekateTestNode> allNodes = new ArrayList<>();

            allNodes.addAll(permanentNodes);

            AtomicReference<HekateTestNode> toTerminate = new AtomicReference<>(createNode());

            toTerminate.get().join();

            allNodes.add(toTerminate.get());

            repeat(3, j -> {
                HekateTestNode newNode = createNode();

                JoinFuture join = newNode.joinAsync();
                toTerminate.get().terminate();

                join.get();

                allNodes.remove(toTerminate.get());
                toTerminate.set(newNode);

                allNodes.add(newNode);

                allNodes.forEach(n -> n.awaitForTopology(allNodes));
            });

            for (HekateTestNode node : allNodes) {
                node.leave();
            }
        });
    }

    @Test
    public void testTerminateJoinSameTimeWithPermanentNodes() throws Exception {
        disableNodeFailurePostCheck();

        repeat(3, i -> {
            List<HekateTestNode> permanentNodes = createAndJoinNodes(i + 1);

            List<HekateTestNode> allNodes = new ArrayList<>();

            allNodes.addAll(permanentNodes);

            AtomicReference<HekateTestNode> toTerminate = new AtomicReference<>(createNode());

            toTerminate.get().join();

            allNodes.add(toTerminate.get());

            repeat(3, j -> {
                HekateTestNode newNode = createNode();

                toTerminate.get().terminate();
                JoinFuture join = newNode.joinAsync();

                join.get();

                allNodes.remove(toTerminate.get());
                toTerminate.set(newNode);

                allNodes.add(newNode);

                allNodes.forEach(n -> n.awaitForTopology(allNodes));
            });

            for (HekateTestNode node : allNodes) {
                node.leave();
            }
        });
    }

    @Test
    public void testClusterView() throws Exception {
        HekateTestNode node1 = createNode(c -> c.withNodeRole("VIEW_1"));
        HekateTestNode node2 = createNode(c -> {
            c.withNodeRole("VIEW_1");
            c.withNodeRole("VIEW_2");
        });
        HekateTestNode node3 = createNode(c -> c.withNodeRole("VIEW_1"));

        node1.join();
        node2.join();
        node3.join();

        awaitForTopology(node1, node2, node3);

        ClusterView view = node1.cluster().filter(n -> n.hasRole("VIEW_1"));

        assertEquals(2, view.getTopology().getRemoteNodes().size());
        assertTrue(view.getTopology().getRemoteNodes().contains(node2.getLocalNode()));
        assertTrue(view.getTopology().getRemoteNodes().contains(node3.getLocalNode()));

        view = node1.cluster().filter(n -> n.hasRole("VIEW_2"));

        assertEquals(1, view.getTopology().getRemoteNodes().size());
        assertTrue(view.getTopology().getRemoteNodes().contains(node2.getLocalNode()));

        CountDownLatch eventsLatch = new CountDownLatch(1);

        List<ClusterEvent> events = new CopyOnWriteArrayList<>();

        view.addListener(e -> {
            events.add(e);

            if (e.getType() == ClusterEventType.CHANGE) {
                eventsLatch.countDown();
            }
        });

        ClusterNode clusterNode2 = node2.getLocalNode();

        node2.leave();

        node1.awaitForTopology(node1, node3);

        HekateTestBase.await(eventsLatch);

        assertEquals(2, events.size());

        ClusterJoinEvent join = events.get(0).asJoin();
        ClusterChangeEvent change = events.get(1).asChange();

        assertNotNull(join);
        assertNotNull(change);

        assertEquals(1, join.getTopology().getRemoteNodes().size());
        assertTrue(join.getTopology().getRemoteNodes().contains(clusterNode2));

        assertEquals(0, change.getTopology().getRemoteNodes().size());

        events.clear();

        node2.leave();
        node1.leave();

        assertEquals(1, events.size());

        ClusterLeaveEvent leave = events.get(0).asLeave();

        assertNotNull(leave);
        assertEquals(0, leave.getTopology().getRemoteNodes().size());
    }

    @Test
    public void testJoinOrderRemoveOldest() throws Exception {
        LinkedList<HekateTestNode> nodes = new LinkedList<>();

        AtomicInteger expectedOrder = new AtomicInteger();

        repeat(3, i -> {
            repeat(5, j -> {
                expectedOrder.incrementAndGet();

                HekateTestNode node = createNode();

                node.join();

                nodes.add(node);

                awaitForTopology(nodes);

                assertEquals(expectedOrder.get(), node.getLocalNode().getJoinOrder());

                nodes.forEach(n -> {
                    assertEquals(nodes.getFirst().getLocalNode(), n.getTopology().getOldest());
                    assertEquals(nodes.getLast().getLocalNode(), n.getTopology().getYoungest());
                });
            });

            nodes.removeLast().leaveAsync();
            nodes.removeLast().leaveAsync();
        });
    }

    @Test
    public void testJoinOrderRemoveYoungest() throws Exception {
        LinkedList<HekateTestNode> nodes = new LinkedList<>();

        AtomicInteger expectedOrder = new AtomicInteger();

        repeat(3, i -> {
            repeat(3, j -> {
                expectedOrder.incrementAndGet();

                HekateTestNode node = createNode();

                node.join();

                nodes.add(node);

                awaitForTopology(nodes);

                assertEquals(expectedOrder.get(), node.getLocalNode().getJoinOrder());

                nodes.forEach(n -> {
                    assertEquals(nodes.getFirst().getLocalNode(), n.getTopology().getOldest());
                    assertEquals(nodes.getLast().getLocalNode(), n.getTopology().getYoungest());
                });
            });

            nodes.removeFirst().leave();
        });
    }

    @Test
    public void testHasService() throws Exception {
        List<HekateTestNode> nodes = new ArrayList<>();

        for (int j = 0; j < 3; j++) {
            HekateTestNode node = createNode(b -> b.withService(() -> new DummyService() {
                // No-op.
            }));

            node.join();

            nodes.add(node);
        }

        nodes.forEach(n -> n.awaitForTopology(nodes));

        for (HekateTestNode node : nodes) {
            assertTrue(node.has(DummyService.class));
            assertNotNull(node.get(DummyService.class));

            assertTrue(node.getLocalNode().hasService(DummyService.class));

            for (ClusterNode clusterNode : node.getTopology().getRemoteNodes()) {
                assertTrue(clusterNode.hasService(DummyService.class));
            }
        }
    }

    @Test
    public void testJoinRejectCustom() throws Exception {
        String rejectReason = HekateTestBase.TEST_ERROR_PREFIX + " This is a test reject.";

        HekateTestNode existing = createNode(c ->
            c.find(ClusterServiceFactory.class).get().withJoinValidator((newNode, node) -> rejectReason)
        );

        existing.join();

        HekateTestNode joining = createNode();

        try {
            joining.join();

            fail("Error was expected.");
        } catch (HekateFutureException e) {
            ClusterJoinRejectedException cause = e.findCause(ClusterJoinRejectedException.class);

            assertEquals(rejectReason, cause.getRejectReason());
            assertEquals(existing.getLocalNode().getAddress(), cause.getRejectedBy());
        }

        assertSame(Hekate.State.DOWN, joining.getState());
    }

    @Test
    public void testJoinRejectLoopback() throws Exception {
        HekateTestNode existing = createNode(c -> {
            DefaultAddressSelectorConfig cfg = new DefaultAddressSelectorConfig();

            cfg.setExcludeLoopback(false);
            cfg.setIpVersion(IpVersion.V4);
            cfg.setIpNotMatch("127.0.0.1");

            c.withService(NetworkServiceFactory.class, net -> {
                net.setHost(null);
                net.setAddressSelector(new DefaultAddressSelector(cfg));
            });
        });

        existing.join();

        HekateTestNode joining = createNode(c -> {
            DefaultAddressSelectorConfig cfg = new DefaultAddressSelectorConfig();

            cfg.setExcludeLoopback(false);
            cfg.setIpVersion(IpVersion.V4);
            cfg.setIpMatch("127.0.0.1");

            c.withService(NetworkServiceFactory.class, net -> {
                net.setHost(null);
                net.setAddressSelector(new DefaultAddressSelector(cfg));
            });
        });

        try {
            joining.join();

            fail("Error was expected.");
        } catch (HekateFutureException e) {
            String reason = "Cluster is configured with non-loopback addresses while node is configured to use a loopback address.";

            ClusterJoinRejectedException cause = e.findCause(ClusterJoinRejectedException.class);

            assertEquals(reason, cause.getRejectReason());
            assertEquals(existing.getLocalNode().getAddress(), cause.getRejectedBy());
        }

        assertSame(Hekate.State.DOWN, joining.getState());
    }

    @Test
    public void testJoinRejectNonLoopback() throws Exception {
        HekateTestNode existing = createNode(c -> {
            DefaultAddressSelectorConfig cfg = new DefaultAddressSelectorConfig();

            cfg.setExcludeLoopback(false);
            cfg.setIpVersion(IpVersion.V4);
            cfg.setIpMatch("127.0.0.1");

            c.withService(NetworkServiceFactory.class, net -> {
                net.setHost(null);
                net.setAddressSelector(new DefaultAddressSelector(cfg));
            });
        });

        existing.join();

        HekateTestNode joining = createNode(c -> {
            DefaultAddressSelectorConfig cfg = new DefaultAddressSelectorConfig();

            cfg.setExcludeLoopback(false);
            cfg.setIpVersion(IpVersion.V4);
            cfg.setIpNotMatch("127.0.0.1");

            c.withService(NetworkServiceFactory.class, net -> {
                net.setHost(null);
                net.setAddressSelector(new DefaultAddressSelector(cfg));
            });
        });

        try {
            joining.join();

            fail("Error was expected.");
        } catch (HekateFutureException e) {

            String reason = "Cluster is configured with loopback addresses while node is configured to use a non-loopback address.";
            ClusterJoinRejectedException cause = e.findCause(ClusterJoinRejectedException.class);

            assertEquals(reason, cause.getRejectReason());
            assertEquals(existing.getLocalNode().getAddress(), cause.getRejectedBy());
        }

        assertSame(Hekate.State.DOWN, joining.getState());
    }

    @Test
    public void testCoordinatorFailureWhileJoining() throws Exception {
        disableNodeFailurePostCheck();

        HekateTestNode coordinator = createNode().join();

        HekateTestNode joining = createNode();

        joining.setGossipSpy(new GossipSpyAdaptor() {
            @Override
            public void onStatusChange(GossipNodeStatus oldStatus, GossipNodeStatus newStatus, int order, Set<ClusterNode> topology) {
                if (newStatus == GossipNodeStatus.JOINING) {
                    HekateTestBase.say("Terminating coordinator: " + coordinator.getLocalNode());

                    try {
                        coordinator.terminate();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }
            }

            @Override
            public void onNodeFailureSuspected(ClusterNode failed, GossipNodeStatus status) {
                HekateTestBase.say("Coordinator failure detected: " + failed);
            }
        });

        get(joining.joinAsync());

        assertSame(Hekate.State.DOWN, coordinator.getState());

        assertSame(Hekate.State.UP, joining.getState());

        awaitForTopology(joining);
    }

    private void verifyJoinOrder(List<HekateTestNode> nodes) {
        List<HekateTestNode> sorted = new ArrayList<>(nodes);

        sorted.sort(Comparator.comparingInt(o -> o.getLocalNode().getJoinOrder()));

        for (int i = 0; i < sorted.size(); i++) {
            assertEquals(i + 1, sorted.get(i).getLocalNode().getJoinOrder());
        }
    }
}

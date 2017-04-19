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

package io.hekate.task.internal;

import io.hekate.HekateTestContext;
import io.hekate.core.Hekate;
import io.hekate.core.internal.HekateTestNode;
import io.hekate.task.MultiNodeResult;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import org.junit.Test;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TaskBroadcastTest extends TaskServiceTestBase {
    public TaskBroadcastTest(HekateTestContext params) {
        super(params);
    }

    @Test
    public void test() throws Exception {
        repeat(3, i -> {
            List<HekateTestNode> nodes = createAndJoin(i + 1);

            for (HekateTestNode node : nodes) {
                MultiNodeResult<Void> result = get(node.tasks().broadcast(() -> {
                    COUNTER.incrementAndGet();

                    NODES.add(node.getLocalNode());
                }));

                assertEquals(nodes.size(), COUNTER.get());
                assertTrue(NODES.toString(), NODES.containsAll(nodes.stream().map(Hekate::getLocalNode).collect(toList())));
                assertTrue(result.isSuccess());
                nodes.forEach(n -> assertTrue(result.isSuccess(n.getLocalNode())));
                assertTrue(result.nodes().containsAll(nodes.stream().map(Hekate::getLocalNode).collect(toList())));

                NODES.forEach(n -> assertTrue(result.toString().contains(n.toString())));

                NODES.clear();
                COUNTER.set(0);
            }

            nodes.forEach(n -> n.leaveAsync().join());
        });
    }

    @Test
    public void testAffinity() throws Exception {
        repeat(3, i -> {
            List<HekateTestNode> nodes = createAndJoin(i + 1);

            for (HekateTestNode node : nodes) {
                MultiNodeResult<Void> affResult = get(node.tasks().withAffinity(100500).broadcast(() -> {
                    COUNTER.incrementAndGet();

                    NODES.add(node.getLocalNode());
                }));

                assertEquals(nodes.size(), COUNTER.get());
                assertTrue(NODES.toString(), NODES.containsAll(nodes.stream().map(Hekate::getLocalNode).collect(toList())));
                assertTrue(affResult.isSuccess());
                nodes.forEach(n -> assertTrue(affResult.isSuccess(n.getLocalNode())));
                assertTrue(affResult.nodes().containsAll(nodes.stream().map(Hekate::getLocalNode).collect(toList())));

                NODES.clear();
                COUNTER.set(0);
            }

            nodes.forEach(n -> n.leaveAsync().join());
        });
    }

    @Test
    public void testError() throws Exception {
        repeat(3, i -> {
            List<HekateTestNode> nodes = createAndJoin(i + 1);

            for (HekateTestNode node : nodes) {
                MultiNodeResult<Void> errResult = get(node.tasks().broadcast(() -> {
                    throw TEST_ERROR;
                }));

                assertFalse(errResult.isSuccess());
                assertTrue(errResult.results().isEmpty());
                nodes.forEach(n -> {
                    assertFalse(errResult.isSuccess(n.getLocalNode()));
                    assertNotNull(errResult.errorOf(n.getLocalNode()));

                    assertTrue(errResult.errorOf(n.getLocalNode()).getMessage().contains(TestAssertionError.class.getName()));
                    assertTrue(errResult.errorOf(n.getLocalNode()).getMessage().contains(TEST_ERROR_MESSAGE));
                });

                NODES.clear();
                COUNTER.set(0);
            }

            nodes.forEach(n -> n.leaveAsync().join());
        });
    }

    @Test
    public void testPartialError() throws Exception {
        repeat(3, i -> {
            List<HekateTestNode> nodes = createAndJoin(i + 1);

            for (HekateTestNode node : nodes) {
                MultiNodeResult<Void> partErrResult = get(node.tasks().broadcast(() -> {
                    if (node.cluster().getTopology().getYoungest().equals(node.getLocalNode())) {
                        throw TEST_ERROR;
                    }
                }));

                assertFalse(partErrResult.isSuccess());

                nodes.forEach(n -> {
                    if (n.cluster().getTopology().getYoungest().equals(n.getLocalNode())) {
                        assertFalse(partErrResult.isSuccess(n.getLocalNode()));
                        assertNotNull(partErrResult.errorOf(n.getLocalNode()));
                        assertNotNull(partErrResult.errors().get(n.getLocalNode()));

                        assertTrue(partErrResult.errorOf(n.getLocalNode()).getMessage().contains(TestAssertionError.class.getName()));
                        assertTrue(partErrResult.errorOf(n.getLocalNode()).getMessage().contains(TEST_ERROR_MESSAGE));
                    } else {
                        assertTrue(partErrResult.isSuccess(n.getLocalNode()));
                    }
                });

                NODES.clear();
                COUNTER.set(0);
            }

            nodes.forEach(n -> n.leaveAsync().join());
        });
    }

    @Test
    public void testBroadcastWithSourceLeave() throws Exception {
        List<HekateTestNode> nodes = createAndJoin(2);

        HekateTestNode source = nodes.get(0);
        HekateTestNode target = nodes.get(1);

        REF.set(source);

        MultiNodeResult<Void> result = get(source.tasks().forRemotes().broadcast(() -> {
            try {
                REF.get().leave();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }));

        assertFalse(result.isSuccess());
        assertEquals(ClosedChannelException.class, result.errorOf(target.getLocalNode()).getClass());

        source.awaitForStatus(Hekate.State.DOWN);

        get(target.tasks().forNode(target.getLocalNode()).broadcast(() -> REF.set(target)));

        assertSame(target, REF.get());
    }
}

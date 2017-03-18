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

import io.hekate.HekateInstanceContextTestBase;
import io.hekate.HekateTestContext;
import io.hekate.cluster.ClusterNode;
import io.hekate.cluster.ClusterServiceFactory;
import io.hekate.cluster.split.SplitBrainAction;
import io.hekate.cluster.split.SplitBrainDetectorMock;
import io.hekate.core.Hekate;
import io.hekate.core.HekateFutureException;
import io.hekate.core.HekateTestInstance;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class ClusterServiceSplitBrainTest extends HekateInstanceContextTestBase {
    public ClusterServiceSplitBrainTest(HekateTestContext params) {
        super(params);
    }

    @Test
    public void testRejoinOnJoin() throws Exception {
        SplitBrainDetectorMock detector = new SplitBrainDetectorMock(false);

        HekateTestInstance node = createInstance(c -> {
            ClusterServiceFactory cluster = c.find(ClusterServiceFactory.class).get();

            cluster.setSplitBrainAction(SplitBrainAction.REJOIN);
            cluster.setSplitBrainDetector(detector);
        });

        Future<HekateTestInstance> future = runAsync(node::join);

        detector.awaitForChecks(5);

        assertSame(Hekate.State.INITIALIZING, node.getState());

        detector.setValid(true);

        future.get(3, TimeUnit.SECONDS);

        assertEquals(1, detector.getChecks());
    }

    @Test
    public void testRejoinWhenOtherNodeLeaves() throws Exception {
        SplitBrainDetectorMock detector = new SplitBrainDetectorMock(true);

        HekateTestInstance node = createInstance(c -> {
            ClusterServiceFactory cluster = c.find(ClusterServiceFactory.class).get();

            cluster.setSplitBrainAction(SplitBrainAction.REJOIN);
            cluster.setSplitBrainDetector(detector);
        }).join();

        HekateTestInstance leaving = createInstance().join();

        awaitForTopology(leaving, node);

        detector.setValid(false);

        leaving.leave();

        node.awaitForStatus(Hekate.State.INITIALIZING);

        detector.awaitForChecks(5);

        detector.setValid(true);

        node.awaitForStatus(Hekate.State.UP);
    }

    @Test
    public void testTerminateWhenOtherNodeLeaves() throws Exception {
        SplitBrainDetectorMock detector = new SplitBrainDetectorMock(true);

        HekateTestInstance node = createInstance(c -> {
            ClusterServiceFactory cluster = c.find(ClusterServiceFactory.class).get();

            cluster.setSplitBrainAction(SplitBrainAction.TERMINATE);
            cluster.setSplitBrainDetector(detector);
        }).join();

        HekateTestInstance leaving = createInstance().join();

        awaitForTopology(leaving, node);

        detector.setValid(false);

        leaving.leave();

        node.awaitForStatus(Hekate.State.DOWN);

        assertEquals(1, detector.getChecks());
    }

    @Test
    public void testTerminateOnDetectorError() throws Exception {
        HekateTestInstance node = createInstance(c -> {
            ClusterServiceFactory cluster = c.find(ClusterServiceFactory.class).get();

            cluster.setSplitBrainAction(SplitBrainAction.REJOIN);
            cluster.setSplitBrainDetector(localNode -> {
                throw TEST_ERROR;
            });
        });

        try {
            node.join();

            fail("Error was expected.");
        } catch (HekateFutureException e) {
            assertSame(TEST_ERROR, e.getCause());
        }
    }

    @Test
    public void testTerminateOnDetectorErrorWhenOtherNodeLeaves() throws Exception {
        CountDownLatch errorLatch = new CountDownLatch(1);

        SplitBrainDetectorMock detector = new SplitBrainDetectorMock(true) {
            @Override
            public synchronized boolean isValid(ClusterNode localNode) {
                if (getChecks() == 5) {
                    errorLatch.countDown();

                    throw TEST_ERROR;
                }

                return super.isValid(localNode);
            }
        };

        HekateTestInstance node = createInstance(c -> {
            ClusterServiceFactory cluster = c.find(ClusterServiceFactory.class).get();

            cluster.setSplitBrainAction(SplitBrainAction.REJOIN);
            cluster.setSplitBrainDetector(detector);
        }).join();

        HekateTestInstance leaving = createInstance().join();

        awaitForTopology(leaving, node);

        detector.setValid(false);

        leaving.leave();

        await(errorLatch);

        node.awaitForStatus(Hekate.State.DOWN);
    }
}
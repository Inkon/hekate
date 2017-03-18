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
import io.hekate.cluster.seed.SeedNodeProvider;
import io.hekate.cluster.seed.SeedNodeProviderAdaptor;
import io.hekate.core.HekateException;
import io.hekate.network.NetworkService;
import io.hekate.network.PingCallback;
import io.hekate.network.PingResult;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

public class SeedNodeManagerTest extends HekateTestBase {
    @Test
    public void testNeverReturnsNull() throws Exception {
        SeedNodeManager manager = createManager(new SeedNodeProviderAdaptor() {
            @Override
            public List<InetSocketAddress> getSeedNodes(String cluster) throws HekateException {
                return null;
            }
        });

        List<InetSocketAddress> nodes = manager.getSeedNodes();

        assertNotNull(nodes);
        assertTrue(nodes.isEmpty());
    }

    @Test
    public void testErrorOnGetSeedNodes() throws Exception {
        SeedNodeManager manager = createManager(new SeedNodeProviderAdaptor() {
            @Override
            public List<InetSocketAddress> getSeedNodes(String cluster) throws HekateException {
                throw TEST_ERROR;
            }
        });

        try {
            manager.getSeedNodes();

            fail("Error was expected.");
        } catch (AssertionError e) {
            assertEquals(TEST_ERROR_MESSAGE, e.getMessage());
        }
    }

    @Test
    public void testErrorOnStartDiscovery() throws Exception {
        SeedNodeManager manager = createManager(new SeedNodeProviderAdaptor() {
            @Override
            public void startDiscovery(String cluster, InetSocketAddress node) throws HekateException {
                throw TEST_ERROR;
            }
        });

        try {
            manager.startDiscovery(newSocketAddress());

            fail("Error was expected.");
        } catch (AssertionError e) {
            assertEquals(TEST_ERROR_MESSAGE, e.getMessage());
        }
    }

    @Test
    public void testNoErrorOnSuspendDiscovery() throws Exception {
        SeedNodeManager manager = createManager(new SeedNodeProviderAdaptor() {
            @Override
            public void suspendDiscovery() throws HekateException {
                throw TEST_ERROR;
            }
        });

        manager.suspendDiscovery();
    }

    @Test
    public void testNoErrorOnStopDiscovery() throws Exception {
        SeedNodeManager manager = createManager(new SeedNodeProviderAdaptor() {
            @Override
            public void startDiscovery(String cluster, InetSocketAddress node) throws HekateException {
                throw TEST_ERROR;
            }
        });

        manager.stopDiscovery(newSocketAddress());
    }

    @Test
    public void testStopCleaningWithoutStarting() throws Exception {
        SeedNodeManager manager = createManager(new SeedNodeProviderAdaptor());

        manager.stopCleaning();
        manager.stopCleaning();
        manager.stopCleaning();
    }

    @Test
    public void testCleaning() throws Exception {
        Map<InetSocketAddress, Boolean> addresses = new ConcurrentHashMap<>();
        Map<InetSocketAddress, Boolean> canPing = new ConcurrentHashMap<>();
        Map<InetSocketAddress, Boolean> alive = new ConcurrentHashMap<>();

        InetSocketAddress address1 = newSocketAddress();
        InetSocketAddress address2 = newSocketAddress();
        InetSocketAddress address3 = newSocketAddress();

        addresses.put(address1, true);
        addresses.put(address2, true);
        addresses.put(address3, true);

        canPing.putAll(addresses);
        alive.putAll(addresses);

        Exchanger<String> latch = new Exchanger<>();

        SeedNodeManager manager = createManager(new SeedNodeProviderAdaptor() {
            @Override
            public List<InetSocketAddress> getSeedNodes(String cluster) throws HekateException {
                return new ArrayList<>(addresses.keySet());
            }

            @Override
            public long getCleanupInterval() {
                return 1;
            }

            @Override
            public void registerRemoteAddress(String cluster, InetSocketAddress address) throws HekateException {
                addresses.put(address, true);

                try {
                    latch.exchange("register-" + address, 3, TimeUnit.SECONDS);
                } catch (InterruptedException | TimeoutException e) {
                    throw new HekateException("Unexpected timing error.", e);
                }
            }

            @Override
            public void unregisterRemoteAddress(String cluster, InetSocketAddress address) throws HekateException {
                addresses.remove(address);

                try {
                    latch.exchange("unregister-" + address, 3, TimeUnit.SECONDS);
                } catch (InterruptedException | TimeoutException e) {
                    throw new HekateException("Unexpected timing error.", e);
                }
            }
        });

        NetworkService netMock = mock(NetworkService.class);

        doAnswer(invocation -> {
            InetSocketAddress address = (InetSocketAddress)invocation.getArguments()[0];
            PingCallback callback = (PingCallback)invocation.getArguments()[1];

            if (canPing.containsKey(address)) {
                callback.onResult(address, PingResult.SUCCESS);
            } else {
                callback.onResult(address, PingResult.FAILURE);
            }

            return null;
        }).when(netMock).ping(any(InetSocketAddress.class), any(PingCallback.class));

        manager.startCleaning(netMock, alive::keySet);

        try {
            List<InetSocketAddress> nodes = manager.getSeedNodes();

            assertTrue(nodes.contains(address1));
            assertTrue(nodes.contains(address2));
            assertTrue(nodes.contains(address3));

            canPing.remove(address2);
            alive.remove(address2);

            assertEquals("unregister-" + address2, latch.exchange(null, 3, TimeUnit.SECONDS));

            nodes = manager.getSeedNodes();

            assertTrue(nodes.contains(address1));
            assertFalse(nodes.contains(address2));
            assertTrue(nodes.contains(address3));

            alive.put(address2, true);

            assertEquals("register-" + address2, latch.exchange(null, 3, TimeUnit.SECONDS));

            nodes = manager.getSeedNodes();

            assertTrue(nodes.contains(address1));
            assertTrue(nodes.contains(address2));
            assertTrue(nodes.contains(address3));
        } finally {
            manager.stopCleaning().await();
        }
    }

    private SeedNodeManager createManager(SeedNodeProvider provider) {
        return new SeedNodeManager("test", provider);
    }
}
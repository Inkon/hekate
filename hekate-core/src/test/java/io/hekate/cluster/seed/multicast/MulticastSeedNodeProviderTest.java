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

package io.hekate.cluster.seed.multicast;

import io.hekate.HekateTestProps;
import io.hekate.cluster.seed.SeedNodeProviderCommonTest;
import io.hekate.core.HekateException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MulticastSeedNodeProviderTest extends SeedNodeProviderCommonTest<MulticastSeedNodeProviderTest.TestProvider> {
    public static class TestProvider extends MulticastSeedNodeProvider {
        private final AtomicInteger receivedDiscovery = new AtomicInteger();

        public TestProvider(MulticastSeedNodeProviderConfig cfg) throws IOException {
            super(cfg);
        }

        public int getReceivedDiscovery() {
            return receivedDiscovery.get();
        }

        public void clearReceivedDiscovery() {
            receivedDiscovery.set(0);
        }

        @Override
        NetworkInterface getMulticastInterface(InetSocketAddress address) throws HekateException {
            NetworkInterface nif = INTERFACES_CACHE.get(address.getAddress());

            if (nif == null) {
                nif = super.getMulticastInterface(address);

                INTERFACES_CACHE.put(address.getAddress(), nif);
            }

            return nif;
        }

        @Override
        void onDiscoveryMessage(InetSocketAddress address) {
            super.onDiscoveryMessage(address);

            receivedDiscovery.incrementAndGet();
        }
    }

    public static final int DISCOVERY_INTERVAL = 50;

    public static final int RESPONSE_WAIT_TIME = 100;

    private static final Map<InetAddress, NetworkInterface> INTERFACES_CACHE = new ConcurrentHashMap<>();

    @BeforeClass
    public static void mayBeDisableTest() {
        Assume.assumeTrue(Boolean.valueOf(HekateTestProps.get("MULTICAST_ENABLED")));
    }

    @AfterClass
    public static void clearCache() {
        INTERFACES_CACHE.clear();
    }

    @Test
    public void testSuspendDiscovery() throws Exception {
        Map<InetSocketAddress, TestProvider> providers = new HashMap<>();

        try {
            for (int i = 0; i < 3; i++) {
                TestProvider provider = createProvider();

                InetSocketAddress address = newSocketAddress(10000 + i);

                providers.put(address, provider);

                provider.startDiscovery(CLUSTER_1, address);
            }

            for (Map.Entry<InetSocketAddress, TestProvider> e : providers.entrySet()) {
                TestProvider provider = e.getValue();

                assertTrue(e.getKey().toString(), provider.getReceivedDiscovery() > 0);

                provider.suspendDiscovery();
            }

            sleep(DISCOVERY_INTERVAL * 4);

            providers.values().forEach(TestProvider::clearReceivedDiscovery);

            sleep(DISCOVERY_INTERVAL * 2);

            providers.values().forEach(p -> assertEquals(0, p.getReceivedDiscovery()));

        } finally {
            for (Map.Entry<InetSocketAddress, TestProvider> e : providers.entrySet()) {
                e.getValue().stopDiscovery(CLUSTER_1, e.getKey());

                assertTrue(e.getValue().getSeedNodes(CLUSTER_1).isEmpty());
            }
        }
    }

    @Test
    public void testThreadInterruptionOnStart() throws Exception {
        TestProvider provider = createProvider();

        Thread.currentThread().interrupt();

        try {
            provider.startDiscovery(CLUSTER_1, newSocketAddress());

            fail("Error was expected.");
        } catch (HekateException e) {
            assertTrue(getStacktrace(e), e.isCausedBy(InterruptedException.class));

            assertTrue(Thread.currentThread().isInterrupted());

            Thread.interrupted();
        }
    }

    @Test
    public void testConfig() throws Exception {
        TestProvider provider = createProvider(c -> c
            .withWaitTime(987)
            .withGroup("224.1.2.14")
            .withPort(MulticastSeedNodeProviderConfig.DEFAULT_PORT + 1)
            .withTtl(789)
            .withInterval(123)
        );

        assertEquals(987, provider.getWaitTime());
        assertEquals("224.1.2.14", provider.getGroup().getAddress().getHostAddress());
        assertEquals(MulticastSeedNodeProviderConfig.DEFAULT_PORT + 1, provider.getGroup().getPort());
        assertEquals(789, provider.getTtl());
        assertEquals(123, provider.getInterval());
        assertEquals(0, provider.getCleanupInterval());
    }

    @Override
    protected TestProvider createProvider() throws Exception {
        return createProvider(null);
    }

    protected TestProvider createProvider(Consumer<MulticastSeedNodeProviderConfig> configurer) throws Exception {
        MulticastSeedNodeProviderConfig cfg = new MulticastSeedNodeProviderConfig();

        cfg.setInterval(DISCOVERY_INTERVAL);
        cfg.setWaitTime(RESPONSE_WAIT_TIME);

        if (configurer != null) {
            configurer.accept(cfg);
        }

        return new TestProvider(cfg);
    }
}
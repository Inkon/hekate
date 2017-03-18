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

package io.hekate.javadoc.lock;

import io.hekate.HekateInstanceTestBase;
import io.hekate.core.Hekate;
import io.hekate.core.HekateBootstrap;
import io.hekate.lock.DistributedLock;
import io.hekate.lock.LockRegionConfig;
import io.hekate.lock.LockService;
import io.hekate.lock.LockServiceFactory;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class LockServiceJavadocTest extends HekateInstanceTestBase {
    @Test
    public void exampleAccessService() throws Exception {
        // Start:configure
        // Prepare lock service factory.
        LockServiceFactory factory = new LockServiceFactory()
            // Register some lock regions.
            .withRegion(new LockRegionConfig()
                .withName("region1")
            )
            .withRegion(new LockRegionConfig()
                .withName("region2")
            );

        // Start node.
        Hekate hekate = new HekateBootstrap()
            .withService(factory)
            .join();
        // End:configure

        // Start:access
        LockService locks = hekate.get(LockService.class);
        // End:access

        assertNotNull(locks);

        // Start:lock
        // Get lock instance with name 'exampleLock' from region 'region1'.
        DistributedLock lock = locks.region("region1").getLock("exampleLock");

        // Obtain the lock.
        lock.lock();

        try {
            // Do some work ...
            thereCanBeOnlyOne();
        } finally {
            // Make sure that lock is always released after the work is done.
            lock.unlock();
        }
        // End:lock

        hekate.leave();
    }

    private void thereCanBeOnlyOne() {
        // No-op.
    }
}
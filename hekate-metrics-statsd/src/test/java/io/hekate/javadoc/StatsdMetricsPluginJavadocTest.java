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

package io.hekate.javadoc;

import io.hekate.core.Hekate;
import io.hekate.core.HekateBootstrap;
import io.hekate.metrics.statsd.StatsdMetricsConfig;
import io.hekate.metrics.statsd.StatsdMetricsPlugin;
import io.hekate.metrics.statsd.StatsdMetricsTestBase;
import java.net.InetAddress;
import org.junit.Test;

public class StatsdMetricsPluginJavadocTest extends StatsdMetricsTestBase {
    @Test
    public void test() throws Exception {
        //Start:configure
        StatsdMetricsConfig statsdCfg = new StatsdMetricsConfig()
            .withHost("my-statsd-host-or-ip-address")
            .withPort(8125);
        //End:configure

        statsdCfg.setHost(InetAddress.getLocalHost().getHostAddress());
        statsdCfg.setPort(testPort);

        // Start:boot
        Hekate node = new HekateBootstrap()
            .withPlugin(new StatsdMetricsPlugin(statsdCfg))
            .join();
        // End:boot

        try {
            busyWait("test metric value", () -> {
                String metric = receiveNext();

                return metric.contains("hekate.cluster.gossip.update");
            });
        } finally {
            node.leave();
        }
    }
}
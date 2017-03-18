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
import io.hekate.core.HekateTestInstance;
import java.util.ArrayList;
import java.util.List;

public abstract class ClusterServiceMultipleNodesTestBase extends HekateInstanceContextTestBase {
    public ClusterServiceMultipleNodesTestBase(HekateTestContext params) {
        super(params);
    }

    protected List<HekateTestInstance> createNodes(int count) throws Exception {
        return createNodes(count, null);
    }

    protected List<HekateTestInstance> createNodes(int count, InstanceConfigurer configurer) throws Exception {
        List<HekateTestInstance> nodes = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            nodes.add(createInstance(configurer));
        }

        return nodes;
    }

    protected List<HekateTestInstance> createAndJoinNodes(int count) throws Exception {
        return createAndJoinNodes(count, null);
    }

    protected List<HekateTestInstance> createAndJoinNodes(int count, InstanceConfigurer configurer) throws Exception {
        List<HekateTestInstance> nodes = createNodes(count, configurer);

        for (HekateTestInstance node : nodes) {
            node.join();
        }

        nodes.forEach(n -> n.awaitForTopology(nodes));

        return nodes;
    }
}
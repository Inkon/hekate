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

package io.hekate.messaging.unicast;

import io.hekate.cluster.ClusterFilter;
import io.hekate.cluster.ClusterNodeFilter;
import io.hekate.cluster.ClusterTopology;
import io.hekate.failover.FailoverPolicy;
import io.hekate.failover.FailureInfo;
import io.hekate.messaging.MessagingChannel;
import java.util.Optional;

/**
 * Context for {@link LoadBalancer}.
 *
 * @see LoadBalancer
 */
public interface LoadBalancerContext extends ClusterTopology {
    /**
     * Returns the cluster topology.
     *
     * @return Cluster topology.
     */
    ClusterTopology getTopology();

    /**
     * Returns <tt>true</tt> if the messaging operation has an affinity key (see {@link #getAffinityKey()}).
     *
     * @return <tt>true</tt> if the messaging operation has an affinity key.
     */
    boolean hasAffinity();

    /**
     * Returns the hash code of affinity key or a synthetically generated value if affinity key was not specified for the messaging
     * operation.
     *
     * @return Hash code of affinity key.
     */
    int getAffinity();

    /**
     * Returns the affinity key of the messaging operation or {@code null} if the affinity key wasn't specified.
     *
     * @return Affinity key or {@code null}.
     *
     * @see MessagingChannel#withAffinity(Object)
     */
    Object getAffinityKey();

    /**
     * Returns information about a failover attempt if this context represents a {@link FailoverPolicy} re-routing attempts.
     *
     * @return Information about a failover attempt.
     *
     * @see FailoverPolicy
     */
    Optional<FailureInfo> getFailure();

    @Override
    LoadBalancerContext filter(ClusterNodeFilter filter);

    @Override
    LoadBalancerContext filterAll(ClusterFilter filter);
}

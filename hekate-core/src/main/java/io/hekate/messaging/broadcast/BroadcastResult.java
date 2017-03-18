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

package io.hekate.messaging.broadcast;

import io.hekate.cluster.ClusterNode;
import io.hekate.messaging.MessagingChannel;
import java.util.Map;
import java.util.Set;

/**
 * Result of {@link MessagingChannel#broadcast(Object) broadcast(...)} operation.
 *
 * @param <T> Base type of broadcast message.
 *
 * @see MessagingChannel#broadcast(Object)
 * @see MessagingChannel#broadcast(Object, BroadcastCallback)
 */
public interface BroadcastResult<T> {
    /**
     * Returns the original message object that was submitted to the cluster nodes.
     *
     * @return Original message.
     */
    T getMessage();

    /**
     * Returns the broadcast operation participants. Returns an empty set if there were no suitable nodes in the cluster to perform the
     * operation.
     *
     * @return Cluster nodes that participated in the broadcast operation or an empty set if there were no suitable nodes in the cluster to
     * perform the operation.
     */
    Set<ClusterNode> getNodes();

    /**
     * Returns the map of cluster nodes and errors that happened while trying to communicate with those nodes. Returns an empty map if
     * there were no communication failures.
     *
     * @return Map of nodes and their corresponding failures. Returns an empty map if there were no communication failures.
     *
     * @see #getError(ClusterNode)
     */
    Map<ClusterNode, Throwable> getErrors();

    /**
     * Returns a communication error for the specified node or {@code null} if there was no communication failure with that node.
     *
     * @param node Cluster node (must be one of {@link #getNodes()}, otherwise results will be unpredictable).
     *
     * @return Error in case of a communication error with the specified node or {@code null}.
     *
     * @see #getErrors()
     */
    Throwable getError(ClusterNode node);

    /**
     * Returns {@code true} if broadcast completed successfully without any {@link #getErrors() errors}.
     *
     * @return {@code true} if broadcast completed successfully without any errors.
     *
     * @see #getErrors()
     */
    boolean isSuccess();

    /**
     * Returns {@code true} if there was no communication failure with the specified cluster node..
     *
     * @param node Cluster node (must be one of {@link #getNodes()}, otherwise results will be unpredictable).
     *
     * @return {@code true} if there was no communication failure with the specified cluster node.
     */
    boolean isSuccess(ClusterNode node);
}
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

package io.hekate.coordinate;

import io.hekate.cluster.ClusterNode;
import io.hekate.cluster.ClusterNodeId;
import io.hekate.cluster.ClusterTopology;
import java.util.List;

/**
 * Context for {@link CoordinationHandler}.
 */
public interface CoordinationContext {
    /**
     * Returns {@code true} if local node is the coordinator.
     *
     * @return {@code true} if local node is the coordinator.
     */
    boolean isCoordinator();

    /**
     * Returns the coordinator.
     *
     * @return Coordinator.
     */
    CoordinationMember getCoordinator();

    /**
     * Returns the cluster topology of this coordination process.
     *
     * @return Cluster topology.
     */
    ClusterTopology getTopology();

    /**
     * Returns {@code true} if this coordination process is finished (either {@link #complete() successfully} or by {@link
     * CoordinationHandler#cancel(CoordinationContext)} cancellation).
     *
     * @return {@code true} if this coordination process is finished.
     */
    boolean isDone();

    /**
     * Returns {@code true} if this coordination process was {@link CoordinationHandler#cancel(CoordinationContext) cancelled}.
     *
     * @return {@code true} if this coordination process was {@link CoordinationHandler#cancel(CoordinationContext) cancelled}.
     */
    boolean isCancelled();

    /**
     * Returns the local node member.
     *
     * @return Local node.
     */
    CoordinationMember getLocalMember();

    /**
     * Returns all members of this coordination process.
     *
     * @return Coordination members.
     */
    List<CoordinationMember> getMembers();

    /**
     * Returns member by its cluster node.
     *
     * @param node Cluster node.
     *
     * @return Member or {@code null} if there is no such member.
     */
    CoordinationMember getMember(ClusterNode node);

    /**
     * Returns member by its cluster node identifier.
     *
     * @param nodeId Cluster node identifier.
     *
     * @return Member or {@code null} if there is no such member.
     */
    CoordinationMember getMember(ClusterNodeId nodeId);

    /**
     * Returns the size of {@link #getMembers()}.
     *
     * @return Size of {@link #getMembers()}.
     */
    int getSize();

    /**
     * Asynchronously sends the specified request to all {@link #getMembers() members} of this coordination process.
     *
     * <p>
     * <b>Note:</b> Request will be send to all members including the local node.
     * </p>
     *
     * @param request Request.
     * @param callback Callback to be notified once responses have been received from all members.
     *
     * @see CoordinationHandler#process(CoordinationRequest, CoordinationContext)
     */
    void broadcast(Object request, CoordinationBroadcastCallback callback);

    /**
     * Completes this coordination process.
     */
    void complete();

    /**
     * Returns a user-defined object that is attached to this context (see {@link #setAttachment(Object)}).
     *
     * @param <T> Attachment type.
     *
     * @return Attachment.
     */
    <T> T getAttachment();

    /**
     * Sets the user-defined object that should be attached to this context.
     *
     * @param attachment Attachment.
     */
    void setAttachment(Object attachment);
}
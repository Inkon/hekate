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

package io.hekate.lock.internal;

import io.hekate.cluster.ClusterNodeId;
import io.hekate.cluster.ClusterTopologyHash;
import io.hekate.codec.Codec;
import io.hekate.codec.CodecUtils;
import io.hekate.codec.DataReader;
import io.hekate.codec.DataWriter;
import io.hekate.lock.internal.LockProtocol.LockOwnerRequest;
import io.hekate.lock.internal.LockProtocol.LockOwnerResponse;
import io.hekate.lock.internal.LockProtocol.LockRequest;
import io.hekate.lock.internal.LockProtocol.LockResponse;
import io.hekate.lock.internal.LockProtocol.MigrationApplyRequest;
import io.hekate.lock.internal.LockProtocol.MigrationPrepareRequest;
import io.hekate.lock.internal.LockProtocol.MigrationResponse;
import io.hekate.lock.internal.LockProtocol.UnlockRequest;
import io.hekate.lock.internal.LockProtocol.UnlockResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;

class LockProtocolCodec implements Codec<LockProtocol> {
    private static final LockProtocol.Type[] MESSAGE_TYPE_CACHE = LockProtocol.Type.values();

    private static final MigrationResponse.Status[] MIGRATION_STATUS_CACHE = MigrationResponse.Status.values();

    private static final LockResponse.Status[] LOCK_RESPONSE_STATUS_CACHE = LockResponse.Status.values();

    private static final UnlockResponse.Status[] UNLOCK_RESPONSE_STATUS_CACHE = UnlockResponse.Status.values();

    private static final LockOwnerResponse.Status[] OWNER_RESPONSE_STATUS_CACHE = LockOwnerResponse.Status.values();

    @Override
    public boolean isStateful() {
        return false;
    }

    @Override
    public void encode(LockProtocol msg, DataWriter out) throws IOException {
        LockProtocol.Type type = msg.getType();

        out.writeByte(type.ordinal());

        switch (type) {
            case LOCK_REQUEST: {
                LockRequest request = (LockRequest)msg;

                out.writeLong(request.getLockId());
                out.writeUTF(request.getRegion());
                out.writeUTF(request.getLockName());
                out.writeLong(request.getTimeout());
                out.writeBoolean(request.isWithFeedback());
                out.writeLong(request.getThreadId());

                CodecUtils.writeTopologyHash(request.getTopology(), out);

                CodecUtils.writeNodeId(request.getNode(), out);

                break;
            }
            case LOCK_RESPONSE: {
                LockResponse response = (LockResponse)msg;

                out.writeByte(response.getStatus().ordinal());
                out.writeLong(response.getOwnerThreadId());

                if (response.getOwner() != null) {
                    out.writeBoolean(true);

                    CodecUtils.writeNodeId(response.getOwner(), out);
                } else {
                    out.writeBoolean(false);
                }

                break;
            }
            case UNLOCK_REQUEST: {
                UnlockRequest request = (UnlockRequest)msg;

                out.writeLong(request.getLockId());
                out.writeUTF(request.getRegion());
                out.writeUTF(request.getLockName());

                CodecUtils.writeTopologyHash(request.getTopology(), out);

                CodecUtils.writeNodeId(request.getNode(), out);

                break;
            }
            case UNLOCK_RESPONSE: {
                UnlockResponse response = (UnlockResponse)msg;

                out.writeByte(response.getStatus().ordinal());

                break;
            }
            case OWNER_REQUEST: {
                LockOwnerRequest request = (LockOwnerRequest)msg;

                out.writeUTF(request.getRegion());
                out.writeUTF(request.getLockName());

                CodecUtils.writeTopologyHash(request.getTopology(), out);

                break;
            }
            case OWNER_RESPONSE: {
                LockOwnerResponse response = (LockOwnerResponse)msg;

                out.writeLong(response.getThreadId());

                ClusterNodeId owner = response.getOwner();

                if (owner == null) {
                    out.writeBoolean(false);
                } else {
                    out.writeBoolean(true);

                    CodecUtils.writeNodeId(owner, out);
                }

                out.writeByte(response.getStatus().ordinal());

                break;
            }
            case MIGRATION_PREPARE: {
                MigrationPrepareRequest request = (MigrationPrepareRequest)msg;

                out.writeUTF(request.getRegion());

                encodeKey(request.getKey(), out);

                out.writeBoolean(request.isFirstPass());

                encodeTopologies(request.getTopologies(), out);

                encodeLocksInfo(request.getLocks(), out);

                break;
            }
            case MIGRATION_APPLY: {
                MigrationApplyRequest request = (MigrationApplyRequest)msg;

                out.writeUTF(request.getRegion());

                encodeKey(request.getKey(), out);

                encodeLocksInfo(request.getLocks(), out);

                break;
            }
            case MIGRATION_RESPONSE: {
                MigrationResponse response = (MigrationResponse)msg;

                out.writeByte(response.getStatus().ordinal());

                break;
            }
            default: {
                throw new IllegalArgumentException("Unexpected message type: " + type);
            }
        }
    }

    @Override
    public LockProtocol decode(DataReader in) throws IOException {
        LockProtocol.Type type = MESSAGE_TYPE_CACHE[in.readByte()];

        switch (type) {
            case LOCK_REQUEST: {
                long lockId = in.readLong();
                String region = in.readUTF();
                String lockName = in.readUTF();
                long timeout = in.readLong();
                boolean withFeedback = in.readBoolean();
                long threadId = in.readLong();

                ClusterTopologyHash topology = CodecUtils.readTopologyHash(in);

                ClusterNodeId node = CodecUtils.readNodeId(in);

                return new LockRequest(lockId, region, lockName, node, timeout, topology, withFeedback, threadId);
            }
            case LOCK_RESPONSE: {
                LockResponse.Status status = LOCK_RESPONSE_STATUS_CACHE[in.readByte()];

                long threadId = in.readLong();

                ClusterNodeId owner = null;

                if (in.readBoolean()) {
                    owner = CodecUtils.readNodeId(in);
                }

                return new LockResponse(status, owner, threadId);
            }
            case UNLOCK_REQUEST: {
                long lockId = in.readLong();
                String region = in.readUTF();
                String lockName = in.readUTF();

                ClusterTopologyHash topology = CodecUtils.readTopologyHash(in);

                ClusterNodeId node = CodecUtils.readNodeId(in);

                return new UnlockRequest(lockId, region, lockName, node, topology);
            }
            case UNLOCK_RESPONSE: {
                UnlockResponse.Status status = UNLOCK_RESPONSE_STATUS_CACHE[in.readByte()];

                return new UnlockResponse(status);
            }
            case OWNER_REQUEST: {
                String region = in.readUTF();
                String lockName = in.readUTF();

                ClusterTopologyHash topology = CodecUtils.readTopologyHash(in);

                return new LockOwnerRequest(region, lockName, topology);
            }
            case OWNER_RESPONSE: {
                long threadId = in.readLong();

                ClusterNodeId owner = null;

                if (in.readBoolean()) {
                    owner = CodecUtils.readNodeId(in);
                }

                LockOwnerResponse.Status status = OWNER_RESPONSE_STATUS_CACHE[in.readByte()];

                return new LockOwnerResponse(threadId, owner, status);
            }
            case MIGRATION_PREPARE: {
                String region = in.readUTF();

                LockMigrationKey key = decodeKey(in);

                boolean firstPass = in.readBoolean();

                Map<ClusterNodeId, ClusterTopologyHash> topologies = decodeTopologies(in);

                List<LockMigrationInfo> locks = decodeLocksInfo(in);

                return new MigrationPrepareRequest(region, key, firstPass, topologies, locks);
            }
            case MIGRATION_APPLY: {
                String region = in.readUTF();

                LockMigrationKey key = decodeKey(in);

                List<LockMigrationInfo> locks = decodeLocksInfo(in);

                return new MigrationApplyRequest(region, key, locks);
            }
            case MIGRATION_RESPONSE: {
                MigrationResponse.Status status = MIGRATION_STATUS_CACHE[in.readByte()];

                return new MigrationResponse(status);
            }
            default: {
                throw new IllegalArgumentException("Unexpected message type: " + type);
            }
        }
    }

    private void encodeKey(LockMigrationKey key, DataWriter out) throws IOException {
        CodecUtils.writeNodeId(key.getNode(), out);

        CodecUtils.writeTopologyHash(key.getTopology(), out);

        out.writeLong(key.getId());
    }

    private LockMigrationKey decodeKey(DataReader in) throws IOException {
        ClusterNodeId node = CodecUtils.readNodeId(in);

        ClusterTopologyHash topology = CodecUtils.readTopologyHash(in);

        long id = in.readLong();

        return new LockMigrationKey(node, topology, id);
    }

    private void encodeTopologies(Map<ClusterNodeId, ClusterTopologyHash> topologies, DataWriter out) throws IOException {
        out.writeInt(topologies.size());

        for (Map.Entry<ClusterNodeId, ClusterTopologyHash> e : topologies.entrySet()) {
            CodecUtils.writeNodeId(e.getKey(), out);

            if (e.getValue() == null) {
                out.writeBoolean(false);
            } else {
                out.writeBoolean(true);

                CodecUtils.writeTopologyHash(e.getValue(), out);
            }
        }
    }

    private Map<ClusterNodeId, ClusterTopologyHash> decodeTopologies(DataReader in) throws IOException {
        int size = in.readInt();

        Map<ClusterNodeId, ClusterTopologyHash> topologies;

        if (size > 0) {
            topologies = new HashMap<>(size, 1.0f);

            for (int i = 0; i < size; i++) {
                ClusterNodeId nodeId = CodecUtils.readNodeId(in);

                ClusterTopologyHash topology = null;

                if (in.readBoolean()) {
                    topology = CodecUtils.readTopologyHash(in);
                }

                topologies.put(nodeId, topology);
            }
        } else {
            topologies = emptyMap();
        }

        return topologies;
    }

    private void encodeLocksInfo(List<LockMigrationInfo> locks, DataWriter out) throws IOException {
        out.writeInt(locks.size());

        for (LockMigrationInfo lock : locks) {
            out.writeUTF(lock.getName());
            out.writeLong(lock.getLockId());
            out.writeLong(lock.getThreadId());

            CodecUtils.writeNodeId(lock.getNode(), out);
        }
    }

    private List<LockMigrationInfo> decodeLocksInfo(DataReader in) throws IOException {
        int size = in.readInt();

        if (size == 0) {
            return Collections.emptyList();
        } else {
            List<LockMigrationInfo> locks = new ArrayList<>(size);

            for (int i = 0; i < size; i++) {
                String name = in.readUTF();
                long lockId = in.readLong();
                long threadId = in.readLong();

                ClusterNodeId node = CodecUtils.readNodeId(in);

                locks.add(new LockMigrationInfo(name, lockId, node, threadId));
            }

            return locks;
        }
    }
}
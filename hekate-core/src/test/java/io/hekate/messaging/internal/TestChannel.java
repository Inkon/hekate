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

package io.hekate.messaging.internal;

import io.hekate.HekateTestBase;
import io.hekate.cluster.ClusterNodeId;
import io.hekate.cluster.ClusterService;
import io.hekate.cluster.event.ClusterEventType;
import io.hekate.core.HekateFutureException;
import io.hekate.core.HekateTestInstance;
import io.hekate.messaging.Message;
import io.hekate.messaging.MessageReceiver;
import io.hekate.messaging.MessagingChannel;
import io.hekate.messaging.MessagingChannelId;
import io.hekate.messaging.unicast.LoadBalancer;
import io.hekate.messaging.unicast.RequestCallback;
import io.hekate.messaging.unicast.RequestFuture;
import io.hekate.messaging.unicast.SendCallback;
import io.hekate.messaging.unicast.SendFuture;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestChannel {
    private final MessageReceiver<String> receiver;

    private final List<String> received = Collections.synchronizedList(new ArrayList<>());

    private HekateTestInstance instance;

    private volatile DefaultMessagingChannel<String> channel;

    private volatile ClusterNodeId nodeId;

    private volatile Throwable receiverError;

    public TestChannel(MessageReceiver<String> receiverDelegate) {
        receiver = msg -> {
            received.add(msg.get());

            if (receiverDelegate != null) {
                try {
                    receiverDelegate.receive(msg);
                } catch (RuntimeException | Error e) {
                    receiverError = e;

                    throw e;
                }
            }
        };
    }

    public void initialize(HekateTestInstance instance) {
        this.instance = instance;

        instance.get(ClusterService.class).addListener(event -> {
            if (event.getType() == ClusterEventType.JOIN) {
                nodeId = instance.getNode().getId();

                channel = instance.get(DefaultMessagingService.class).channel(MessagingServiceTestBase.TEST_CHANNEL_NAME);
            }
        });
    }

    public MessagingChannel<String> get() {
        return channel;
    }

    public MessagingChannel<String> withLoadBalancer(LoadBalancer<String> balancer) {
        return channel.withLoadBalancer(balancer);
    }

    public ClusterNodeId getNodeId() {
        return nodeId;
    }

    public MessageReceiver<String> getReceiver() {
        return receiver;
    }

    public HekateTestInstance getInstance() {
        return instance;
    }

    public MessagingGateway<String> getImpl() {
        return channel.getGateway();
    }

    public TestChannel join() throws HekateFutureException, InterruptedException {
        instance.join();

        return this;
    }

    public TestChannel leave() throws InterruptedException, HekateFutureException {
        instance.leave();

        return this;
    }

    public MessagingChannelId getId() {
        return channel.getId();
    }

    public int getSocketPoolSize() {
        return channel.getSockets();
    }

    public int getSocketThreadPoolSize() {
        return channel.getNioThreads();
    }

    public int getWorkerThreads() {
        return channel.getWorkerThreads();
    }

    public SendFuture send(ClusterNodeId nodeId, String msg) {
        return channel.forNode(nodeId).send(msg);
    }

    public void send(ClusterNodeId nodeId, String msg, SendCallback callback) {
        channel.forNode(nodeId).send(msg, callback);
    }

    public RequestFuture<String> request(ClusterNodeId nodeId, String msg) {
        return channel.forNode(nodeId).request(msg);
    }

    public void request(ClusterNodeId nodeId, String msg, RequestCallback<String> callback) {
        channel.forNode(nodeId).request(msg, callback);
    }

    public Message<String> requestWithSyncCallback(ClusterNodeId nodeId, String msg) throws Exception {
        RequestCallbackMock callback = new RequestCallbackMock(msg);

        channel.forNode(nodeId).request(msg, callback);

        try {
            return callback.get();
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new Exception(e);
        }
    }

    public void sendWithSyncCallback(ClusterNodeId nodeId, String msg) throws Exception {
        SendCallbackMock callback = new SendCallbackMock();

        send(nodeId, msg, callback);

        callback.get();
    }

    public void assertReceived(String expected) {
        assertTrue(received.contains(expected));
    }

    public void awaitForMessage(String expected) throws Exception {
        awaitForMessages(Collections.singletonList(expected));
    }

    public void awaitForMessages(List<String> expected) throws Exception {
        awaitForMessages(received, expected);
    }

    public void awaitForMessages(List<String> received, List<String> expected) throws Exception {
        HekateTestBase.busyWait("messages - " + expected, () ->
            received.containsAll(expected)
        );
    }

    public List<String> getReceived() {
        return new ArrayList<>(received);
    }

    public void clearReceived() {
        received.clear();

        receiverError = null;
    }

    public void awaitForTopology(List<TestChannel> channels) {
        instance.awaitForTopology(channel.getCluster(), channels.stream().map(c -> c.getInstance().getNode()).collect(toList()));
    }

    public void checkReceiverError() {
        assertNull(receiverError);
    }
}
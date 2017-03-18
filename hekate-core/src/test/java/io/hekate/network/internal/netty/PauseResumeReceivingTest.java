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

package io.hekate.network.internal.netty;

import io.hekate.HekateTestContext;
import io.hekate.network.NetworkClient;
import io.hekate.network.NetworkEndpoint;
import io.hekate.network.NetworkMessage;
import io.hekate.network.internal.NetworkClientCallbackMock;
import io.hekate.network.internal.NetworkSendCallbackMock;
import io.hekate.network.internal.NetworkServer;
import io.hekate.network.internal.NetworkServerCallbackMock;
import io.hekate.network.internal.NetworkServerHandlerMock;
import io.hekate.network.internal.NetworkTestBase;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PauseResumeReceivingTest extends NetworkTestBase {
    public PauseResumeReceivingTest(HekateTestContext ctx) {
        super(ctx);
    }

    @Test
    public void testPauseServerTest() throws Exception {
        NetworkServerHandlerMock<String> receiver = new NetworkServerHandlerMock<>();

        NetworkServer server = createServer(receiver);

        NetworkServerCallbackMock listener = new NetworkServerCallbackMock();

        server.start(newServerAddress(), listener).get();

        NetworkClient<String> client = createClient();

        repeat(3, i -> {
            client.connect(server.getAddress(), new NetworkClientCallbackMock<>()).get(3, TimeUnit.SECONDS);

            // Await for server endpoint to be initialized.
            busyWait("server endpoint", () -> !server.getConnected(client.getProtocol()).isEmpty());

            NetworkEndpoint<?> remote = server.getConnected(client.getProtocol()).get(0);

            repeat(3, j -> {
                String msg = "request_" + i;

                // Pause receiving.
                pause(remote);

                // Send a message.
                NetworkSendCallbackMock<String> sendCallback = new NetworkSendCallbackMock<>();

                client.send(msg, sendCallback);

                // Make sure that message was flushed to the network buffer.
                sendCallback.awaitForSent(msg);

                // Await for some time before checking that message wasn't received.
                sleep(300);

                // Check that message was not received.
                receiver.assertNotReceived(client, msg);

                // Resume receiving.
                resume(remote);

                // Check that message was received.
                receiver.awaitForMessages(client, msg);

                receiver.reset();
            });

            client.disconnect().get(3, TimeUnit.SECONDS);
            remote.disconnect().get(3, TimeUnit.SECONDS);

            // Check that pause/resume after disconnect doesn't cause errors.
            doPause(remote);
            doResume(remote);
        });
    }

    @Test
    public void testClientDisconnectWhileServerPausedTest() throws Exception {
        NetworkServer server = createServer();

        server.start(newServerAddress(), new NetworkServerCallbackMock()).get();

        NetworkClient<String> client = createClient();

        repeat(3, i -> {
            client.connect(server.getAddress(), new NetworkClientCallbackMock<>()).get(3, TimeUnit.SECONDS);

            // Await for server endpoint to be initialized.
            busyWait("server endpoint connect", () -> !server.getConnected(client.getProtocol()).isEmpty());

            NetworkEndpoint<?> remote = server.getConnected(client.getProtocol()).get(0);

            pause(remote);

            client.disconnect().get(3, TimeUnit.SECONDS);

            // Await for server endpoint to be disconnected.
            busyWait("server endpoint disconnect", () -> server.getConnected(client.getProtocol()).isEmpty());
        });
    }

    @Test
    public void testClientDisconnectWhileBothPausedTest() throws Exception {
        NetworkServer server = createServer();

        server.start(newServerAddress(), new NetworkServerCallbackMock()).get();

        NetworkClient<String> client = createClient();

        repeat(3, i -> {
            client.connect(server.getAddress(), new NetworkClientCallbackMock<>()).get(3, TimeUnit.SECONDS);

            // Await for server endpoint to be initialized.
            busyWait("server endpoint connect", () -> !server.getConnected(client.getProtocol()).isEmpty());

            NetworkEndpoint<?> remote = server.getConnected(client.getProtocol()).get(0);

            pause(client);
            pause(remote);

            client.disconnect().get(3, TimeUnit.SECONDS);

            // Await for server endpoint to be disconnected.
            busyWait("server endpoint disconnect", () -> server.getConnected(client.getProtocol()).isEmpty());
        });
    }

    @Test
    public void testServerDisconnectWhilePausedTest() throws Exception {
        NetworkServer server = createServer();

        server.start(newServerAddress(), new NetworkServerCallbackMock()).get();

        NetworkClient<String> client = createClient();

        repeat(3, i -> {
            client.connect(server.getAddress(), new NetworkClientCallbackMock<>()).get(3, TimeUnit.SECONDS);

            // Await for server endpoint to be initialized.
            busyWait("server endpoint connect", () -> !server.getConnected(client.getProtocol()).isEmpty());

            NetworkEndpoint<?> remote = server.getConnected(client.getProtocol()).get(0);

            pause(remote);

            remote.disconnect().get(3, TimeUnit.SECONDS);

            // Await for client to be disconnected.
            busyWait("client disconnect", () -> client.getState() == NetworkClient.State.DISCONNECTED);
        });
    }

    @Test
    public void testServerDisconnectWhileClientPausedTest() throws Exception {
        NetworkServer server = createServer();

        server.start(newServerAddress(), new NetworkServerCallbackMock()).get();

        NetworkClient<String> client = createClient();

        repeat(3, i -> {
            client.connect(server.getAddress(), new NetworkClientCallbackMock<>()).get(3, TimeUnit.SECONDS);

            // Await for server endpoint to be initialized.
            busyWait("server endpoint connect", () -> !server.getConnected(client.getProtocol()).isEmpty());

            NetworkEndpoint<?> remote = server.getConnected(client.getProtocol()).get(0);

            pause(client);

            remote.disconnect().get(3, TimeUnit.SECONDS);

            // Await for client to be disconnected.
            busyWait("client disconnect", () -> client.getState() == NetworkClient.State.DISCONNECTED);
        });
    }

    @Test
    public void testServerDisconnectWhileBothPausedTest() throws Exception {
        NetworkServer server = createServer();

        server.start(newServerAddress(), new NetworkServerCallbackMock()).get();

        NetworkClient<String> client = createClient();

        repeat(3, i -> {
            client.connect(server.getAddress(), new NetworkClientCallbackMock<>()).get(3, TimeUnit.SECONDS);

            // Await for server endpoint to be initialized.
            busyWait("server endpoint connect", () -> !server.getConnected(client.getProtocol()).isEmpty());

            NetworkEndpoint<?> remote = server.getConnected(client.getProtocol()).get(0);

            pause(remote);
            pause(client);

            remote.disconnect().get(3, TimeUnit.SECONDS);

            // Await for client to be disconnected.
            busyWait("client disconnect", () -> client.getState() == NetworkClient.State.DISCONNECTED);
        });
    }

    @Test
    public void testClientDisconnectWhilePausedTest() throws Exception {
        NetworkServer server = createServer();

        server.start(newServerAddress(), new NetworkServerCallbackMock()).get();

        NetworkClient<String> client = createClient();

        repeat(3, i -> {
            client.connect(server.getAddress(), new NetworkClientCallbackMock<>()).get(3, TimeUnit.SECONDS);

            // Await for server endpoint to be initialized.
            busyWait("server endpoint connect", () -> !server.getConnected(client.getProtocol()).isEmpty());

            NetworkEndpoint<?> remote = server.getConnected(client.getProtocol()).get(0);

            pause(client);

            client.disconnect().get(3, TimeUnit.SECONDS);

            // Await for server endpoint to be disconnected.
            busyWait("server endpoint disconnect", () -> server.getConnected(client.getProtocol()).isEmpty());
        });
    }

    @Test
    public void testPauseClientTest() throws Exception {
        NetworkServer server = createServer();

        NetworkServerCallbackMock listener = new NetworkServerCallbackMock();

        server.start(newServerAddress(), listener).get();

        NetworkClient<String> client = createClient();

        repeat(3, i -> {
            NetworkClientCallbackMock<String> clientCallback = new NetworkClientCallbackMock<>();

            client.connect(server.getAddress(), clientCallback).get(3, TimeUnit.SECONDS);

            // Await for server endpoint to be initialized.
            busyWait("server endpoint", () -> !server.getConnected(client.getProtocol()).isEmpty());

            @SuppressWarnings("unchecked")
            NetworkEndpoint<String> remote = (NetworkEndpoint<String>)server.getConnected(client.getProtocol()).get(0);

            repeat(3, j -> {
                String msg = "request_" + i;

                // Pause receiving.
                pause(client);

                // Send a message.
                NetworkSendCallbackMock<String> sendCallback = new NetworkSendCallbackMock<>();

                remote.send(msg, sendCallback);

                // Make sure that message was flushed to the network buffer.
                sendCallback.awaitForSent(msg);

                // Await for some time before checking that message wasn't received.
                sleep(300);

                // Check that message was not received.
                assertTrue(clientCallback.getMessages().isEmpty());

                // Resume receiving.
                resume(client);

                // Check that message was received.
                clientCallback.awaitForMessages(msg);

                clientCallback.reset();
            });

            client.disconnect().get(3, TimeUnit.SECONDS);

            // Check that pause/resume after disconnect doesn't cause errors.
            doPause(client);
            doResume(client);
        });
    }

    @Test
    public void testServerNoHeartbeatTimeoutOnPause() throws Exception {
        int hbInterval = 100;
        int hbLossThreshold = 3;

        NetworkServer server = createAndConfigureServer(f -> {
            f.setHeartbeatInterval(hbInterval);
            f.setHeartbeatLossThreshold(hbLossThreshold);
        });

        server.start(newServerAddress()).get();

        NetworkClient<String> client = createClient();

        CountDownLatch blockLatch = new CountDownLatch(1);

        NetworkClientCallbackMock<String> clientCallback = new NetworkClientCallbackMock<String>() {
            @Override
            public void onMessage(NetworkMessage<String> msg, NetworkClient<String> from) throws IOException {
                super.onMessage(msg, client);

                if (msg.decode().equals("block")) {
                    try {
                        blockLatch.await();
                    } catch (InterruptedException t) {
                        // No-op.
                    }
                }
            }
        };

        client.connect(server.getAddress(), clientCallback).get();

        busyWait("server endpoint", () -> !server.getConnected(client.getProtocol()).isEmpty());

        @SuppressWarnings("unchecked")
        NetworkEndpoint<String> remote = (NetworkEndpoint<String>)server.getConnected(client.getProtocol()).get(0);

        repeat(3, i -> {
            clientCallback.assertDisconnects(0);
            clientCallback.assertNoErrors();

            pause(remote);

            sleep(hbInterval * hbLossThreshold * 3);

            assertSame(NetworkClient.State.CONNECTED, client.getState());
            assertEquals(0, clientCallback.getMessages().size());
            clientCallback.assertDisconnects(0);
            clientCallback.assertNoErrors();

            resume(remote);

            clientCallback.assertDisconnects(0);
            clientCallback.assertNoErrors();

            clientCallback.reset();
        });

        remote.send("block");

        sleep(hbInterval * hbLossThreshold * 3);

        blockLatch.countDown();

        assertTrue(server.getConnected(client.getProtocol()).isEmpty());
    }

    @Test
    public void testClientNoHeartbeatTimeoutOnPause() throws Exception {
        int hbInterval = 100;
        int hbLossThreshold = 3;

        CountDownLatch blockLatch = new CountDownLatch(1);

        NetworkServer server = createAndConfigureServerHandler(
            h ->
                h.setHandler((msg, from) -> {
                    if (msg.decode().equals("block")) {
                        try {
                            blockLatch.await();
                        } catch (InterruptedException e) {
                            // No-op.
                        }
                    }
                }),
            s -> {
                s.setHeartbeatInterval(hbInterval);
                s.setHeartbeatLossThreshold(hbLossThreshold);
            }
        );

        server.start(newServerAddress()).get();

        NetworkClient<String> client = createClient();

        NetworkClientCallbackMock<String> clientCallback = new NetworkClientCallbackMock<>();

        client.connect(server.getAddress(), clientCallback).get();

        repeat(3, i -> {
            clientCallback.assertDisconnects(0);
            clientCallback.assertNoErrors();

            busyWait("server endpoint", () -> !server.getConnected(client.getProtocol()).isEmpty());

            @SuppressWarnings("unchecked")
            NetworkEndpoint<String> remote = (NetworkEndpoint<String>)server.getConnected(client.getProtocol()).get(0);

            pause(client);

            sleep(hbInterval * hbLossThreshold * 3);

            assertSame(NetworkClient.State.CONNECTED, client.getState());
            assertEquals(0, clientCallback.getMessages().size());
            clientCallback.assertDisconnects(0);
            clientCallback.assertNoErrors();

            resume(client);

            assertSame(NetworkClient.State.CONNECTED, client.getState());
            assertEquals(0, clientCallback.getMessages().size());
            clientCallback.assertDisconnects(0);
            clientCallback.assertNoErrors();

            remote.send("ping_" + i);

            clientCallback.awaitForMessages("ping_" + i);
            clientCallback.assertDisconnects(0);
            clientCallback.assertNoErrors();

            clientCallback.reset();
        });

        // Check that timeout is still possible.
        client.send("block");

        // Await for heartbeat timeout.
        sleep(hbInterval * hbLossThreshold * 3);

        blockLatch.countDown();

        assertSame(NetworkClient.State.DISCONNECTED, client.getState());
    }

    private void pause(NetworkEndpoint<?> client) {
        assertTrue(client.isReceiving());

        doPause(client);

        assertFalse(client.isReceiving());
    }

    private void resume(NetworkEndpoint<?> client) {
        assertFalse(client.isReceiving());

        doResume(client);

        assertTrue(client.isReceiving());
    }

    private void doPause(NetworkEndpoint<?> client) {
        CountDownLatch paused = new CountDownLatch(1);

        client.pauseReceiving(subj -> paused.countDown());

        await(paused);
    }

    private void doResume(NetworkEndpoint<?> client) {
        CountDownLatch resumed = new CountDownLatch(1);

        client.resumeReceiving(subj -> resumed.countDown());

        await(resumed);
    }
}
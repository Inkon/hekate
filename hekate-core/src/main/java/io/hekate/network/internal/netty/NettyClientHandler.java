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

import io.hekate.network.NetworkClient.State;
import io.hekate.network.NetworkClientCallback;
import io.hekate.network.internal.netty.NetworkProtocol.HandshakeAccept;
import io.hekate.network.internal.netty.NetworkProtocol.HandshakeReject;
import io.hekate.network.internal.netty.NetworkProtocol.HandshakeRequest;
import io.hekate.network.internal.netty.NetworkProtocol.Heartbeat;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

import static io.hekate.network.NetworkClient.State.CONNECTED;
import static io.hekate.network.NetworkClient.State.CONNECTING;
import static io.hekate.network.NetworkClient.State.DISCONNECTED;

class NettyClientHandler<T> extends SimpleChannelInboundHandler {
    private static final String CONNECT_TIMEOUT_HANDLER_ID = "timeout_handler";

    private final Logger log;

    private final boolean debug;

    private final boolean trace;

    private final int epoch;

    private final String protocol;

    private final T login;

    private final long idleTimeout;

    private final Integer connectTimeout;

    private final InetSocketAddress address;

    private final NettyMetricsCallback metrics;

    private final NettyClient<T> client;

    private final NetworkClientCallback<T> callback;

    private final GenericFutureListener<Future<? super Void>> heartbeatFlushListener;

    private boolean heartbeatFlushed = true;

    private int ignoreReadTimeouts;

    private boolean handshakeDone;

    private State state = CONNECTING;

    public NettyClientHandler(int epoch, String protocol, T login, Integer connectTimeout, long idleTimeout, InetSocketAddress address,
        Logger log, NettyMetricsCallback metrics, NettyClient<T> client,
        NetworkClientCallback<T> callback) {
        this.log = log;
        this.epoch = epoch;
        this.protocol = protocol;
        this.login = login;
        this.idleTimeout = idleTimeout;
        this.connectTimeout = connectTimeout;
        this.address = address;
        this.metrics = metrics;
        this.client = client;
        this.callback = callback;

        this.debug = log.isDebugEnabled();
        this.trace = log.isTraceEnabled();

        heartbeatFlushListener = future -> heartbeatFlushed = true;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);

        if (connectTimeout != null && connectTimeout > 0) {
            if (debug) {
                log.debug("Registering connect timeout handler [connect-timeout={}]", connectTimeout);
            }

            IdleStateHandler idleStateHandler = new IdleStateHandler(connectTimeout, 0, 0, TimeUnit.MILLISECONDS);

            ctx.pipeline().addFirst(CONNECT_TIMEOUT_HANDLER_ID, idleStateHandler);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);

        HandshakeRequest request = new HandshakeRequest(protocol, login);

        if (debug) {
            log.debug("Connected ...sending handshake request [address={}, request={}]", address, request);
        }

        if (metrics != null) {
            metrics.onConnect();
        }

        ctx.writeAndFlush(request);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof AutoReadChangeEvent) {
            if (evt == AutoReadChangeEvent.PAUSE) {
                // Completely ignore read timeouts.
                ignoreReadTimeouts = -1;
            } else {
                // Ignore next timeout.
                ignoreReadTimeouts = 1;
            }
        } else if (evt instanceof IdleStateEvent) {
            if (state == CONNECTING || state == CONNECTED) {
                IdleStateEvent idle = (IdleStateEvent)evt;

                if (idle.state() == IdleState.WRITER_IDLE) {
                    if (heartbeatFlushed) {
                        // Make sure that we don't push multiple heartbeats to the network buffer simultaneously.
                        // Need to perform this check since remote peer can hang and stop reading
                        // while this channel will still be trying to put more and more heartbeats on its send buffer.
                        heartbeatFlushed = false;

                        ctx.writeAndFlush(Heartbeat.INSTANCE).addListener(heartbeatFlushListener);
                    }
                } else {
                    // Reader idle.
                    // Ignore if auto-reading was disabled since in such case we will not read any heartbeats.
                    if (ignoreReadTimeouts != -1 && ctx.channel().config().isAutoRead()) {
                        // Check if timeout should be ignored.
                        if (ignoreReadTimeouts > 0) {
                            // Decrement the counter of ignored timeouts.
                            ignoreReadTimeouts--;
                        } else {
                            if (state == CONNECTING) {
                                throw new ConnectTimeoutException("Timeout while connecting to " + address);
                            } else if (state == CONNECTED) {
                                throw new SocketTimeoutException("Timeout while reading data from " + address);
                            }
                        }
                    }
                }
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (metrics != null) {
            metrics.onDisconnect();
        }

        if (state == CONNECTING) {
            state = DISCONNECTED;

            ctx.fireExceptionCaught(new ConnectException("Got disconnected on handshake [address=" + address + ']'));
        } else {
            state = DISCONNECTED;

            super.channelInactive(ctx);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Ignore heartbeats.
        if (msg instanceof Heartbeat) {
            if (trace) {
                log.trace("Received heartbeat from server [from={}, message={}]", address, msg);
            }

            return;
        }

        if (handshakeDone) {
            NettyMessage netMsg = (NettyMessage)msg;

            try {
                netMsg.prepare(log);

                if (trace) {
                    log.trace("Message buffer prepared [from={}, buffer={}]", address, netMsg);
                }

                if (metrics != null) {
                    metrics.onMessageReceived();
                }

                callback.onMessage(netMsg.cast(), client);
            } finally {
                netMsg.release();
            }
        } else {
            if (debug) {
                log.debug("Received handshake response [from={}, message={}]", address, msg);
            }

            NetworkProtocol handshakeMsg = (NetworkProtocol)msg;

            ChannelPipeline pipeline = ctx.pipeline();

            if (handshakeMsg.getType() == NetworkProtocol.Type.HANDSHAKE_REJECT) {
                HandshakeReject reject = (HandshakeReject)handshakeMsg;

                String reason = reject.getReason();

                if (debug) {
                    log.debug("Server rejected connection [address={}, protocol={}, reason={}]", address, protocol, reason);
                }

                throw new ConnectException(reason + " [protocol=" + protocol + ']');
            } else {
                HandshakeAccept accept = (HandshakeAccept)handshakeMsg;

                handshakeDone = true;

                // Unregister connect timeout handler.
                if (ctx.pipeline().get(CONNECT_TIMEOUT_HANDLER_ID) != null) {
                    ctx.pipeline().remove(CONNECT_TIMEOUT_HANDLER_ID);
                }

                int interval = accept.getHbInterval();
                int threshold = accept.getHbLossThreshold();
                boolean disableHeartbeats = accept.isHbDisabled();

                // Register heartbeat handler.
                if (interval > 0 && threshold > 0) {
                    int readTimeout = interval * threshold;

                    if (disableHeartbeats) {
                        interval = 0;

                        if (debug) {
                            log.debug("Registering heartbeatless timeout handler [read-timeout={}]", readTimeout);
                        }
                    } else {
                        if (debug) {
                            log.debug("Registering heartbeats handler [interval={}, loss-threshold={}, read-timeout={}]", interval,
                                threshold, readTimeout);
                        }
                    }

                    pipeline.addFirst(new IdleStateHandler(readTimeout, interval, 0, TimeUnit.MILLISECONDS));
                }

                // Register idle connection handler.
                if (idleTimeout > 0) {
                    if (debug) {
                        log.debug("Registering idle connection handler [idle-timeout={}]", idleTimeout);
                    }

                    NettyClientIdleStateHandler idleStateHandler = new NettyClientIdleStateHandler(idleTimeout);

                    pipeline.addAfter(NettyClient.ENCODER_HANDLER_ID, NettyClientIdleStateHandler.ID, idleStateHandler);
                }

                // Update state and notify on handshake completion.
                state = CONNECTED;

                ctx.fireUserEventTriggered(new HandshakeDoneEvent(epoch));
            }
        }
    }
}
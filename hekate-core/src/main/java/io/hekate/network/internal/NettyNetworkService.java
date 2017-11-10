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

package io.hekate.network.internal;

import io.hekate.codec.Codec;
import io.hekate.codec.CodecFactory;
import io.hekate.codec.CodecService;
import io.hekate.codec.DataReader;
import io.hekate.codec.DataWriter;
import io.hekate.codec.SingletonCodecFactory;
import io.hekate.core.HekateException;
import io.hekate.core.internal.util.ArgAssert;
import io.hekate.core.internal.util.ConfigCheck;
import io.hekate.core.internal.util.HekateThreadFactory;
import io.hekate.core.internal.util.StreamUtils;
import io.hekate.core.resource.ResourceService;
import io.hekate.core.service.ConfigurableService;
import io.hekate.core.service.ConfigurationContext;
import io.hekate.core.service.DependencyContext;
import io.hekate.core.service.DependentService;
import io.hekate.core.service.InitializationContext;
import io.hekate.core.service.InitializingService;
import io.hekate.core.service.NetworkBindCallback;
import io.hekate.core.service.NetworkServiceManager;
import io.hekate.core.service.TerminatingService;
import io.hekate.metrics.local.CounterConfig;
import io.hekate.metrics.local.CounterMetric;
import io.hekate.metrics.local.LocalMetricsService;
import io.hekate.network.NetworkClient;
import io.hekate.network.NetworkClientCallback;
import io.hekate.network.NetworkConfigProvider;
import io.hekate.network.NetworkConnector;
import io.hekate.network.NetworkConnectorConfig;
import io.hekate.network.NetworkMessage;
import io.hekate.network.NetworkServer;
import io.hekate.network.NetworkServerCallback;
import io.hekate.network.NetworkServerFailure;
import io.hekate.network.NetworkServerFuture;
import io.hekate.network.NetworkServerHandler;
import io.hekate.network.NetworkService;
import io.hekate.network.NetworkServiceFactory;
import io.hekate.network.NetworkSslConfig;
import io.hekate.network.NetworkTransportType;
import io.hekate.network.PingCallback;
import io.hekate.network.PingResult;
import io.hekate.network.address.AddressSelector;
import io.hekate.network.netty.NettyClientFactory;
import io.hekate.network.netty.NettyMetricsFactory;
import io.hekate.network.netty.NettyMetricsSink;
import io.hekate.network.netty.NettyServerFactory;
import io.hekate.network.netty.NettyServerHandlerConfig;
import io.hekate.network.netty.NettyUtils;
import io.hekate.util.StateGuard;
import io.hekate.util.async.AsyncUtils;
import io.hekate.util.format.ToString;
import io.hekate.util.format.ToStringIgnore;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toList;

public class NettyNetworkService implements NetworkService, NetworkServiceManager, DependentService, ConfigurableService,
    InitializingService, TerminatingService {
    private static class ConnectorRegistration<T> {
        private final String protocol;

        private final EventLoopGroup eventLoop;

        private final NettyServerHandlerConfig<T> serverHandler;

        private final NetworkConnector<T> connector;

        public ConnectorRegistration(String protocol, EventLoopGroup eventLoop, NetworkConnector<T> connector,
            NettyServerHandlerConfig<T> serverHandler) {
            assert protocol != null : "Protocol is null.";
            assert connector != null : "Connector is null.";

            this.protocol = protocol;
            this.eventLoop = eventLoop;
            this.serverHandler = serverHandler;
            this.connector = connector;
        }

        public String protocol() {
            return protocol;
        }

        public boolean hasEventLoop() {
            return eventLoop != null;
        }

        public EventLoopGroup eventLoop() {
            return eventLoop;
        }

        public NettyServerHandlerConfig<T> serverHandler() {
            return serverHandler;
        }

        public NetworkConnector<T> connector() {
            return connector;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(NettyNetworkService.class);

    private static final boolean DEBUG = log.isDebugEnabled();

    private static final String METRIC_CONN_ACTIVE = "conn.active";

    private static final String METRIC_MSG_ERR = "msg.err";

    private static final String METRIC_MSG_QUEUE = "msg.queue";

    private static final String METRIC_MSG_OUT = "msg.out";

    private static final String METRIC_MSG_IN = "msg.in";

    private static final String METRIC_BYTES_IN = "bytes.in";

    private static final String METRIC_BYTES_OUT = "bytes.out";

    private static final int NIO_ACCEPTOR_THREADS = 1;

    private static final String PING_PROTOCOL = "hekate.ping";

    private final AddressSelector addressSelector;

    private final int initPort;

    private final int portRange;

    private final int connectTimeout;

    private final long acceptorFailoverInterval;

    private final int heartbeatInterval;

    private final int heartbeatLossThreshold;

    private final int nioThreadPoolSize;

    private final NetworkTransportType transport;

    private final boolean tcpNoDelay;

    private final Integer soReceiveBufferSize;

    private final Integer soSendBufferSize;

    private final Boolean soReuseAddress;

    private final Integer soBacklog;

    private final NetworkSslConfig sslConfig;

    @ToStringIgnore
    private final StateGuard guard = new StateGuard(NetworkService.class);

    @ToStringIgnore
    private final List<NetworkConnectorConfig<?>> connectorConfigs = new ArrayList<>();

    @ToStringIgnore
    private final Map<String, ConnectorRegistration<?>> connectors = new HashMap<>();

    @ToStringIgnore
    private SslContext clientSsl;

    @ToStringIgnore
    private SslContext serverSsl;

    @ToStringIgnore
    private CodecService codec;

    @ToStringIgnore
    private ResourceService resources;

    @ToStringIgnore
    private NettyMetricsFactory serverMetrics;

    @ToStringIgnore
    private NettyMetricsFactory clientMetrics;

    @ToStringIgnore
    private EventLoopGroup acceptorLoop;

    @ToStringIgnore
    private EventLoopGroup coreLoop;

    @ToStringIgnore
    private NetworkServer server;

    public NettyNetworkService(NetworkServiceFactory factory) {
        assert factory != null : "Factory is null.";

        ConfigCheck check = ConfigCheck.get(NetworkServiceFactory.class);

        check.range(factory.getPort(), 0, 65535, "port");
        check.notNull(factory.getTransport(), "transport");
        check.notNull(factory.getHostSelector(), "address selector");
        check.positive(factory.getNioThreads(), "NIO thread pool size");
        check.positive(factory.getHeartbeatInterval(), "heartbeat interval");
        check.positive(factory.getHeartbeatLossThreshold(), "heartbeat loss threshold");
        check.positive(factory.getConnectTimeout(), "connect timeout");

        initPort = factory.getPort();
        portRange = factory.getPortRange();
        addressSelector = factory.getHostSelector();
        connectTimeout = factory.getConnectTimeout();
        acceptorFailoverInterval = factory.getAcceptRetryInterval();
        heartbeatInterval = factory.getHeartbeatInterval();
        heartbeatLossThreshold = factory.getHeartbeatLossThreshold();
        nioThreadPoolSize = factory.getNioThreads();
        tcpNoDelay = factory.isTcpNoDelay();
        soReceiveBufferSize = factory.getTcpReceiveBufferSize();
        soSendBufferSize = factory.getTcpSendBufferSize();
        soReuseAddress = factory.getTcpReuseAddress();
        soBacklog = factory.getTcpBacklog();
        sslConfig = factory.getSsl();

        if (factory.getTransport() == NetworkTransportType.AUTO) {
            transport = Epoll.isAvailable() ? NetworkTransportType.EPOLL : NetworkTransportType.NIO;
        } else {
            transport = factory.getTransport();
        }

        StreamUtils.nullSafe(factory.getConnectors()).forEach(connectorConfigs::add);

        StreamUtils.nullSafe(factory.getConfigProviders()).forEach(provider ->
            StreamUtils.nullSafe(provider.configureNetwork()).forEach(connectorConfigs::add)
        );

        // Register ping protocol.
        connectorConfigs.add(pingConnector());
    }

    @Override
    public void resolve(DependencyContext ctx) {
        codec = ctx.require(CodecService.class);
        resources = ctx.require(ResourceService.class);

        LocalMetricsService metricsService = ctx.optional(LocalMetricsService.class);

        if (metricsService != null) {
            serverMetrics = createMetricsAdaptor(true, metricsService);
            clientMetrics = createMetricsAdaptor(false, metricsService);
        }
    }

    @Override
    public void configure(ConfigurationContext ctx) {
        Collection<NetworkConfigProvider> providers = ctx.findComponents(NetworkConfigProvider.class);

        providers.forEach(provider ->
            StreamUtils.nullSafe(provider.configureNetwork()).forEach(connectorConfigs::add)
        );

        if (sslConfig != null) {
            clientSsl = NettySslUtils.clientContext(sslConfig, resources);
            serverSsl = NettySslUtils.serverContext(sslConfig, resources);
        }
    }

    @Override
    public NetworkServerFuture bind(NetworkBindCallback callback) throws HekateException {
        ArgAssert.notNull(callback, "Callback");

        if (DEBUG) {
            log.debug("Obtaining preferred host address...");
        }

        InetAddress publicIp = ipOnly(addressSelector.select());

        if (publicIp == null) {
            throw new HekateException("Failed to select public host address [selector=" + addressSelector + ']');
        }

        if (log.isInfoEnabled()) {
            log.info("Selected public address [address={}]", publicIp);
        }

        log.info("Binding network acceptor [port={}]", initPort);

        guard.lockWrite();

        try {
            guard.becomeInitialized();

            acceptorLoop = newEventLoop(NIO_ACCEPTOR_THREADS, "NioAcceptor");
            coreLoop = newEventLoop(nioThreadPoolSize, "NioWorker-core");

            NettyServerFactory serverFactory = new NettyServerFactory();

            serverFactory.setAutoAccept(false);
            serverFactory.setHeartbeatInterval(heartbeatInterval);
            serverFactory.setHeartbeatLossThreshold(heartbeatLossThreshold);
            serverFactory.setSoBacklog(soBacklog);
            serverFactory.setSoReceiveBufferSize(soReceiveBufferSize);
            serverFactory.setSoSendBufferSize(soSendBufferSize);
            serverFactory.setSoReuseAddress(soReuseAddress);
            serverFactory.setTcpNoDelay(tcpNoDelay);
            serverFactory.setAcceptorEventLoop(acceptorLoop);
            serverFactory.setWorkerEventLoop(coreLoop);
            serverFactory.setSsl(serverSsl);
            serverFactory.setMetrics(serverMetrics);

            server = serverFactory.createServer();

            connectorConfigs.forEach(protocolCfg -> {
                ConnectorRegistration<?> reg = createRegistration(protocolCfg);

                connectors.put(reg.protocol(), reg);

                if (reg.serverHandler() != null) {
                    server.addHandler(reg.serverHandler());
                }
            });

            // Using wildcard address.
            InetSocketAddress wildcard = new InetSocketAddress(initPort);

            return server.start(wildcard, new NetworkServerCallback() {
                @Override
                public void onStart(NetworkServer server) {
                    InetSocketAddress realAddress = server.address();

                    if (log.isInfoEnabled()) {
                        log.info("Done binding [bind-address={}]", realAddress);
                    }

                    // Convert to public address (port can be assigned by the auto-increment or by OS if it was configured as 0).
                    InetSocketAddress address = new InetSocketAddress(publicIp, realAddress.getPort());

                    callback.onBind(address);
                }

                @Override
                public NetworkServerFailure.Resolution onFailure(NetworkServer server, NetworkServerFailure err) {
                    Throwable cause = err.cause();

                    if (cause instanceof IOException) {
                        int initPort = wildcard.getPort();

                        if (initPort > 0 && portRange > 0 && server.state() == NetworkServer.State.STARTING) {
                            int prevPort = err.lastTriedAddress().getPort();

                            int newPort = prevPort + 1;

                            if (newPort < initPort + portRange) {
                                InetSocketAddress newAddress = new InetSocketAddress(err.lastTriedAddress().getAddress(), newPort);

                                if (log.isInfoEnabled()) {
                                    log.info("Couldn't bind on port {} ...will try next port [new-address={}]", prevPort, newAddress);
                                }

                                return err.retry().withRetryAddress(newAddress);
                            }
                        } else if (server.state() == NetworkServer.State.STARTED && acceptorFailoverInterval > 0) {
                            if (log.isErrorEnabled()) {
                                log.error("Network server encountered an error ...will try to restart after {} ms [attempt={}, address={}]",
                                    acceptorFailoverInterval, err.attempt(), err.lastTriedAddress(), cause);
                            }

                            return err.retry().withRetryDelay(acceptorFailoverInterval);
                        }
                    }

                    return callback.onFailure(err);
                }
            });
        } finally {
            guard.unlockWrite();
        }
    }

    @Override
    public void initialize(InitializationContext ctx) {
        // No-op.
    }

    @Override
    public void preInitialize(InitializationContext ctx) {
        // No-op.
    }

    @Override
    public void postInitialize(InitializationContext ctx) {
        guard.lockRead();

        try {
            if (guard.isInitialized()) {
                log.info("Started accepting network connections.");

                server.startAccepting();
            }
        } finally {
            guard.unlockRead();
        }
    }

    @Override
    public void preTerminate() {
        // No-op.
    }

    @Override
    public void terminate() {
        // No-op.
    }

    @Override
    public void postTerminate() {
        NetworkServer localServer = null;
        EventLoopGroup localAcceptorLoop = null;
        EventLoopGroup localCoreLoop = null;
        List<EventLoopGroup> localLoops = null;

        guard.lockWrite();

        try {
            if (guard.becomeTerminated()) {
                localServer = this.server;
                localAcceptorLoop = this.acceptorLoop;
                localCoreLoop = this.coreLoop;

                localLoops = connectors.values().stream()
                    .filter(ConnectorRegistration::hasEventLoop)
                    .map(ConnectorRegistration::eventLoop)
                    .collect(toList());

                connectors.clear();

                acceptorLoop = null;
                coreLoop = null;
                server = null;
            }
        } finally {
            guard.unlockWrite();
        }

        if (localServer != null) {
            try {
                AsyncUtils.getUninterruptedly(localServer.stop());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();

                if (cause instanceof IOException) {
                    if (DEBUG) {
                        log.debug("Failed to stop network server due to an I/O error [cause={}]", e.toString());
                    }
                } else {
                    log.warn("Failed to stop network server.", cause);
                }
            }
        }

        shutdown(localAcceptorLoop);
        shutdown(localCoreLoop);

        if (localLoops != null) {
            localLoops.forEach(this::shutdown);
        }
    }

    @Override
    public <T> NetworkConnector<T> connector(String protocol) throws IllegalArgumentException {
        ArgAssert.notNull(protocol, "Protocol");

        guard.tryLockReadWithStateCheck();

        try {
            ConnectorRegistration<?> module = connectors.get(protocol);

            ArgAssert.check(module != null, "Unknown protocol [name=" + protocol + ']');

            @SuppressWarnings("unchecked")
            NetworkConnector<T> connector = (NetworkConnector<T>)module.connector();

            return connector;
        } finally {
            guard.unlockRead();
        }
    }

    @Override
    public boolean hasConnector(String protocol) {
        guard.tryLockReadWithStateCheck();

        try {
            return connectors.containsKey(protocol);
        } finally {
            guard.unlockRead();
        }
    }

    @Override
    public void ping(InetSocketAddress address, PingCallback callback) {
        connector(PING_PROTOCOL).newClient().connect(address, new NetworkClientCallback<Object>() {
            @Override
            public void onConnect(NetworkClient<Object> client) {
                client.disconnect();

                callback.onResult(address, PingResult.SUCCESS);
            }

            @Override
            public void onDisconnect(NetworkClient<Object> client, Optional<Throwable> cause) {
                cause.ifPresent(err -> {
                    if (err instanceof ConnectTimeoutException || err instanceof SocketTimeoutException) {
                        callback.onResult(address, PingResult.TIMEOUT);
                    } else {
                        callback.onResult(address, PingResult.FAILURE);
                    }
                });
            }

            @Override
            public void onMessage(NetworkMessage<Object> message, NetworkClient<Object> client) {
                throw new UnsupportedOperationException(PING_PROTOCOL + " doesn't support any messages.");
            }
        });
    }

    protected <T> NettyClientFactory<T> createClientFactory() {
        return new NettyClientFactory<>();
    }

    // Package level for testing purposes.
    void start() {
        // Safe to use null since we don't use this parameter in any of those method.
        preInitialize(null);
        initialize(null);
        postInitialize(null);
    }

    // Package level for testing purposes.
    void stop() {
        preTerminate();
        terminate();
        postTerminate();
    }

    private InetAddress ipOnly(InetAddress address) throws HekateException {
        if (address == null) {
            return null;
        }

        try {
            return InetAddress.getByName(address.getHostAddress());
        } catch (UnknownHostException e) {
            throw new HekateException("Failed to resolve host address [address=" + address + ']', e);
        }
    }

    private <T> ConnectorRegistration<T> createRegistration(NetworkConnectorConfig<T> cfg) {
        ConfigCheck check = ConfigCheck.get(NetworkConnectorConfig.class);

        String protocol = cfg.getProtocol();

        check.notEmpty(protocol, "protocol");
        check.unique(protocol, connectors.keySet(), "protocol");

        boolean useCoreLoop;
        EventLoopGroup eventLoop;

        if (cfg.getNioThreads() > 0) {
            useCoreLoop = false;

            eventLoop = newEventLoop(cfg.getNioThreads(), "NioWorker-" + protocol);
        } else {
            useCoreLoop = true;
            eventLoop = coreLoop;
        }

        CodecFactory<T> codecFactory;

        if (cfg.getMessageCodec() == null) {
            codecFactory = defaultCodecFactory();
        } else {
            codecFactory = cfg.getMessageCodec();
        }

        NettyClientFactory<T> factory = createClientFactory();

        factory.setProtocol(protocol);
        factory.setCodecFactory(codecFactory);
        factory.setIdleTimeout(cfg.getIdleTimeout());
        factory.setLoggerCategory(cfg.getLogCategory());

        factory.setConnectTimeout(connectTimeout);
        factory.setSoReceiveBufferSize(soReceiveBufferSize);
        factory.setSoSendBufferSize(soSendBufferSize);
        factory.setSoReuseAddress(soReuseAddress);
        factory.setTcpNoDelay(tcpNoDelay);
        factory.setSsl(clientSsl);

        factory.setEventLoop(eventLoop);

        if (serverMetrics != null) {
            NettyMetricsSink metricsSink = clientMetrics.createSink(protocol);

            factory.setMetrics(metricsSink);
        }

        NettyServerHandlerConfig<T> handlerCfg = null;

        if (cfg.getServerHandler() != null) {
            NetworkServerHandler<T> handler = cfg.getServerHandler();

            handlerCfg = new NettyServerHandlerConfig<>();

            handlerCfg.setProtocol(protocol);
            handlerCfg.setCodecFactory(codecFactory);
            handlerCfg.setLoggerCategory(cfg.getLogCategory());
            handlerCfg.setHandler(handler);

            if (!useCoreLoop) {
                handlerCfg.setEventLoop(eventLoop);
            }
        }

        NetworkConnector<T> connector = new DefaultNetworkConnector<>(protocol, factory);

        return new ConnectorRegistration<>(protocol, !useCoreLoop ? eventLoop : null, connector, handlerCfg);
    }

    private NetworkConnectorConfig<Object> pingConnector() {
        NetworkConnectorConfig<Object> ping = new NetworkConnectorConfig<>();

        SingletonCodecFactory<Object> codecFactory = new SingletonCodecFactory<>(new Codec<Object>() {
            @Override
            public Object decode(DataReader in) throws IOException {
                throw new UnsupportedOperationException(PING_PROTOCOL + " doesn't support any messages.");
            }

            @Override
            public void encode(Object message, DataWriter out) throws IOException {
                throw new UnsupportedOperationException(PING_PROTOCOL + " doesn't support any messages.");
            }

            @Override
            public boolean isStateful() {
                return false;
            }

            @Override
            public Class<Object> baseType() {
                return Object.class;
            }
        });

        ping.setProtocol(PING_PROTOCOL);
        ping.setMessageCodec(codecFactory);
        ping.setServerHandler((message, from) -> {
            throw new UnsupportedOperationException(PING_PROTOCOL + " doesn't support any messages.");
        });

        return ping;
    }

    private NettyMetricsFactory createMetricsAdaptor(boolean server, LocalMetricsService metrics) {
        // Overall bytes metrics.
        CounterMetric allBytesSent = counter(METRIC_BYTES_OUT, true, metrics);
        CounterMetric allBytesReceived = counter(METRIC_BYTES_IN, true, metrics);

        // Overall message metrics.
        CounterMetric allMsgSent = counter(METRIC_MSG_OUT, true, metrics);
        CounterMetric allMsgReceived = counter(METRIC_MSG_IN, true, metrics);
        CounterMetric allMsgQueue = counter(METRIC_MSG_QUEUE, false, metrics);
        CounterMetric allMsgFailed = counter(METRIC_MSG_ERR, true, metrics);

        // Overall connection metrics.
        CounterMetric allConnections = counter(METRIC_CONN_ACTIVE, false, metrics);

        return protocol -> {
            // Connector bytes metrics.
            CounterMetric bytesSent = counter(METRIC_BYTES_OUT, protocol, server, true, metrics);
            CounterMetric bytesReceived = counter(METRIC_BYTES_IN, protocol, server, true, metrics);

            // Connector message metrics.
            CounterMetric msgSent = counter(METRIC_MSG_OUT, protocol, server, true, metrics);
            CounterMetric msgReceived = counter(METRIC_MSG_IN, protocol, server, true, metrics);
            CounterMetric msgQueue = counter(METRIC_MSG_QUEUE, protocol, server, false, metrics);
            CounterMetric msgFailed = counter(METRIC_MSG_ERR, protocol, server, true, metrics);

            // Connector connection metrics.
            CounterMetric connections = counter(METRIC_CONN_ACTIVE, protocol, server, false, metrics);

            return new NettyMetricsSink() {
                @Override
                public void onBytesSent(long bytes) {
                    bytesSent.add(bytes);

                    allBytesSent.add(bytes);
                }

                @Override
                public void onBytesReceived(long bytes) {
                    bytesReceived.add(bytes);

                    allBytesReceived.add(bytes);
                }

                @Override
                public void onMessageSent() {
                    msgSent.increment();

                    allMsgSent.increment();
                }

                @Override
                public void onMessageReceived() {
                    msgReceived.increment();

                    allMsgReceived.increment();
                }

                @Override
                public void onMessageSendError() {
                    msgFailed.increment();

                    allMsgFailed.increment();
                }

                @Override
                public void onMessageEnqueue() {
                    msgQueue.increment();

                    allMsgQueue.increment();
                }

                @Override
                public void onMessageDequeue() {
                    msgQueue.decrement();

                    allMsgQueue.decrement();
                }

                @Override
                public void onConnect() {
                    connections.increment();

                    allConnections.increment();
                }

                @Override
                public void onDisconnect() {
                    connections.decrement();

                    allConnections.decrement();
                }
            };
        };
    }

    private <T> CodecFactory<T> defaultCodecFactory() {
        return codec.codecFactory();
    }

    private EventLoopGroup newEventLoop(int size, String threadNamePrefix) {
        switch (transport) {
            case EPOLL: {
                return new EpollEventLoopGroup(size, new HekateThreadFactory(threadNamePrefix));
            }
            case NIO: {
                return new NioEventLoopGroup(size, new HekateThreadFactory(threadNamePrefix));
            }
            case AUTO: // <-- Fail since AUTO must be resolved in the constructor.
            default: {
                throw new IllegalArgumentException("Unexpected transport type: " + transport);
            }
        }
    }

    private void shutdown(EventLoopGroup group) {
        NettyUtils.shutdown(group).awaitUninterruptedly();
    }

    private static CounterMetric counter(String name, boolean autoReset, LocalMetricsService metrics) {
        return counter(name, null, null, autoReset, metrics);
    }

    private static CounterMetric counter(String name, String protocol, Boolean server, boolean autoReset, LocalMetricsService metrics) {
        String counterName = "";

        if (protocol != null) {
            counterName += protocol + '.';
        }

        counterName += "network.";

        if (server != null) {
            counterName += server ? "server." : "client.";
        }

        counterName += name;

        CounterConfig cfg = new CounterConfig(counterName);

        cfg.setAutoReset(autoReset);

        if (autoReset) {
            cfg.setName(counterName + ".current");
            cfg.setTotalName(counterName + ".total");
        } else {
            cfg.setName(counterName);
        }

        return metrics.register(cfg);
    }

    @Override
    public String toString() {
        return ToString.format(NetworkService.class, this);
    }
}
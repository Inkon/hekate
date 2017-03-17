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

package io.hekate.coordinate.internal;

import io.hekate.cluster.ClusterNodeFilter;
import io.hekate.cluster.ClusterNodeService;
import io.hekate.cluster.ClusterService;
import io.hekate.cluster.ClusterTopology;
import io.hekate.cluster.ClusterView;
import io.hekate.cluster.event.ClusterEvent;
import io.hekate.cluster.event.ClusterEventType;
import io.hekate.codec.CodecFactory;
import io.hekate.codec.CodecService;
import io.hekate.coordinate.CoordinationConfigProvider;
import io.hekate.coordinate.CoordinationFuture;
import io.hekate.coordinate.CoordinationHandler;
import io.hekate.coordinate.CoordinationProcess;
import io.hekate.coordinate.CoordinationProcessConfig;
import io.hekate.coordinate.CoordinationService;
import io.hekate.coordinate.CoordinationServiceFactory;
import io.hekate.core.HekateException;
import io.hekate.core.internal.util.ArgAssert;
import io.hekate.core.internal.util.ConfigCheck;
import io.hekate.core.internal.util.HekateThreadFactory;
import io.hekate.core.internal.util.Utils;
import io.hekate.core.internal.util.Waiting;
import io.hekate.core.service.ConfigurableService;
import io.hekate.core.service.ConfigurationContext;
import io.hekate.core.service.DependencyContext;
import io.hekate.core.service.DependentService;
import io.hekate.core.service.InitializationContext;
import io.hekate.core.service.InitializingService;
import io.hekate.core.service.TerminatingService;
import io.hekate.messaging.Message;
import io.hekate.messaging.MessagingChannel;
import io.hekate.messaging.MessagingChannelConfig;
import io.hekate.messaging.MessagingConfigProvider;
import io.hekate.messaging.MessagingService;
import io.hekate.util.StateGuard;
import io.hekate.util.format.ToString;
import io.hekate.util.format.ToStringIgnore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toList;

public class DefaultCoordinationService implements CoordinationService, ConfigurableService, DependentService, InitializingService,
    TerminatingService, MessagingConfigProvider {
    private static final Logger log = LoggerFactory.getLogger(DefaultCoordinationProcess.class);

    private static final boolean DEBUG = log.isDebugEnabled();

    private static final String MESSAGING_CHANNEL = "hekate.coordination";

    private static final String PROCESSES_PROPERTY = "processes";

    private static final ClusterNodeFilter NODE_FILTER = node -> node.hasService(CoordinationService.class);

    private final long failoverDelay;

    private final int sockets;

    private final int nioThreads;

    @ToStringIgnore
    private final StateGuard guard = new StateGuard(CoordinationService.class);

    @ToStringIgnore
    private final List<CoordinationProcessConfig> processesConfig = new ArrayList<>();

    @ToStringIgnore
    private final Map<String, DefaultCoordinationProcess> processes = new HashMap<>();

    @ToStringIgnore
    private MessagingService messaging;

    @ToStringIgnore
    private ClusterView cluster;

    @ToStringIgnore
    private CodecService defaultCodec;

    public DefaultCoordinationService(CoordinationServiceFactory factory) {
        assert factory != null : "Factory is null.";

        ConfigCheck check = ConfigCheck.get(CoordinationServiceFactory.class);

        check.positive(factory.getRetryInterval(), "retry interval");

        this.sockets = factory.getSockets();
        this.nioThreads = factory.getNioThreads();
        this.failoverDelay = factory.getRetryInterval();

        Utils.nullSafe(factory.getProcesses()).forEach(processesConfig::add);
    }

    @Override
    public void resolve(DependencyContext ctx) {
        messaging = ctx.require(MessagingService.class);
        cluster = ctx.require(ClusterService.class).filter(NODE_FILTER);
        defaultCodec = ctx.require(CodecService.class);
    }

    @Override
    public void configure(ConfigurationContext ctx) {
        // Collect configurations from providers.
        Collection<CoordinationConfigProvider> providers = ctx.findComponents(CoordinationConfigProvider.class);

        Utils.nullSafe(providers).forEach(provider -> {
            Collection<CoordinationProcessConfig> processesCfg = provider.getCoordinationConfig();

            Utils.nullSafe(processesCfg).forEach(processesConfig::add);
        });

        // Validate configs.
        ConfigCheck check = ConfigCheck.get(CoordinationProcessConfig.class);

        Set<String> uniqueNames = new HashSet<>();

        processesConfig.forEach(cfg -> {
            check.notEmpty(cfg.getName(), "name");
            check.notNull(cfg.getHandler(), "handler");

            String name = cfg.getName().trim();

            check.unique(name, uniqueNames, "name");

            uniqueNames.add(name);
        });

        // Register process names as service property.
        processesConfig.forEach(cfg ->
            ctx.addServiceProperty(PROCESSES_PROPERTY, cfg.getName().trim())
        );
    }

    @Override
    public Collection<MessagingChannelConfig<?>> getMessagingConfig() {
        Map<String, CodecFactory<Object>> processCodecs = new HashMap<>();

        processesConfig.forEach(cfg -> {
            String name = cfg.getName().trim();

            if (cfg.getMessageCodec() == null) {
                processCodecs.put(name, defaultCodec.getCodecFactory());
            } else {
                processCodecs.put(name, cfg.getMessageCodec());
            }
        });

        return Collections.singleton(new MessagingChannelConfig<CoordinationProtocol>()
            .withName(MESSAGING_CHANNEL)
            .withLogCategory(DefaultCoordinationService.class.getName())
            .withSockets(sockets)
            .withNioThreads(nioThreads)
            .withMessageCodec(() -> new CoordinationProtocolCodec(processCodecs))
            .withClusterFilter(NODE_FILTER)
            .withReceiver(this::handleMessage)
        );
    }

    @Override
    public void initialize(InitializationContext ctx) throws HekateException {
        guard.lockWrite();

        try {
            guard.becomeInitialized();

            if (DEBUG) {
                log.debug("Initializing...");
            }

            cluster.addListener(this::processTopologyChange, ClusterEventType.JOIN, ClusterEventType.CHANGE);

            MessagingChannel<CoordinationProtocol> channel = messaging.channel(MESSAGING_CHANNEL);

            processesConfig.forEach(cfg ->
                register(cfg, channel)
            );

            if (DEBUG) {
                log.debug("Initialized.");
            }
        } finally {
            guard.unlockWrite();
        }
    }

    @Override
    public void terminate() throws HekateException {
        Waiting waiting = null;

        guard.lockWrite();

        try {
            if (guard.becomeTerminated()) {
                if (DEBUG) {
                    log.debug("Terminating...");
                }

                waiting = Waiting.awaitAll(processes.values().stream().map(DefaultCoordinationProcess::terminate).collect(toList()));

                processes.clear();
            }
        } finally {
            guard.unlockWrite();
        }

        if (waiting != null) {
            waiting.awaitUninterruptedly();

            if (DEBUG) {
                log.debug("Terminated.");
            }
        }
    }

    @Override
    public List<CoordinationProcess> allProcesses() {
        guard.lockReadWithStateCheck();

        try {
            return new ArrayList<>(processes.values());
        } finally {
            guard.unlockRead();
        }
    }

    @Override
    public CoordinationProcess process(String name) {
        DefaultCoordinationProcess process;

        guard.lockReadWithStateCheck();

        try {
            process = processes.get(name);
        } finally {
            guard.unlockRead();
        }

        ArgAssert.check(process != null, "Coordination process not configured [name=" + name + ']');

        return process;
    }

    @Override
    public boolean hasProcess(String name) {
        guard.lockReadWithStateCheck();

        try {
            return processes.containsKey(name);
        } finally {
            guard.unlockRead();
        }
    }

    @Override
    public CoordinationFuture futureOf(String process) {
        return process(process).getFuture();
    }

    private void register(CoordinationProcessConfig cfg, MessagingChannel<CoordinationProtocol> channel) {
        assert guard.isWriteLocked() : "Thread must hold write lock.";

        if (DEBUG) {
            log.debug("Registering new process [configuration={}]", cfg);
        }

        String name = cfg.getName().trim();

        CoordinationHandler handler = cfg.getHandler();

        HekateThreadFactory threadFactory = new HekateThreadFactory(CoordinationService.class.getSimpleName() + "-" + name);

        ExecutorService async = Executors.newSingleThreadExecutor(threadFactory);

        DefaultCoordinationProcess process = new DefaultCoordinationProcess(name, handler, async, channel, failoverDelay);

        processes.put(name, process);

        process.initialize();
    }

    private void handleMessage(Message<CoordinationProtocol> msg) {
        DefaultCoordinationProcess process = null;

        CoordinationProtocol.Request request = msg.get(CoordinationProtocol.Request.class);

        guard.lockRead();

        try {
            if (guard.isInitialized()) {
                process = processes.get(request.getProcessName());

                if (process == null) {
                    throw new IllegalStateException("Received coordination request for unknown process: " + request);
                }
            }
        } finally {
            guard.unlockRead();
        }

        if (process == null) {
            if (DEBUG) {
                log.debug("Rejecting coordination message since service is not initialized [message={}]", request);
            }

            msg.reply(CoordinationProtocol.Reject.INSTANCE);
        } else {
            process.processMessage(msg);
        }
    }

    private void processTopologyChange(ClusterEvent event) {
        guard.lockRead();

        try {
            if (guard.isInitialized()) {
                processes.values().forEach(process -> {
                    ClusterTopology topology = event.getTopology().filter(node -> {
                        ClusterNodeService service = node.getService(CoordinationService.class);

                        return service.getProperty(PROCESSES_PROPERTY).contains(process.getName());
                    });

                    process.processTopologyChange(topology);
                });
            }
        } finally {
            guard.unlockRead();
        }
    }

    @Override
    public String toString() {
        return CoordinationService.class.getSimpleName() + '['
            + ToString.formatProperties(this)
            + ", processes=" + Utils.toString(processesConfig, CoordinationProcessConfig::getName)
            + ']';
    }
}

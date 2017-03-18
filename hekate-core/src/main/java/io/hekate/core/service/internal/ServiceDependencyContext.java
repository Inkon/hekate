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

package io.hekate.core.service.internal;

import io.hekate.core.service.ConfigurationContext;
import io.hekate.core.service.DependencyContext;
import io.hekate.core.service.Service;
import io.hekate.core.service.ServiceDependencyException;
import io.hekate.util.format.ToString;
import io.hekate.util.format.ToStringIgnore;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ServiceDependencyContext implements DependencyContext {
    private static class ServiceElement {
        private final Object instance;

        private final ServiceElement parent;

        public ServiceElement(Object instance, ServiceElement parent) {
            this.instance = instance;
            this.parent = parent;
        }

        public Object getInstance() {
            return instance;
        }

        public ServiceElement getParent() {
            return parent;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ServiceDependencyContext.class);

    private static final boolean DEBUG = log.isDebugEnabled();

    @ToStringIgnore
    private final ServiceManager manager;

    @ToStringIgnore
    private final Set<Class<? extends Service>> allServiceTypes = new HashSet<>();

    @ToStringIgnore
    private ServiceElement currentService;

    public ServiceDependencyContext(ServiceManager manager) {
        this.manager = manager;
    }

    @Override
    public <T extends Service> T optional(Class<T> type) {
        checkState();

        ServiceHandler handler = manager.findServiceDirect(type);

        if (handler != null) {
            handler.resolve(this);

            return type.cast(handler.getService());
        }

        return null;
    }

    @Override
    public <T extends Service> T require(Class<T> type) {
        checkState();

        if (DEBUG) {
            log.debug("Resolving required service dependency [type={}, required-by={}]", type.getName(), currentService.getInstance());
        }

        ServiceHandler handler = manager.findOrCreateService(type);

        if (handler == null) {
            throw new ServiceDependencyException("Failed to resolve dependency for service " + currentService.getInstance()
                + " [failed-dependency=" + type.getName() + ']');

        }

        handler.resolve(this);

        return type.cast(handler.getService());
    }

    public void prepare(Object service) {
        Set<Class<? extends Service>> interfaces = getServiceInterfaces(service);

        allServiceTypes.addAll(interfaces);

        currentService = new ServiceElement(service, currentService);
    }

    public void close() {
        currentService = currentService.getParent();
    }

    public Set<Class<? extends Service>> getServiceTypes() {
        return allServiceTypes;
    }

    private void checkState() {
        if (currentService == null) {
            throw new IllegalStateException("Dependency context already closed.");
        }
    }

    private static Set<Class<? extends Service>> getServiceInterfaces(Object service) {
        Class<?> serviceType = service.getClass();

        Set<Class<? extends Service>> interfaces = new HashSet<>();

        for (Class<?> type : serviceType.getInterfaces()) {
            if (!Service.class.equals(type) && Service.class.isAssignableFrom(type)) {
                interfaces.add(type.asSubclass(Service.class));
            }
        }

        return interfaces;
    }

    @Override
    public String toString() {
        return ToString.format(ConfigurationContext.class, this);
    }
}
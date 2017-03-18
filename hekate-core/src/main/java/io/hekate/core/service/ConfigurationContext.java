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

package io.hekate.core.service;

import io.hekate.cluster.ClusterNode;
import io.hekate.cluster.ClusterNodeService;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Context for {@link ConfigurableService}.
 *
 * @see ConfigurableService#configure(ConfigurationContext)
 */
public interface ConfigurationContext {
    /**
     * Returns the immutable set of {@link ClusterNode#getRoles() node roles}.
     *
     * @return Immutable set of {@link ClusterNode#getRoles() node roles}.
     *
     * @see #addNodeRole(String)
     */
    Set<String> getNodeRoles();

    /**
     * Adds the specified {@link ClusterNode#getRoles() node role}.
     *
     * @param role Role.
     */
    void addNodeRole(String role);

    /**
     * Returns the immutable map of {@link ClusterNode#getProperties() node properties}.
     *
     * @return Immutable map of {@link ClusterNode#getProperties() node properties}.
     *
     * @see #addNodeProperty(String, String)
     */
    Map<String, String> getNodeProperties();

    /**
     * Adds the specified {@link ClusterNode#getProperties() node property}.
     *
     * @param name Property name.
     * @param value Property value.
     */
    void addNodeProperty(String name, String value);

    /**
     * Adds the specified {@link ClusterNodeService#getProperties() service property}.
     *
     * @param name Property name.
     * @param value Property value.
     */
    void addServiceProperty(String name, String value);

    /**
     * Searches for all components of the specified type.
     *
     * @param type Component type.
     * @param <T> Component type.
     *
     * @return Collections of matching components of an empty collection if there are no such components.
     */
    <T> Collection<T> findComponents(Class<T> type);
}
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

package io.hekate.spring.boot.lock;

import io.hekate.core.Hekate;
import io.hekate.lock.DistributedLock;
import io.hekate.lock.LockRegion;
import io.hekate.lock.LockRegionConfig;
import io.hekate.lock.LockService;
import io.hekate.lock.LockServiceFactory;
import io.hekate.spring.bean.lock.LockBean;
import io.hekate.spring.bean.lock.LockRegionBean;
import io.hekate.spring.bean.lock.LockServiceBean;
import io.hekate.spring.boot.ConditionalOnHekateEnabled;
import io.hekate.spring.boot.HekateConfigurer;
import io.hekate.spring.boot.internal.AnnotationInjectorBase;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.BeanMetadataAttribute;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * <span class="startHere">&laquo; start here</span>Auto-configuration for {@link LockService}.
 *
 * <h2>Overview</h2>
 * <p>
 * This auto-configuration constructs a {@link Bean} of {@link LockServiceFactory} type and automatically {@link
 * LockServiceFactory#setRegions(List) registers} all {@link Bean}s of {@link LockRegionConfig} type.
 * </p>
 *
 * <p>
 * <b>Note: </b> this auto-configuration is available only if application doesn't provide its own {@link Bean} of {@link
 * LockServiceFactory} type and if there is at least one {@link Bean} of {@link LockRegionConfig} type within the application context.
 * </p>
 *
 * <h2>Locks injections</h2>
 * <p>
 * This auto-configuration provides support for injecting beans of {@link LockRegion} and {@link DistributedLock} type into other beans with
 * the help of {@link NamedLockRegion} and {@link NamedLock} annotations.
 * </p>
 *
 * <p>
 * Please see the documentation of the following annotations for more details:
 * </p>
 * <ul>
 * <li>{@link NamedLockRegion} - for injection of {@link LockRegion}s</li>
 * <li>{@link NamedLock} - for injection of {@link DistributedLock}s</li>
 * </ul>
 *
 * @see LockService
 * @see HekateConfigurer
 */
@Configuration
@ConditionalOnHekateEnabled
@AutoConfigureBefore(HekateConfigurer.class)
@ConditionalOnBean(LockRegionConfig.class)
@ConditionalOnMissingBean(LockServiceFactory.class)
public class HekateLockServiceConfigurer {
    @Component
    static class NamedLockRegionInjector extends AnnotationInjectorBase<NamedLockRegion> {
        public NamedLockRegionInjector() {
            super(NamedLockRegion.class, LockRegionBean.class);
        }

        @Override
        protected String getInjectedBeanName(NamedLockRegion annotation) {
            return LockRegionBean.class.getName() + "-" + annotation.value();
        }

        @Override
        protected Object getQualifierValue(NamedLockRegion annotation) {
            return annotation.value();
        }

        @Override
        protected void configure(BeanDefinitionBuilder builder, NamedLockRegion annotation) {
            builder.addPropertyValue("region", annotation.value());
        }
    }

    @Component
    static class NamedLockInjector extends AnnotationInjectorBase<NamedLock> {
        public NamedLockInjector() {
            super(NamedLock.class, LockBean.class);
        }

        @Override
        protected String getInjectedBeanName(NamedLock annotation) {
            return LockBean.class.getName() + "-" + annotation.name() + "--" + annotation.name();
        }

        @Override
        protected Object getQualifierValue(NamedLock annotation) {
            return null;
        }

        @Override
        protected void customize(AutowireCandidateQualifier qualifier, NamedLock annotation) {
            qualifier.addMetadataAttribute(new BeanMetadataAttribute("region", annotation.region()));
            qualifier.addMetadataAttribute(new BeanMetadataAttribute("name", annotation.name()));
        }

        @Override
        protected void configure(BeanDefinitionBuilder builder, NamedLock annotation) {
            builder.addPropertyValue("region", annotation.region());
            builder.addPropertyValue("name", annotation.name());
        }
    }

    private final List<LockRegionConfig> regions;

    /**
     * Constructs new instance.
     *
     * @param regions {@link LockRegionConfig}s that were found in the application context.
     */
    public HekateLockServiceConfigurer(Optional<List<LockRegionConfig>> regions) {
        this.regions = regions.orElse(null);
    }

    /**
     * Constructs the {@link LockServiceFactory}.
     *
     * @return Service factory.
     */
    @Bean
    @ConfigurationProperties(prefix = "hekate.locks")
    public LockServiceFactory lockServiceFactory() {
        LockServiceFactory factory = new LockServiceFactory();

        factory.setRegions(regions);

        return factory;
    }

    /**
     * Returns the factory bean that makes it possible to inject {@link LockService} directly into other beans instead of accessing it via
     * {@link Hekate#get(Class)} method.
     *
     * @return Service bean.
     */
    @Bean
    public LockServiceBean lockService() {
        return new LockServiceBean();
    }
}
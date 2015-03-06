/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.eureka2.server.spi;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.ServiceLoader;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.netflix.eureka2.server.audit.AuditService;
import com.netflix.eureka2.server.audit.SimpleAuditService;
import com.netflix.eureka2.server.spi.ExtAbstractModule.ServerType;
import com.netflix.governator.guice.BootstrapBinder;
import com.netflix.governator.guice.BootstrapModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads Eureka extensions using {@link java.util.ServiceLoader}. Eureka extension
 * to be discoverable must provide module implementation derived from {@link ExtAbstractModule},
 * that is installed in META-INF/services according to the {@link java.util.ServiceLoader} rules.
 *
 * @author Tomasz Bak
 */
public class ExtensionLoader {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private final boolean stdExtOnly;

    public ExtensionLoader() {
        this.stdExtOnly = false;
    }

    public ExtensionLoader(boolean stdExtOnly) {
        this.stdExtOnly = stdExtOnly;
    }

    public Module[] asModuleArray(ServerType serverType) {
        List<Module> moduleList = enableExtensions(serverType);
        return moduleList.toArray(new Module[moduleList.size()]);
    }

    public BootstrapModule asBootstrapModule(final ServerType serverType) {
        return new BootstrapModule() {
            @Override
            public void configure(BootstrapBinder binder) {
                for (Module m : enableExtensions(serverType)) {
                    binder.include(m);
                }
            }
        };
    }

    private List<Module> enableExtensions(ServerType serverType) {
        List<Module> moduleList = new ArrayList<>();

        // Discover and load whats available
        final EnumSet<StandardExtension> loadedStdExts;
        if (!stdExtOnly) {
            loadedStdExts = EnumSet.noneOf(StandardExtension.class);
            for (ExtAbstractModule m : ServiceLoader.load(ExtAbstractModule.class)) {
                String moduleName = m.getClass().getName();
                if (m.isRunnable(serverType)) {
                    logger.info("Loading module {}", moduleName);
                    moduleList.add(m);
                    if (m.standardExtension() != StandardExtension.Undefined) {
                        loadedStdExts.add(m.standardExtension());
                    }
                } else {
                    logger.info("Ignoring module {}, as it is not runnable on {} server", moduleName, serverType);
                }
            }
        } else {
            loadedStdExts = EnumSet.noneOf(StandardExtension.class);
        }

        // Use defaults for standard extensions
        moduleList.add(new AbstractModule() {
            @Override
            protected void configure() {
                EnumSet<StandardExtension> missingExtensions = EnumSet.complementOf(loadedStdExts);
                for (StandardExtension ext : missingExtensions) {
                    if (ext.hasDefault()) {
                        logger.info("Binding default implementation for service {}", ext.getServiceInterface());
                        bind(ext.getServiceInterface()).toInstance(ext.createInstance());
                    }
                }
            }
        });

        return moduleList;
    }

    public enum StandardExtension {
        AuditServiceExt(true, AuditService.class) {
            @Override
            public Object createInstance() {
                return new SimpleAuditService();
            }
        },
        Undefined(false, null) {
            @Override
            public Object createInstance() {
                throw new IllegalStateException("Undefined extension cannot be created");
            }
        };

        private final boolean defaultAvailable;
        private final Class serviceInterface;

        StandardExtension(boolean defaultAvailable, Class<?> serviceInterface) {
            this.defaultAvailable = defaultAvailable;
            this.serviceInterface = serviceInterface;
        }

        public boolean hasDefault() {
            return defaultAvailable;
        }

        public Class getServiceInterface() {
            return serviceInterface;
        }

        public abstract Object createInstance();
    }
}

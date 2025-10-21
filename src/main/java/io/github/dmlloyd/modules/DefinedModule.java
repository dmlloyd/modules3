package io.github.dmlloyd.modules;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.smallrye.common.constraint.Assert;
import io.smallrye.common.resource.ResourceLoader;

/**
 * A defined module.
 */
abstract class DefinedModule {
    static final class New extends DefinedModule {
        private final String moduleName;
        private final List<ResourceLoaderOpener> resourceLoaderOpeners;
        private final ModuleDescriptorLoader descriptorLoader;
        private final ReentrantLock loadLock = new ReentrantLock();
        private volatile DefinedModule result;

        New(final String moduleName, final List<ResourceLoaderOpener> resourceLoaderOpeners, final ModuleDescriptorLoader descriptorLoader) {
            this.moduleName = moduleName;
            this.resourceLoaderOpeners = List.copyOf(resourceLoaderOpeners);
            this.descriptorLoader = descriptorLoader;
        }

        String moduleName() {
            return moduleName;
        }

        ModuleClassLoader moduleClassLoader(final ModuleLoader moduleLoader) {
            DefinedModule result = this.result;
            if (result == null) {
                ReentrantLock lock = loadLock;
                lock.lock();
                try {
                    result = this.result;
                    if (result == null) {
                        List<ResourceLoaderOpener> openers = resourceLoaderOpeners;
                        int cnt = openers.size();
                        final ResourceLoader[] loaderArray = new ResourceLoader[cnt];
                        for (int i = 0; i < cnt; i++) {
                            ResourceLoaderOpener opener = openers.get(i);
                            try {
                                loaderArray[i] = Assert.checkNotNullArrayParam("returned loader from openers", i, opener.open());
                            } catch (Throwable t) {
                                ModuleLoadException mle = new ModuleLoadException("Failed to open a resource loader for `" + moduleName + "`", t);
                                for (int j = i - 1; j >= 0; j--) {
                                    try {
                                        loaderArray[j].close();
                                    } catch (Throwable t2) {
                                        mle.addSuppressed(t2);
                                    }
                                }
                                throw mle;
                            }
                        }
                        List<ResourceLoader> loaders = List.of(loaderArray);
                        try {
                            ModuleDescriptor desc = descriptorLoader.loadDescriptor(moduleName, loaders);
                            if (! desc.name().equals(moduleName)) {
                                throw new ModuleLoadException("Module descriptor for `" + moduleName + "` has unexpected name `" + desc.name() + "`");
                            }
                            ModuleClassLoader.ClassLoaderConfiguration config = new ModuleClassLoader.ClassLoaderConfiguration(
                                moduleLoader,
                                loaders,
                                desc
                            );
                            ModuleClassLoader mcl;
                            try {
                                mcl = moduleLoader.createClassLoader(config);
                            } finally {
                                config.clear();
                            }
                            result = new Loaded(mcl);
                            this.result = result;
                            moduleLoader.replace(this, result);
                        } catch (Throwable t) {
                            ModuleLoadException mle = new ModuleLoadException("Failed to load module descriptor for for " + moduleName, t);
                            for (int j = cnt - 1; j >= 0; j--) {
                                try {
                                    loaders.get(j).close();
                                } catch (Throwable t2) {
                                    mle.addSuppressed(t2);
                                }
                            }
                            throw mle;
                        }
                    }
                } catch (Exception t) {
                    moduleLoader.replace(this, new Failed(t));
                    throw t;
                } finally {
                    lock.unlock();
                }
            }
            return result.moduleClassLoader(moduleLoader);
        }
    }

    static final class Loaded extends DefinedModule {
        private final ModuleClassLoader moduleClassLoader;

        Loaded(final ModuleClassLoader moduleClassLoader) {
            this.moduleClassLoader = moduleClassLoader;
        }

        ModuleClassLoader moduleClassLoader(ModuleLoader ignored) {
            return moduleClassLoader;
        }
    }

    static final class Failed extends DefinedModule {
        private final Exception cause;

        Failed(final Exception cause) {
            this.cause = cause;
        }

        ModuleClassLoader moduleClassLoader(final ModuleLoader moduleLoader) {
            throw new ModuleLoadException("Previous module load failed", cause);
        }
    }

    abstract ModuleClassLoader moduleClassLoader(ModuleLoader moduleLoader);
}

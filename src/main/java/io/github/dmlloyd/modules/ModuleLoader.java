package io.github.dmlloyd.modules;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.desc.ResourceLoaderOpener;
import io.smallrye.common.constraint.Assert;
import io.smallrye.common.resource.ResourceLoader;

/**
 * A loader for modules.
 */
public class ModuleLoader implements Closeable {
    private final String name;
    private final ModuleFinder moduleFinder;
    private final ReentrantLock defineLock = new ReentrantLock();
    private final ConcurrentHashMap<String, ModuleClassLoader> loaders = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private static final Module javaBase = Object.class.getModule();

    public ModuleLoader(final String name, final ModuleFinder moduleFinder) {
        this.name = Assert.checkNotNullParam("name", name);
        this.moduleFinder = Assert.checkNotNullParam("moduleFinder", moduleFinder);
    }

    /**
     * {@return the name of this module loader}
     */
    public String name() {
        return name;
    }

    /**
     * Load a module with the given name.
     * If the module loader has been closed, an exception is thrown.
     * The module {@code java.base} is always resolved directly by this method.
     * Otherwise, this method delegates to {@link #doLoadModule(String)} which
     * may be overridden to implement specific loading behavior.
     *
     * @param moduleName the module name (must not be {@code null})
     * @return the loaded module, or {@code null} if the module is not found by this loader
     */
    public final Module loadModule(final String moduleName) {
        if (closed) {
            throw new IllegalStateException(this + " is closed");
        }
        if (moduleName.equals("java.base")) {
            return javaBase;
        } else {
            return doLoadModule(moduleName);
        }
    }

    public void close() throws IOException {
        List<ModuleClassLoader> loaders;
        defineLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            loaders = List.copyOf(this.loaders.values());
            this.loaders.clear();
        } finally {
            defineLock.unlock();
        }
        IOException ioe = null;
        try {
            moduleFinder.close();
        } catch (Throwable t) {
            ioe = new IOException("Error while closing module loader " + this, t);
        }
        for (ModuleClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (Throwable t) {
                if (ioe == null) {
                    ioe = new IOException("Error while closing module loader " + this, t);
                } else {
                    ioe.addSuppressed(t);
                }
            }
        }
        if (ioe != null) {
            throw ioe;
        }
    }

    /**
     * Load a module with the given name, delegating as needed.
     * This method may call {@link #findModuleLocal(String)} to attempt to load a locally-defined
     * module.
     *
     * @param moduleName the name of the module to load (not {@code null})
     * @return the loaded module, or {@code null} if no module was found for the given name
     */
    protected Module doLoadModule(final String moduleName) {
        ModuleClassLoader loaded = findModuleLocal(moduleName);
        return loaded == null ? null : loaded.module();
    }

    /**
     * {@return the controller for the given module class loader}
     * @param loader the module class loader (must not be {@code null})
     */
    protected final ModuleClassLoader.Controller controllerFor(ModuleClassLoader loader) {
        if (loader.moduleLoader() == this) {
            return loader.controller();
        }
        throw new SecurityException("Invalid access to controller");
    }

    /**
     * Load a module defined by this module loader.
     * No delegation is performed.
     *
     * @param moduleName the module name (must not be {@code null})
     * @return the module, or {@code null} if the module is not found within this loader
     */
    protected final ModuleClassLoader findModuleLocal(String moduleName) {
        ModuleClassLoader loader = loaders.get(moduleName);
        if (loader != null) {
            return loader;
        }
        ModuleDescriptor desc = moduleFinder.findModule(moduleName);
        if (desc == null) {
            return null;
        }
        return defineOrGet(moduleName, desc);
    }

    final ModuleClassLoader defineOrGet(String moduleName, ModuleDescriptor desc) {
        if (! desc.name().equals(moduleName)) {
            throw new ModuleLoadException("Module name \"" + moduleName + "\" does not match descriptor module name \"" + desc.name() + "\"");
        }
        ConcurrentHashMap<String, ModuleClassLoader> loaders = this.loaders;
        ReentrantLock lock = defineLock;
        lock.lock();
        try {
            ModuleClassLoader loader = loaders.get(moduleName);
            if (loader != null) {
                return loader;
            }
            List<ResourceLoaderOpener> openers = desc.resourceLoaderOpeners();
            int cnt = openers.size();
            ResourceLoader[] resourceLoaders = new ResourceLoader[cnt];
            try {
                for (int i = 0; i < cnt; i++) {
                    try {
                        resourceLoaders[i] = openers.get(i).open();
                        if (resourceLoaders[i] == null) {
                            throw new NullPointerException("Resource loader opener " + openers.get(i) + " returned null for open()");
                        }
                    } catch (Throwable t) {
                        for (int j = i - 1; j >= 0; j--) {
                            try {
                                resourceLoaders[i].close();
                            } catch (Throwable t2) {
                                t.addSuppressed(t2);
                            }
                        }
                        throw t;
                    }
                }
                loader = desc.classLoaderFactory().apply(new ModuleClassLoader.ClassLoaderConfiguration(
                    this,
                    name(),
                    List.of(resourceLoaders),
                    moduleName,
                    desc.version().orElse(null),
                    desc.dependencies(),
                    desc.exports(),
                    desc.opens(),
                    desc.packages(),
                    desc.modifiers(),
                    desc.uses(),
                    desc.provides()
                ));
                if (loader == null) {
                    throw new NullPointerException("Class loader factory " + desc.classLoaderFactory() + " returned null for apply()");
                }
                if (loader.moduleLoader() != this) {
                    throw new SecurityException("Invalid access to wrong module loader");
                }
            } catch (ModuleLoadException e) {
                throw e;
            } catch (Exception e) {
                throw new ModuleLoadException("Failed to create module " + moduleName, e);
            }
            loaders.put(moduleName, loader);
            return loader;
        } finally {
            lock.unlock();
        }
    }

    public static final ModuleLoader EMPTY = new ModuleLoader("empty", ModuleFinder.EMPTY);

    public static final ModuleLoader BOOT = forLayer("boot", ModuleLayer.boot());

    public static ModuleLoader forLayer(String name, ModuleLayer layer) {
        return new ModuleLoader(name, ModuleFinder.EMPTY) {
            public Module doLoadModule(final String moduleName) {
                Optional<Module> result = layer.findModule(moduleName);
                if (result.isPresent()) {
                    return result.get();
                } else {
                    return null;
                }
            }
        };
    }

    public static ModuleLoader forClassLoader(ClassLoader cl) {
        return cl instanceof ModuleClassLoader mcl ? mcl.moduleLoader() : null;
    }

    public static ModuleLoader forModule(Module module) {
        return forClassLoader(module.getClassLoader());
    }

    public static ModuleLoader forClass(Class<?> clazz) {
        return forClassLoader(clazz.getClassLoader());
    }
}

package io.github.dmlloyd.modules;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import io.smallrye.common.constraint.Assert;

/**
 * A loader for modules.
 */
public class ModuleLoader implements Closeable {
    private final String name;
    private final ReentrantLock defineLock = new ReentrantLock();
    private final ConcurrentHashMap<String, DefinedModule> definedModules = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * Construct a new instance.
     *
     * @param name the module loader's name (must not be {@code null})
     */
    public ModuleLoader(final String name) {
        this.name = Assert.checkNotNullParam("name", name);
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
     *
     * @param moduleName the module name (must not be {@code null})
     * @return the loaded module, or {@code null} if the module is not found by this loader
     */
    public LoadedModule loadModule(final String moduleName) {
        checkClosed();
        ModuleClassLoader loaded = findDefinedModule(moduleName);
        if (loaded == null) {
            loaded = findModule(moduleName);
            if (loaded == null) {
                return null;
            }
        }
        return LoadedModule.forModuleClassLoader(loaded);
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException(this + " is closed");
        }
    }

    public final LoadedModule requireModule(final String moduleName) {
        LoadedModule loadedModule = loadModule(moduleName);
        if (loadedModule == null) {
            throw new ModuleNotFoundException(moduleName);
        }
        return loadedModule;
    }

    /**
     * Create the class loader for the given configuration.
     * Used to allow module loaders to produce subclasses of {@link ModuleClassLoader}
     * which implement additional interfaces or provide a class loader name.
     *
     * @param configuration the class loader configuration (not {@code null})
     * @return the module class loader (must not be {@code null})
     */
    protected ModuleClassLoader createClassLoader(ModuleClassLoader.ClassLoaderConfiguration configuration) {
        return new ModuleClassLoader(configuration, null);
    }

    /**
     * Get a defined module with the given name.
     * If the module was not yet defined to this module loader, {@code null} is returned.
     * If the module was defined and loaded, the module's class loader is returned.
     * If the module was defined but cannot be loaded, then an exception is thrown.
     *
     * @param name the module name (must not be {@code null})
     * @return the module class loader, or {@code null} if none is found
     * @throws ModuleLoadException if the module was defined but cannot be loaded.
     */
    protected final ModuleClassLoader findDefinedModule(String name) throws ModuleLoadException {
        DefinedModule defined = definedModules.get(name);
        return defined == null ? null : defined.moduleClassLoader(this);
    }

    /**
     * Load a module defined by this module loader.
     * No delegation is performed.
     *
     * @param moduleName the module name (must not be {@code null})
     * @return the module, or {@code null} if the module is not found within this loader
     */
    protected ModuleClassLoader findModule(String moduleName) {
        return null;
    }

    /**
     * Atomically try to define a module with the given name, if it is not already defined.
     * If the module is already defined, then {@code null} is returned.
     * If the module cannot be defined due to an error, then an exception is thrown.
     * Otherwise, the return value is non-{@code null} and the module has been defined to this module loader.
     *
     * @param moduleName the module name (must not be {@code null})
     * @param loaderOpeners the list of resource loader openers for the new module (must not be {@code null})
     * @param descriptorLoader the module descriptor opener (must not be {@code null})
     * @return the defined module, or {@code null} if the module was already defined
     * @throws ModuleLoadException if the module cannot be defined due to an error, or a previous module definition
     *                             for this name has failed
     */
    protected final ModuleClassLoader tryDefineModule(
        String moduleName,
        List<ResourceLoaderOpener> loaderOpeners,
        ModuleDescriptorLoader descriptorLoader
    ) throws ModuleLoadException {
        ConcurrentHashMap<String, DefinedModule> loadedModules = this.definedModules;
        checkClosed();
        DefinedModule existing = loadedModules.get(moduleName);
        if (existing != null) {
            return null;
        }
        ReentrantLock lock = defineLock;
        lock.lock();
        DefinedModule dm;
        try {
            dm = new DefinedModule.New(moduleName, loaderOpeners, descriptorLoader);
            existing = loadedModules.putIfAbsent(moduleName, dm);
            if (existing != null) {
                return null;
            }
        } finally {
            lock.unlock();
        }
        return dm.moduleClassLoader(this);
    }

    public void close() throws IOException {
        if (closed) {
            return;
        }
        List<DefinedModule> definedModules;
        defineLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            definedModules = List.copyOf(this.definedModules.values());
            this.definedModules.clear();
        } finally {
            defineLock.unlock();
        }
        IOException ioe = null;
        for (DefinedModule definedModule : definedModules) {
            if (definedModule instanceof DefinedModule.Loaded loaded) {
                ModuleClassLoader loader = loaded.moduleClassLoader(this);
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
        }
        if (ioe != null) {
            throw ioe;
        }
    }

    public static ModuleLoader aggregate(String name, List<ModuleLoader> loaders) {
        List<ModuleLoader> copy = List.copyOf(loaders);
        return new ModuleLoader(name) {
            public LoadedModule loadModule(final String moduleName) {
                LoadedModule found = super.loadModule(moduleName);
                if (found != null) {
                    return found;
                }
                for (ModuleLoader moduleLoader : copy) {
                    found = moduleLoader.loadModule(moduleName);
                    if (found != null) {
                        return found;
                    }
                }
                return null;
            }
        };
    }

    public static ModuleLoader aggregate(String name, ModuleLoader... loaders) {
        return aggregate(name, List.of(loaders));
    }

    public static final ModuleLoader EMPTY = new ModuleLoader("empty");

    public static final ModuleLoader BASE = new ModuleLoader("java.base") {
        private static final Module javaBase = Object.class.getModule();
        private static final LoadedModule loadedJavaBase = LoadedModule.forModule(javaBase);

        public LoadedModule loadModule(final String moduleName) {
            if (moduleName.equals("java.base")) {
                return loadedJavaBase;
            } else {
                return super.loadModule(moduleName);
            }
        }
    };

    public static final ModuleLoader BOOT = forLayer("boot", ModuleLayer.boot());

    public static ModuleLoader forLayer(String name, ModuleLayer layer) {
        return new ModuleLoader(name) {
            public LoadedModule loadModule(final String moduleName) {
                return layer.findModule(moduleName).map(LoadedModule::forModule).orElse(null);
            }
        };
    }

    public static ModuleLoader ofClassLoader(ClassLoader cl) {
        return cl instanceof ModuleClassLoader mcl ? mcl.moduleLoader() : null;
    }

    public static ModuleLoader ofModule(Module module) {
        return ofClassLoader(module.getClassLoader());
    }

    public static ModuleLoader ofClass(Class<?> clazz) {
        return ofClassLoader(clazz.getClassLoader());
    }

    public static ModuleLoader ofThread(Thread thread) {
        return ofClassLoader(thread.getContextClassLoader());
    }

    void replace(final DefinedModule.New newModule, final DefinedModule result) {
        definedModules.replace(newModule.moduleName(), newModule, result);
    }
}

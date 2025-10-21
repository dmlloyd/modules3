package io.github.dmlloyd.modules;

import java.util.Optional;

import io.smallrye.common.constraint.Assert;

/**
 * A module that has been loaded, but not necessarily defined yet.
 * Objects of this type represent handles to a module, are not unique,
 * and have no defined identity semantics.
 * Usage as a hash key in particular is not supported.
 */
public sealed abstract class LoadedModule {
    private LoadedModule() {}

    /**
     * Load the module instance associated with this loaded module.
     *
     * @return the module instance (not {@code null})
     * @throws ModuleLoadException if the module could not be loaded
     */
    public abstract Module module() throws ModuleLoadException;

    /**
     * Find the class loader associated with the module.
     * If the module is managed by this framework, the returned class loader
     * will extend {@link ModuleClassLoader} and may represent a module
     * which is still in early stages of loading (i.e. no module yet exists).
     *
     * @return the class loader, or {@code null} if the module is on the boostrap class loader
     * @throws ModuleLoadException if the module could not be loaded
     */
    public abstract ClassLoader classLoader() throws ModuleLoadException;

    /**
     * {@return the optional name of the module}
     */
    public abstract Optional<String> name();

    public boolean equals(final Object obj) {
        return obj instanceof LoadedModule lm && equals(lm);
    }

    public abstract boolean equals(LoadedModule other);

    public abstract int hashCode();

    /**
     * {@return a loaded module for the given module}
     * @param module the module to encapsulate (must not be {@code null})
     */
    public static LoadedModule forModule(Module module) {
        Assert.checkNotNullParam("module", module);
        if (module.getClassLoader() instanceof ModuleClassLoader mcl) {
            // this will be false if mcl defines a named module but module is unnamed
            if (module.isNamed() || mcl.module() == module) {
                return forModuleClassLoader(mcl);
            }
        }
        return new External(module);
    }

    /**
     * {@return a loaded module for the given module class loader}
     * @param cl the class loader of the module to encapsulate (must not be {@code null})
     */
    public static LoadedModule forModuleClassLoader(ModuleClassLoader cl) {
        return new Internal(Assert.checkNotNullParam("cl", cl));
    }

    static final class Internal extends LoadedModule {
        private final ModuleClassLoader cl;

        public Internal(final ModuleClassLoader cl) {
            this.cl = cl;
        }

        public Module module() throws ModuleLoadException {
            return cl.module();
        }

        public Optional<String> name() {
            return Optional.of(cl.moduleName());
        }

        public ModuleClassLoader classLoader() {
            return cl;
        }

        public boolean equals(final LoadedModule other) {
            return other != null && other.getClass() == getClass() && cl.equals(other.classLoader());
        }

        public int hashCode() {
            return cl.hashCode();
        }

        public String toString() {
            return "Loaded[" + classLoader() + "]";
        }
    }

    private static final class External extends LoadedModule {
        private final Module module;

        public External(final Module module) {
            this.module = module;
        }

        public Module module() throws ModuleLoadException {
            return module;
        }

        public Optional<String> name() {
            return module.isNamed() ? Optional.of(module.getName()) : Optional.empty();
        }

        public ClassLoader classLoader() throws ModuleLoadException {
            return module.getClassLoader();
        }

        public boolean equals(final LoadedModule other) {
            return other != null && other.getClass() == getClass() && module.equals(other.module());
        }

        public int hashCode() {
            return module.hashCode();
        }

        public String toString() {
            return "Loaded[" + module() + "]";
        }
    }
}

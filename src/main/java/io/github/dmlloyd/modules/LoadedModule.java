package io.github.dmlloyd.modules;

import io.smallrye.common.constraint.Assert;

/**
 * A module that has been loaded, but not necessarily defined yet.
 * Objects of this type represent handles to a module, are not unique,
 * and have no defined identity semantics.
 * Usage as a hash key in particular is not supported.
 */
public abstract class LoadedModule {
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
     * {@return a loaded module for the given module}
     * @param module the module to encapsulate (must not be {@code null})
     */
    public static LoadedModule forModule(Module module) {
        ClassLoader cl = module.getClassLoader();
        if (cl instanceof ModuleClassLoader mcl) {
            return forModuleClassLoader(mcl);
        }
        return new LoadedModule() {
            public Module module() throws ModuleLoadException {
                return module;
            }

            public ClassLoader classLoader() throws ModuleLoadException {
                return module.getClassLoader();
            }
        };
    }

    /**
     * {@return a loaded module for the given module class loader}
     * @param cl the class loader of the module to encapsulate (must not be {@code null})
     */
    public static LoadedModule forModuleClassLoader(ModuleClassLoader cl) {
        Assert.checkNotNullParam("cl", cl);
        return new LoadedModule() {
            public Module module() throws ModuleLoadException {
                return cl.module();
            }

            public ModuleClassLoader classLoader() {
                return cl;
            }
        };
    }
}

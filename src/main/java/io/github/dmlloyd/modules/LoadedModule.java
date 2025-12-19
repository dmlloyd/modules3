package io.github.dmlloyd.modules;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.smallrye.common.constraint.Assert;

/**
 * A module that has been loaded, but not necessarily defined yet.
 * Objects of this type represent handles to a module, are not unique,
 * and have no defined identity semantics.
 * Usage as a hash key in particular is not supported.
 */
public final class LoadedModule {
    private final Module module;
    private final ModuleClassLoader moduleClassLoader;

    private LoadedModule(Module module) {
        if (module.getClassLoader() instanceof ModuleClassLoader mcl) {
            // this will be false if mcl defines a named module but module is unnamed
            if (module.isNamed() || mcl.module() == module) {
                this.module = null;
                this.moduleClassLoader = mcl;
                return;
            }
        }
        this.module = module;
        this.moduleClassLoader = null;
    }

    private LoadedModule(final ModuleClassLoader moduleClassLoader) {
        this.module = null;
        this.moduleClassLoader = moduleClassLoader;
    }

    /**
     * Load the module instance associated with this loaded module.
     *
     * @return the module instance (not {@code null})
     */
    public Module module() {
        if (module != null) {
            return module;
        } else {
            return moduleClassLoader.module();
        }
    }

    /**
     * Find the class loader associated with the module.
     * If the module is managed by this framework, the returned class loader
     * will extend {@link ModuleClassLoader} and may represent a module
     * which is still in early stages of loading (i.e. no module yet exists).
     *
     * @return the class loader, or {@code null} if the module is on the boostrap class loader
     */
    public ClassLoader classLoader() {
        if (module != null) {
            return module.getClassLoader();
        } else {
            return moduleClassLoader;
        }
    }

    /**
     * {@return the set of package names exported to the given module (not {@code null})}
     * This set only includes packages defined within this module directly.
     * @param toModule the module being exported to (must not be {@code null})
     */
    public Set<String> exportedPackageNames(Module toModule) {
        if (this.module != null) {
            return this.module.getPackages().stream().filter(pn -> module.isExported(pn, toModule)).collect(Collectors.toUnmodifiableSet());
        } else {
            return moduleClassLoader.exportedPackageNames(toModule);
        }
    }

    boolean isExported(String packageName, Module toModule) {
        if (this.module != null) {
            return this.module.isExported(packageName, toModule);
        } else {
            return moduleClassLoader.isExported(packageName, toModule);
        }
    }

    /**
     * {@return the optional name of the module}
     */
    public Optional<String> name() {
        if (module != null) {
            return module.isNamed() ? Optional.of(module.getName()) : Optional.empty();
        } else {
            return Optional.of(moduleClassLoader.moduleName());
        }
    }

    public boolean equals(final Object obj) {
        return obj instanceof LoadedModule lm && equals(lm);
    }

    public boolean equals(LoadedModule other) {
        return other != null && module == other.module && moduleClassLoader  == other.moduleClassLoader;
    }

    public int hashCode() {
        return Objects.hash(module, moduleClassLoader);
    }

    public String toString() {
        return module != null ? String.valueOf(module) : moduleClassLoader.toString();
    }

    /**
     * {@return a loaded module for the given module}
     * @param module the module to encapsulate (must not be {@code null})
     */
    public static LoadedModule forModule(Module module) {
        return new LoadedModule(Assert.checkNotNullParam("module", module));
    }

    /**
     * {@return a loaded module for the given module class loader}
     * @param cl the class loader of the module to encapsulate (must not be {@code null})
     */
    public static LoadedModule forModuleClassLoader(ModuleClassLoader cl) {
        return new LoadedModule(Assert.checkNotNullParam("cl", cl));
    }
}

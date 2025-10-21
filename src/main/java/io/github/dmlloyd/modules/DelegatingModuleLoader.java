package io.github.dmlloyd.modules;

import java.util.function.Function;

import io.smallrye.common.constraint.Assert;

/**
 * A module loader which loads modules from a delegate loader if it is not locally defined.
 */
public final class DelegatingModuleLoader extends ModuleLoader {
    private final Function<String, ModuleLoader> delegateFn;

    /**
     * Construct a new instance.
     *
     * @param name the module loader name (must not be {@code null})
     * @param moduleFinder the module finder (must not be {@code null})
     * @param delegateFn the function which yields the delegate to use when the module is not found (must not be {@code null})
     */
    public DelegatingModuleLoader(final String name, final ModuleFinder moduleFinder, final Function<String, ModuleLoader> delegateFn) {
        super(name);
        this.delegateFn = Assert.checkNotNullParam("delegateFn", delegateFn);
    }

    /**
     * Construct a new instance.
     *
     * @param name the module loader name (must not be {@code null})
     * @param moduleFinder the module finder (must not be {@code null})
     * @param delegate the delegate loader to use when the module is not found (must not be {@code null})
     */
    public DelegatingModuleLoader(final String name, final ModuleFinder moduleFinder, final ModuleLoader delegate) {
        this(name, moduleFinder, __ -> delegate);
        Assert.checkNotNullParam("delegate", delegate);
    }

    protected ModuleClassLoader findModule(final String moduleName) {
        ModuleClassLoader loader = findDefinedModule(moduleName);
        if (loader != null) {
            return loader;
        } else {
            ModuleLoader delegate = delegateFn.apply(moduleName);
            if (delegate == null) {
                return null;
            }
            return delegate.findModule(moduleName);
        }
    }
}

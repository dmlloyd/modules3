package io.github.dmlloyd.modules;

import java.util.List;

import io.smallrye.common.constraint.Assert;

/**
 * Opening and loading information for a module that has been found by a module loader.
 *
 * @param loaderOpeners the resource loader openers (must not be {@code null})
 * @param descriptorLoader the descriptor loader (must not be {@code null})
 */
public record FoundModule(List<ResourceLoaderOpener> loaderOpeners, ModuleDescriptorLoader descriptorLoader) {

    /**
     * Construct a new instance.
     *
     * @param loaderOpeners the resource loader openers (must not be {@code null})
     * @param descriptorLoader the descriptor loader (must not be {@code null})
     */
    public FoundModule {
        Assert.checkNotNullParam("loaderOpeners", loaderOpeners);
        Assert.checkNotNullParam("descriptorLoader", descriptorLoader);
    }
}

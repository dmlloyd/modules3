package io.github.dmlloyd.modules;

import io.github.dmlloyd.modules.desc.Dependency;
import io.smallrye.common.constraint.Assert;

/**
 * A loaded dependency.
 */
record LoadedDependency(Dependency dependency, LoadedModule loadedModule) {
    LoadedDependency {
        Assert.checkNotNullParam("dependency", dependency);
        Assert.checkNotNullParam("loadedModule", loadedModule);
    }
}

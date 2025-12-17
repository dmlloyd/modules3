package io.github.dmlloyd.modules;

import io.github.dmlloyd.modules.desc.Dependency;

/**
 * A loaded dependency.
 */
record LoadedDependency(Dependency dependency, LoadedModule loadedModule) {
}

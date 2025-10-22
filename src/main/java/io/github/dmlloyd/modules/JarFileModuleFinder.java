package io.github.dmlloyd.modules;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.desc.PackageAccess;
import io.github.dmlloyd.modules.impl.TextIter;
import io.github.dmlloyd.modules.impl.Util;
import io.smallrye.common.resource.ResourceLoader;

/**
 * A module finder which finds a single module in the given JAR file.
 */
final class JarFileModuleFinder implements ModuleFinder {
    private final ResourceLoader jarLoader;
    private final ModuleDescriptor descriptor;
    private final ResourceLoaderOpener opener;

    JarFileModuleFinder(final ResourceLoader jarLoader, final String name, final Map<String, Map<String, PackageAccess>> extraAccesses) throws IOException {
        this.jarLoader = jarLoader;
        descriptor = ModuleDescriptorLoader.basic(extraAccesses).loadDescriptor(Util.autoModuleName(TextIter.of(name)), List.of(jarLoader));
        opener = ResourceLoaderOpener.forLoader(jarLoader);
    }

    public FoundModule findModule(final String name) {
        return name.equals(descriptor.name()) ? new FoundModule(List.of(opener), (moduleName, loaders) -> descriptor) : null;
    }

    public void close() {
        jarLoader.close();
    }

    ModuleDescriptor descriptor() {
        return descriptor;
    }
}

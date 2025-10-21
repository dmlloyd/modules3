package io.github.dmlloyd.modules;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.jar.Manifest;

import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.impl.TextIter;
import io.smallrye.common.resource.JarFileResourceLoader;
import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;

/**
 * A module finder which finds a single module in the given JAR file.
 */
final class JarFileModuleFinder implements ModuleFinder {
    private final ResourceLoader jarLoader;
    private final ModuleDescriptor descriptor;

    JarFileModuleFinder(final ResourceLoader jarLoader, final String name) throws IOException {
        this.jarLoader = jarLoader;
        descriptor = computeModuleDesc(jarLoader, name);
    }

    JarFileModuleFinder(final Path jarPath) throws IOException {
        this(new JarFileResourceLoader(jarPath), jarPath.getFileName().toString());
    }

    ModuleDescriptor computeModuleDesc(ResourceLoader loader, String name) throws IOException {
        // compute a default name
        TextIter nameIter = TextIter.of(name);
        String defaultName = Util.autoModuleName(nameIter);
        String defaultVersion = nameIter.hasNext() ? nameIter.substring() : null;
        if (defaultVersion != null
            && defaultVersion.endsWith("ar")
            && Character.isLetter(defaultVersion.charAt(defaultVersion.length() - 3))
            && defaultVersion.charAt(defaultVersion.length() - 4) == '.'
        ) {
            defaultVersion = defaultVersion.substring(0, defaultVersion.length() - 4);
        }
        List<ResourceLoader> loaderAsList = List.of(loader);
        // first, try for module-info
        Resource moduleInfo = loader.findResource("module-info.class");
        if (moduleInfo != null) {
            return ModuleDescriptor.fromModuleInfo(moduleInfo, loaderAsList);
        }
        // try to construct a descriptor from the manifest
        return ModuleDescriptor.fromManifest(
            defaultName,
            defaultVersion,
            Objects.requireNonNullElseGet(loader.manifest(), Manifest::new),
            loaderAsList
        );
    }

    public ModuleDescriptor findModule(final String name) {
        return name.equals(descriptor.name()) ? descriptor : null;
    }

    public void close() {
        jarLoader.close();
    }

    ModuleDescriptor descriptor() {
        return descriptor;
    }
}

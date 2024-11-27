package io.github.dmlloyd.modules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Manifest;

import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.desc.ResourceLoaderOpener;
import io.smallrye.common.resource.JarFileResourceLoader;
import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;

/**
 * A module finder which finds a single module in the given JAR file.
 */
final class JarFileModuleFinder implements ModuleFinder {
    private final ResourceLoader jarLoader;
    private final ModuleDescriptor descriptor;

    JarFileModuleFinder(final ResourceLoader jarLoader, final String name) {
        this.jarLoader = jarLoader;
        descriptor = computeModuleDesc(jarLoader).withResourceLoaders(List.of(ResourceLoaderOpener.forLoader(jarLoader))).withName(name);
    }

    JarFileModuleFinder(final Path jarPath) throws IOException {
        this(new JarFileResourceLoader(jarPath), jarPath.getFileName().toString());
    }

    static ModuleDescriptor computeModuleDesc(ResourceLoader loader) {
        // first, try for module-info
        try {
            Resource moduleInfo = loader.findResource("module-info.class");
            if (moduleInfo != null) {
                try (InputStream is = moduleInfo.openStream()) {
                    return ModuleDescriptor.fromModuleInfo(is.readAllBytes(), () -> {
                        throw new UnsupportedOperationException("Not supported yet");
                    });
                }
            }
        } catch (IOException ignored) {}
        // try to construct a descriptor from the manifest
        try {
            Manifest manifest = loader.manifest();
            if (manifest != null) {
                String autoName = manifest.getMainAttributes().getValue("Automatic-Module-Name");
                if (autoName != null) {
                    throw new UnsupportedOperationException("Not supported yet");
                }
            }
        } catch (IOException ignored) {}
        // fall back to a basic config
        throw new UnsupportedOperationException("Not supported yet");
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

package io.github.dmlloyd.modules;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.desc.PackageAccess;
import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;

/**
 * A loader for a module descriptor.
 */
public interface ModuleDescriptorLoader {
    /**
     * Load the descriptor for the module.
     *
     * @param moduleName the expected module name (not {@code null})
     * @param loaders the opened resource loaders (not {@code null})
     * @return the loaded descriptor (must not be {@code null})
     * @throws IOException if the descriptor could not be opened
     */
    ModuleDescriptor loadDescriptor(String moduleName, List<ResourceLoader> loaders) throws IOException;

    /**
     * {@return a module opener which looks for a {@code module-info.class} file, or otherwise constructs an automatic module, possibly with no dependencies}
     */
    static ModuleDescriptorLoader basic() {
        return ModuleDescriptorLoader::loadDescriptorBasic;
    }

    /**
     * {@return a module opener which looks for a {@code module-info.class} file, or otherwise constructs an automatic module, possibly with no dependencies}
     * @param extraAccesses extra accesses to add (must not be {@code null})
     */
    static ModuleDescriptorLoader basic(Map<String, Map<String, PackageAccess>> extraAccesses) {
        return (moduleName, loaders) -> loadDescriptorBasic(moduleName, loaders, extraAccesses);
    }

    private static ModuleDescriptor loadDescriptorBasic(String moduleName, List<ResourceLoader> loaders) throws IOException {
        return loadDescriptorBasic(moduleName, loaders, Map.of());
    }

    private static ModuleDescriptor loadDescriptorBasic(String moduleName, List<ResourceLoader> loaders, Map<String, Map<String, PackageAccess>> extraAccesses) throws IOException {
        for (ResourceLoader loader : loaders) {
            Resource resource = loader.findResource("module-info.class");
            if (resource != null) {
                return ModuleDescriptor.fromModuleInfo(resource, loaders, extraAccesses);
            }
        }
        // automatic module
        Manifest manifest = null;
        for (ResourceLoader loader : loaders) {
            manifest = loader.manifest();
            if (manifest != null) {
                break;
            }
        }
        if (manifest == null) {
            // blank manifest
            manifest = new Manifest();
        }
        return ModuleDescriptor.fromManifest(moduleName, null, manifest, loaders, extraAccesses);
    }

}

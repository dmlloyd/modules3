package io.github.dmlloyd.modules;

import static io.github.dmlloyd.modules.Util.*;

import java.net.URI;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.dmlloyd.modules.desc.Dependency;
import io.github.dmlloyd.modules.desc.Modifiers;
import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.desc.PackageInfo;
import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;

/**
 * The enclosing class for all link states.
 */
abstract class LinkState {
    private LinkState() {}

    /**
     * The singleton closed state.
     */
    static final class Closed extends LinkState {
        private Closed() {}

        static final Closed INSTANCE = new Closed();
    }

    static class Loaded extends LinkState {
        private final String moduleVersion;
        private final String mainClass;
        private final List<Dependency> dependencies;
        private final List<ResourceLoader> resourceLoaders;
        private final Map<String, PackageInfo> packages;
        private final Modifiers<ModuleDescriptor.Modifier> modifiers;
        private final Set<String> uses;
        private final Map<String, List<String>> provides;
        private final URI location;

        Loaded(final String moduleVersion, final String mainClass, final List<Dependency> dependencies, final List<ResourceLoader> resourceLoaders, final Map<String, PackageInfo> packages, final Modifiers<ModuleDescriptor.Modifier> modifiers, final Set<String> uses, final Map<String, List<String>> provides, final URI location) {
            this.moduleVersion = moduleVersion;
            this.mainClass = mainClass;
            this.dependencies = dependencies;
            this.resourceLoaders = resourceLoaders;
            this.packages = packages;
            this.modifiers = modifiers;
            this.uses = uses;
            this.provides = provides;
            this.location = location;
        }

        Loaded(final Loaded other) {
            this(other.moduleVersion, other.mainClass, other.dependencies, other.resourceLoaders, other.packages, other.modifiers, other.uses, other.provides, other.location);
        }

        List<Dependency> dependencies() {
            return dependencies;
        }

        List<ResourceLoader> resourceLoaders() {
            return resourceLoaders;
        }

        Map<String, PackageInfo> packages() {
            return packages;
        }

        Modifiers<ModuleDescriptor.Modifier> modifiers() {
            return modifiers;
        }

        Set<String> uses() {
            return uses;
        }

        Map<String, List<String>> provides() {
            return provides;
        }

        URI location() {
            return location;
        }
    }

    static class Dependencies extends Loaded {
        private final List<LoadedModule> loadedDependencies;

        Dependencies(final Loaded other, final List<LoadedModule> loadedDependencies) {
            super(other);
            this.loadedDependencies = loadedDependencies;
        }

        Dependencies(Dependencies other) {
            this(other, other.loadedDependencies);
        }

        List<LoadedModule> loadedDependencies() {
            return loadedDependencies;
        }
    }

    static class Defined extends Dependencies {
        private final Module module;
        private final ModuleLayer.Controller layerController;
        private final Set<String> exportedPackages;
        private final ConcurrentHashMap<List<CodeSigner>, ProtectionDomain> pdCache;

        private Defined(
            final Dependencies other,
            final Module module,
            final ModuleLayer.Controller layerController,
            final Set<String> exportedPackages,
            final ConcurrentHashMap<List<CodeSigner>, ProtectionDomain> pdCache
        ) {
            super(other);
            this.module = module;
            this.layerController = layerController;
            this.exportedPackages = exportedPackages;
            this.pdCache = pdCache;
        }

        Defined(
            final Dependencies other,
            final Module module,
            final ModuleLayer.Controller layerController,
            final Set<String> exportedPackages
        ) {
            this(other, module, layerController, exportedPackages, new ConcurrentHashMap<>());
            myModule.addReads(module);
        }

        Defined(final Defined other) {
            this(other, other.module, other.layerController, other.exportedPackages, other.pdCache);
        }

        Module module() {
            return module;
        }

        void addReads(final Module target) {
            if (layerController != null) {
                layerController.addReads(module, target);
            }
        }

        void addExports(final String pn, final Module target) {
            if (layerController != null) {
                try {
                    layerController.addExports(module, pn, target);
                } catch (IllegalArgumentException e) {
                    IllegalArgumentException e2 = new IllegalArgumentException("Failed to export " + module + " to " + target + ": " + e.getMessage());
                    e2.setStackTrace(e.getStackTrace());
                    throw e2;
                }
            }
        }

        void addOpens(final String pn, final Module target) {
            if (layerController != null) {
                try {
                    layerController.addOpens(module, pn, target);
                } catch (IllegalArgumentException e) {
                    IllegalArgumentException e2 = new IllegalArgumentException("Failed to open " + module + " to " + target + ": " + e.getMessage());
                    e2.setStackTrace(e.getStackTrace());
                    throw e2;
                }
            }
        }

        void addUses(final Class<?> service) {
            if (layerController != null) {
                Util.addUses(module, service);
            }
        }

        void addProvider(final Class<?> service, final Class<?> impl) {
            if (layerController != null) {
                Util.addProvides(module, service, impl);
            }
        }

        Set<String> exportedPackages() {
            return exportedPackages;
        }

        ProtectionDomain cachedProtectionDomain(final Resource resource) {
            List<CodeSigner> codeSigners = List.copyOf(resource.codeSigners());
            ProtectionDomain pd = pdCache.get(codeSigners);
            if (pd == null) {
                pd = new ProtectionDomain(new CodeSource(resource.url(), codeSigners.toArray(CodeSigner[]::new)), allPermissions);
                ProtectionDomain appearing = pdCache.putIfAbsent(codeSigners, pd);
                if (appearing != null) {
                    pd = appearing;
                }
            }
            return pd;
        }
    }

    static class Packages extends Defined {
        private final Map<String, Module> modulesByPackage;

        Packages(Defined other, final Map<String, Module> modulesByPackage) {
            super(other);
            this.modulesByPackage = modulesByPackage;
        }

        Packages(Packages other) {
            this(other, other.modulesByPackage);
        }

        Map<String, Module> modulesByPackage() {
            return modulesByPackage;
        }
    }

    static class Provides extends Packages {
        Provides(Packages other) {
            super(other);
        }

        Provides(Provides other) {
            this((Packages) other);
        }
    }

    static class Uses extends Provides {
        Uses(Provides other) {
            super(other);
        }

        Uses(Uses other) {
            this((Provides) other);
        }
    }
}

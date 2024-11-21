package io.github.dmlloyd.modules;

import java.net.URI;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.dmlloyd.modules.desc.Dependency;
import io.github.dmlloyd.modules.desc.Export;
import io.github.dmlloyd.modules.desc.Modifiers;
import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.desc.Open;
import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;

/**
 * The enclosing class for all link states.
 */
abstract class LinkState {
    private LinkState() {}

    static final class Closed extends LinkState {
        private Closed() {}

        static final Closed INSTANCE = new Closed();
    }

    static class Initial extends LinkState {
        private final List<Dependency> dependencies;
        private final List<ResourceLoader> resourceLoaders;
        private final Set<Export> exports;
        private final Set<Open> opens;
        private final Set<String> packages;
        private final Modifiers<ModuleDescriptor.Modifier> modifiers;
        private final Set<String> uses;
        private final Map<String, List<String>> provides;
        private final URI location;

        Initial(final List<Dependency> dependencies, final List<ResourceLoader> resourceLoaders, final Set<Export> exports, final Set<Open> opens, final Set<String> packages, final Modifiers<ModuleDescriptor.Modifier> modifiers, final Set<String> uses, final Map<String, List<String>> provides, final URI location) {
            this.dependencies = dependencies;
            this.resourceLoaders = resourceLoaders;
            this.exports = exports;
            this.opens = opens;
            this.packages = packages;
            this.modifiers = modifiers;
            this.uses = uses;
            this.provides = provides;
            this.location = location;
        }

        Initial(final Initial other) {
            this(other.dependencies, other.resourceLoaders, other.exports, other.opens, other.packages, other.modifiers, other.uses, other.provides, other.location);
        }

        List<Dependency> dependencies() {
            return dependencies;
        }

        List<ResourceLoader> resourceLoaders() {
            return resourceLoaders;
        }

        Set<Export> exports() {
            return exports;
        }

        Set<Open> opens() {
            return opens;
        }

        Set<String> packages() {
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

    static class Dependencies extends Initial {
        private final List<LoadedModule> loadedDependencies;
        private final Map<String, List<String>> providedServices;

        Dependencies(final Initial other, final List<LoadedModule> loadedDependencies, final Map<String, List<String>> providedServices) {
            super(other);
            this.loadedDependencies = loadedDependencies;
            this.providedServices = providedServices;
        }

        Dependencies(Dependencies other) {
            this(other, other.loadedDependencies, other.providedServices);
        }

        List<LoadedModule> loadedDependencies() {
            return loadedDependencies;
        }

        Map<String, List<String>> providedServices() {
            return providedServices;
        }
    }

    static class Defined extends Dependencies {
        private final Module module;
        private final ModuleLayer.Controller layerController;
        private final Set<String> exportedPackages;

        Defined(
            final Dependencies other,
            final Module module,
            final ModuleLayer.Controller layerController,
            final Set<String> exportedPackages
        ) {
            super(other);
            this.module = module;
            this.layerController = layerController;
            this.exportedPackages = exportedPackages;
        }

        Defined(final Defined other) {
            this(other, other.module, other.layerController, other.exportedPackages);
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
                layerController.addExports(module, pn, target);
            }
        }

        void addOpens(final String pn, final Module target) {
            if (layerController != null) {
                layerController.addOpens(module, pn, target);
            }
        }

        void addUses(final Class<?> service) {
            try {
                module.getClassLoader().loadClass("$internal.Utils").getDeclaredMethod("use", Class.class).invoke(null, service);
            } catch (Exception e) {
                throw new IllegalStateException("Unexpected failure", e);
            }
        }

        Set<String> exportedPackages() {
            return exportedPackages;
        }
    }

    static class Linked extends Defined {
        private final Map<String, Module> modulesByPackage;
        private final ConcurrentHashMap<List<CodeSigner>, ProtectionDomain> pdCache = new ConcurrentHashMap<>();

        Linked(Defined other, final Map<String, Module> modulesByPackage) {
            super(other);
            this.modulesByPackage = modulesByPackage;
        }

        Linked(Linked other) {
            this(other, other.modulesByPackage);
        }

        Map<String, Module> modulesByPackage() {
            return modulesByPackage;
        }

        ProtectionDomain cachedProtectionDomain(final Resource resource) {
            List<CodeSigner> codeSigners = List.copyOf(resource.codeSigners());
            ProtectionDomain pd = pdCache.get(codeSigners);
            if (pd == null) {
                pd = new ProtectionDomain(new CodeSource(resource.url(), codeSigners.toArray(CodeSigner[]::new)), Util.ALL_PERMISSIONS);
                ProtectionDomain appearing = pdCache.putIfAbsent(codeSigners, pd);
                if (appearing != null) {
                    pd = appearing;
                }
            }
            return pd;
        }
    }
}

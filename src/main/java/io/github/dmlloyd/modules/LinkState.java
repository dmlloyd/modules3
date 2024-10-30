package io.github.dmlloyd.modules;

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
import io.github.dmlloyd.modules.desc.Provide;
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
        private final Set<Provide> provides;

        Initial(final List<Dependency> dependencies, final List<ResourceLoader> resourceLoaders, final Set<Export> exports, final Set<Open> opens, final Set<String> packages, final Modifiers<ModuleDescriptor.Modifier> modifiers, final Set<String> uses, final Set<Provide> provides) {
            this.dependencies = dependencies;
            this.resourceLoaders = resourceLoaders;
            this.exports = exports;
            this.opens = opens;
            this.packages = packages;
            this.modifiers = modifiers;
            this.uses = uses;
            this.provides = provides;
        }

        Initial(final Initial initial) {
            this(initial.dependencies, initial.resourceLoaders, initial.exports, initial.opens, initial.packages, initial.modifiers, initial.uses, initial.provides);
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

        Set<Provide> provides() {
            return provides;
        }
    }

    static class Defined extends Initial {
        private final Module module;
        private final ModuleLayer.Controller layerController;
        private final Set<String> exportedPackages;

        Defined(
            final Initial initial,
            final Module module,
            final ModuleLayer.Controller layerController,
            final Set<String> exportedPackages
        ) {
            super(initial);
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

        ModuleLayer.Controller layerController() {
            return layerController;
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

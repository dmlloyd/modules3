package io.github.dmlloyd.modules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.constant.ClassDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.dmlloyd.classfile.ClassFile;
import io.github.dmlloyd.classfile.attribute.ModuleAttribute;
import io.github.dmlloyd.classfile.attribute.ModulePackagesAttribute;
import io.github.dmlloyd.classfile.extras.constant.ConstantUtils;
import io.github.dmlloyd.classfile.extras.constant.ModuleDesc;
import io.github.dmlloyd.classfile.extras.constant.PackageDesc;
import io.github.dmlloyd.classfile.extras.reflect.AccessFlag;
import io.github.dmlloyd.modules.desc.Dependency;
import io.github.dmlloyd.modules.desc.Modifiers;
import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.desc.PackageAccess;
import io.smallrye.common.resource.MemoryResource;
import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;
import org.jboss.logging.Logger;

/**
 * A class loader for a module.
 */
public class ModuleClassLoader extends ClassLoader {
    public static final ClassDesc CD_Module = ClassDesc.of("java.lang.Module");


    static {
        if (! ClassLoader.registerAsParallelCapable()) {
            throw new InternalError("Class loader cannot be made parallel-capable");
        }
    }

    private static final Map<String, Module> bootModuleIndex = ModuleLayer.boot().modules().stream()
        .flatMap(m -> m.getPackages().stream().filter(p -> m.isExported(p, Util.myModule)).map(p -> Map.entry(p, m)))
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private final String moduleName;
    private final String moduleVersion;
    private final ModuleLoader moduleLoader;
    private final String mainClassName;
    private final Set<ModuleLayer> registeredLayers = ConcurrentHashMap.newKeySet();

    /**
     * The lock used for certain linking operations.
     * No other lock should ever be acquired while holding this lock,
     * including the lock(s) of other instances of this class.
     */
    private final ReentrantLock linkLock = new ReentrantLock();

    private volatile LinkState linkState;
    private final Controller controller = new Controller();

    /**
     * Construct a new instance.
     *
     * @param config the configuration (must not be {@code null})
     */
    public ModuleClassLoader(ClassLoaderConfiguration config) {
        super(config.classLoaderName(), null);
        if (! isRegisteredAsParallelCapable()) {
            throw new IllegalStateException("Class loader is not registered as parallel-capable");
        }
        this.moduleLoader = config.moduleLoader();
        this.moduleName = config.moduleName();
        this.moduleVersion = config.moduleVersion();
        this.mainClassName = config.mainClassName();
        this.linkState = new LinkState.Initial(
            config.dependencies(),
            config.resourceLoaders(),
            config.packages(),
            config.modifiers(),
            config.uses(),
            config.provides(),
            config.location()
        );
    }

    /**
     * {@return the name of this class loader}
     */
    public final String getName() {
        return super.getName();
    }

    /**
     * {@return the defining module loader of this module}
     */
    public final ModuleLoader moduleLoader() {
        return moduleLoader;
    }

    /**
     * {@return the module loaded by this class loader}
     */
    public final Module module() {
        return linkDefined().module();
    }

    @Override
    public final Class<?> loadClass(final String name) throws ClassNotFoundException {
        return loadClass(name, false);
    }

    @Override
    protected final Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("[")) {
            return loadClassFromDescriptor(name, 0);
        }
        String dotName = name.replace('/', '.');
        String packageName = Util.packageName(dotName);
        if (packageName.isEmpty() || linkInitial().packages().containsKey(packageName)) {
            return loadClassDirect(name);
        }
        if (bootModuleIndex.containsKey(packageName)) {
            if (dotName.equals("java.util.ServiceLoader")) {
                // loading services! extra linking required
                linkUses();
            }
            // -> BootLoader.loadClass(...)
            Class<?> result = Class.forName(bootModuleIndex.get(packageName), dotName);
            if (result != null) {
                return result;
            } else {
                throw new ClassNotFoundException("Cannot find " + name + " from " + this);
            }
        }
        Module module = linkPackages().modulesByPackage().get(packageName);
        if (module == null) {
            throw new ClassNotFoundException("Class loader for " + this + " does not link against package `" + packageName + "`");
        }
        Class<?> loaded;
        ClassLoader cl = module.getClassLoader();
        if (cl == this) {
            loaded = loadClassDirect(dotName);
        } else {
            if (module.isExported(packageName, module())) {
                if (cl instanceof ModuleClassLoader mcl) {
                    loaded = mcl.loadClassDirect(dotName);
                } else {
                    loaded = Class.forName(dotName, false, cl);
                }
            } else {
                throw new ClassNotFoundException("Module " + module.getName() + " does not export package " + packageName + " to " + module().getName());
            }
        }
        if (resolve) {
            // note: this is a no-op in OpenJDK
            resolveClass(loaded);
        }
        return loaded;
    }

    public final Resource getExportedResource(final String name) {
        // todo: filter to exportable resources
        try {
            return getExportedResource(name, stackWalker.walk(callerFinder));
        } catch (IOException e) {
            return null;
        }
    }

    public final URL getResource(final String name) {
        Resource resource;
        try {
            resource = getExportedResource(name, stackWalker.walk(callerFinder));
        } catch (IOException e) {
            return null;
        }
        return resource == null ? null : resource.url();
    }

    @Override
    public final InputStream getResourceAsStream(final String name) {
        Resource resource;
        try {
            resource = getExportedResource(name, stackWalker.walk(callerFinder));
            return resource == null ? null : resource.openStream();
        } catch (IOException e) {
            return null;
        }
    }

    public final List<Resource> getExportedResources(final String name) throws IOException {
        return getExportedResources(name, stackWalker.walk(callerFinder));
    }

    @Override
    public final Enumeration<URL> getResources(final String name) throws IOException {
        // todo: filter to exportable resources
        List<Resource> resources = loadResourcesDirect(name);
        Iterator<Resource> iterator = resources.iterator();
        return new Enumeration<URL>() {
            public boolean hasMoreElements() {
                return iterator.hasNext();
            }

            public URL nextElement() {
                return iterator.next().url();
            }
        };
    }

    @Override
    public final Stream<URL> resources(final String name) {
        // todo: filter to exportable resources
        try {
            return loadResourcesDirect(name).stream().map(Resource::url);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public final Set<String> exportedPackages() {
        return linkPackages().exportedPackages();
    }

    /**
     * {@return the module class loader of the calling class, or {@code null} if the calling class does not have one}
     */
    public static ModuleClassLoader current() {
        return stackWalker.getCallerClass().getClassLoader() instanceof ModuleClassLoader mcl ? mcl : null;
    }

    /**
     * {@return the module class loader of the given module, or {@code null} if it does not have one}
     */
    public static ModuleClassLoader forModule(Module module) {
        return module.getClassLoader() instanceof ModuleClassLoader mcl ? mcl : null;
    }

    public String toString() {
        return "ModuleClassLoader[" + moduleName + "]";
    }

    // special


    protected <R> R resolving(Function<String, Class<?>> resolver, Supplier<R> action) {
        throw new UnsupportedOperationException("TODO");
    }

    // private

    /**
     * Get a resource from an exported and open package.
     *
     * @param name the resource name (must not be {@code null})
     * @param caller the caller's class (must not be {@code null})
     * @return the resource or {@code null} if it is not available to {@code caller}
     * @throws IOException if an error occurs while getting the resource
     */
    private Resource getExportedResource(final String name, Class<?> caller) throws IOException {
        // loading the resource will canonicalize its path for us
        Resource resource = loadResourceDirect(name);
        if (resource == null) {
            return null;
        }
        String pathName = resource.pathName();
        if (pathName.endsWith(".class")) {
            return resource;
        }
        if (caller == null || caller.getModule().getClassLoader() == this) {
            return resource;
        }
        String pkgName = Util.resourcePackageName(pathName);
        if (pkgName.isEmpty() || ! linkDefined().packages().containsKey(pkgName)) {
            return resource;
        }
        if (linkDefined().exportedPackages().contains(pkgName)) {
            return resource;
        }
        if (module().isOpen(pkgName, caller.getModule())) {
            return resource;
        }
        // no access
        return null;
    }

    private List<Resource> getExportedResources(final String name, final Class<?> caller) throws IOException {
        List<Resource> resources = loadResourcesDirect(name);
        if (resources.isEmpty()) {
            return List.of();
        }
        String pathName = resources.get(0).pathName();
        if (pathName.endsWith(".class")) {
            return resources;
        }
        if (caller == null || caller.getModule().getClassLoader() == this) {
            return resources;
        }
        String pkgName = Util.resourcePackageName(pathName);
        if (pkgName.isEmpty() || ! linkDefined().packages().containsKey(pkgName)) {
            return resources;
        }
        if (linkDefined().exportedPackages().contains(pkgName)) {
            return resources;
        }
        if (module().isOpen(pkgName, caller.getModule())) {
            return resources;
        }
        // no access
        return null;
    }

    // direct loaders

    /**
     * Load a class directly from this class loader.
     *
     * @param name the dot-separated ("binary") name of the class to load (must not be {@code null})
     * @return the loaded class (not {@code null})
     * @throws ClassNotFoundException if the class is not found in this class loader
     */
    final Class<?> loadClassDirect(String name) throws ClassNotFoundException {
        String dotName = name.replace('/', '.');
        Class<?> loaded = findLoadedClass(dotName);
        if (loaded != null) {
            return loaded;
        }
        LinkState.Defined linked = linkDefined();
        String packageName = Util.packageName(dotName);
        if (! packageName.isEmpty() && ! linked.packages().containsKey(packageName)) {
            throw new ClassNotFoundException("Class `" + name + "` is not in a package that is reachable from " + moduleName);
        }

        String fullPath = name.replace('.', '/') + ".class";
        try {
            Resource resource = loadResourceDirect(fullPath);
            if (resource != null) {
                // found it!
                ProtectionDomain pd = linked.cachedProtectionDomain(resource);
                return defineOrGetClass(dotName, resource, pd);
            }
        } catch (IOException e) {
            throw new ClassNotFoundException("Failed to load " + dotName, e);
        }
        throw new ClassNotFoundException("Class `" + name + "` is not found in " + moduleName);
    }

    final Resource loadResourceDirect(final String name) throws IOException {
        // TODO: canonicalize
        if (name.equals("module-info.class")) {
            // this is always loaded as a resource
            return loadModuleInfo();
        }
        if (isServiceFileName(name)) {
            return loadServicesFileDirect(name);
        }
        for (ResourceLoader loader : linkInitial().resourceLoaders()) {
            Resource resource = loader.findResource(name);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    private static boolean isServiceFileName(final String name) {
        return name.startsWith("META-INF/services/") && name.lastIndexOf('/') == 17;
    }

    final List<Resource> loadResourcesDirect(final String name) throws IOException {
        // TODO: canonicalize
        if (name.equals("module-info.class")) {
            // this is always loaded as a resource
            return Optional.ofNullable(loadModuleInfo()).map(List::of).orElse(List.of());
        }
        if (isServiceFileName(name)) {
            return Optional.ofNullable(loadServicesFileDirect(name)).map(List::of).orElse(List.of());
        }
        try {
            return linkInitial().resourceLoaders().stream().map(l -> {
                try {
                    return l.findResource(name);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).filter(Objects::nonNull).toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private Resource loadServicesFileDirect(final String name) {
        List<String> services = linkDependencies().provides().getOrDefault(name.substring(18), List.of());
        if (services.isEmpty()) {
            return null;
        }
        String result = services.stream().collect(Collectors.joining("\n", "", "\n"));
        return new MemoryResource(name, result.getBytes(StandardCharsets.UTF_8));
    }

    private static String getAttribute(Attributes.Name name, Attributes packageAttribute, Attributes mainAttribute, String defVal) {
        String value = null;
        if (packageAttribute != null) {
            value = packageAttribute.getValue(name);
        }
        if (value == null && mainAttribute != null) {
            value = mainAttribute.getValue(name);
        }
        if (value == null) {
            value = defVal;
        }
        return value;
    }

    final Package loadPackageDirect(final String name) {
        Package pkg = getDefinedPackage(name);
        if (pkg != null) {
            return pkg;
        }
        Manifest manifest = null;
        ResourceLoader loader = null;
        List<ResourceLoader> list = linkDefined().resourceLoaders();
        for (ResourceLoader rl : list) {
            try {
                manifest = rl.manifest();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load manifest for package " + name, e);
            }
            if (manifest != null) {
                loader = rl;
                break;
            }
        }
        // todo: change this to use the manifest of the JAR which contains the package
        String specTitle;
        String specVersion;
        String specVendor;
        String implTitle;
        String implVersion;
        String implVendor;
        boolean sealed;
        if (manifest == null) {
            specTitle = null;
            specVersion = null;
            specVendor = null;
            implTitle = moduleName;
            implVersion = moduleVersion;
            implVendor = null;
            sealed = false;
        } else {
            Attributes ma = manifest.getMainAttributes();
            String path = name.replace('.', '/') + '/';
            Attributes pa = manifest.getAttributes(path);
            specTitle = getAttribute(Attributes.Name.SPECIFICATION_TITLE, pa, ma, null);
            specVersion = getAttribute(Attributes.Name.SPECIFICATION_VERSION, pa, ma, null);
            specVendor = getAttribute(Attributes.Name.SPECIFICATION_VENDOR, pa, ma, null);
            implTitle = getAttribute(Attributes.Name.IMPLEMENTATION_TITLE, pa, ma, moduleName);
            implVersion = getAttribute(Attributes.Name.IMPLEMENTATION_VERSION, pa, ma, moduleVersion);
            implVendor = getAttribute(Attributes.Name.IMPLEMENTATION_VENDOR, pa, ma, null);
            sealed = Boolean.parseBoolean(getAttribute(Attributes.Name.SEALED, pa, ma, "false"));
        }
        try {
            return definePackage(
                name,
                specTitle,
                specVersion,
                specVendor,
                implTitle,
                implVersion,
                implVendor,
                sealed ? loader.baseUrl() : null
            );
        } catch (IllegalArgumentException e) {
            // double check
            pkg = getDefinedPackage(name);
            if (pkg != null) {
                return pkg;
            }
            throw e;
        }
    }

    // linking

    private boolean locked() {
        return linkLock.isHeldByCurrentThread();
    }

    private <O> O doLocked(Function<ModuleClassLoader, O> operation) {
        assert ! locked();
        ReentrantLock lock = linkLock;
        lock.lock();
        try {
            return operation.apply(this);
        } finally {
            lock.unlock();
        }
    }

    private <I, O> O doLocked(BiFunction<ModuleClassLoader, I, O> operation, I input) {
        assert ! locked();
        ReentrantLock lock = linkLock;
        lock.lock();
        try {
            return operation.apply(this, input);
        } finally {
            lock.unlock();
        }
    }

    LinkState.Initial linkInitial() {
        LinkState linkState = this.linkState;
        if (linkState instanceof LinkState.Initial state) {
            return state;
        }
        assert linkState == LinkState.Closed.INSTANCE;
        throw new IllegalStateException("Module " + moduleName + " has been unloaded");
    }

    LinkState.Dependencies linkDependencies() {
        LinkState.Initial linkState = linkInitial();
        if (linkState instanceof LinkState.Dependencies deps) {
            return deps;
        }
        List<LoadedModule> loadedDependencies = linkState.dependencies().stream()
            .map(d -> {
                ModuleLoader ml = d.moduleLoader().orElse(moduleLoader);
                if (d.modifiers().contains(Dependency.Modifier.OPTIONAL)) {
                    return ml.loadModule(d.moduleName());
                } else {
                    try {
                        return ml.requireModule(d.moduleName());
                    } catch (ModuleLoadException e) {
                        throw e.withMessage(e.getMessage() + " (required by " + moduleName + ")");
                    }
                }
            })
            .filter(Objects::nonNull)
            .toList();
        return doLocked(ModuleClassLoader::linkDependenciesLocked, loadedDependencies);
    }

    private LinkState.Dependencies linkDependenciesLocked(List<LoadedModule> loadedDependencies) {
        LinkState.Initial linkState = linkInitial();
        if (linkState instanceof LinkState.Dependencies deps) {
            return deps;
        }
        LinkState.Dependencies newState = new LinkState.Dependencies(linkState, loadedDependencies);
        this.linkState = newState;
        return newState;
    }

    private static Set<java.lang.module.ModuleDescriptor.Modifier> toJlmModifiers(Modifiers<ModuleDescriptor.Modifier> modifiers) {
        if (modifiers.contains(ModuleDescriptor.Modifier.AUTOMATIC)) {
            return Set.of(java.lang.module.ModuleDescriptor.Modifier.AUTOMATIC);
        } else if (modifiers.contains(ModuleDescriptor.Modifier.OPEN)) {
            return Set.of(java.lang.module.ModuleDescriptor.Modifier.OPEN);
        } else {
            return Set.of();
        }
    }

    private static final Set<java.lang.module.ModuleDescriptor.Requires.Modifier> justStatic = Set.of(
        java.lang.module.ModuleDescriptor.Requires.Modifier.STATIC
    );

    private LinkState.Defined linkDefined() {
        LinkState.Initial linkState = linkDependencies();
        if (linkState instanceof LinkState.Defined defined) {
            return defined;
        }
        java.lang.module.ModuleDescriptor descriptor;
        if (linkState.modifiers().contains(ModuleDescriptor.Modifier.UNNAMED)) {
            descriptor = null;
        } else {
            java.lang.module.ModuleDescriptor.Builder builder = java.lang.module.ModuleDescriptor.newModule(
                moduleName,
                toJlmModifiers(linkState.modifiers())
            );
            try {
                java.lang.module.ModuleDescriptor.Version v = java.lang.module.ModuleDescriptor.Version.parse(moduleVersion);
                builder.version(v);
            } catch (IllegalArgumentException ignored) {
            }
            builder.packages(linkState.packages().keySet());
            if (mainClassName != null) {
                // not actually used, but for completeness...
                builder.mainClass(mainClassName);
            }
            if (! linkState.modifiers().contains(ModuleDescriptor.Modifier.AUTOMATIC)) {
                linkState.packages().forEach((name, pkg) -> {
                    switch (pkg.packageAccess()) {
                        case EXPORTED -> builder.exports(name);
                        case OPEN -> builder.opens(name);
                    }
                });
                linkState.dependencies().forEach(d -> builder.requires(justStatic, d.moduleName()));
            }
            descriptor = builder.build();
        }
        return doLocked(ModuleClassLoader::linkDefinedLocked, descriptor);
    }

    private LinkState.Defined linkDefinedLocked(java.lang.module.ModuleDescriptor descriptor) {
        LinkState.Dependencies linkState = linkDependencies();
        if (linkState instanceof LinkState.Defined defined) {
            return defined;
        }
        log.debugf("Linking module %s to defined state", moduleName);
        Set<String> exportedPackages = linkState.packages().entrySet().stream().filter(e -> e.getValue().packageAccess().isAtLeast(PackageAccess.EXPORTED)).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
        LinkState.Defined defined;
        if (linkState.modifiers().contains(ModuleDescriptor.Modifier.UNNAMED)) {
            // nothing needed
            defined = new LinkState.Defined(linkState, getUnnamedModule(), null, exportedPackages);
        } else {
            // all the stuff that the JDK needs to have a module
            URI uri = linkState.location();
            ModuleReference modRef = new ModuleReference(descriptor, uri) {
                public ModuleReader open() {
                    throw new UnsupportedOperationException();
                }
            };
            List<Configuration> parentConfigs = List.of(ModuleLayer.boot().configuration());
            final Configuration cf = Configuration.resolve(
                new SingleModuleFinder(modRef),
                parentConfigs,
                Util.EMPTY_MF,
                List.of(moduleName)
            );
            ModuleLayer.Controller ctl = ModuleLayer.defineModules(cf, List.of(ModuleLayer.boot()), name -> {
                if (name.equals(moduleName)) {
                    return this;
                } else {
                    throw new IllegalStateException("Wrong module name: " + name + " (expected " + moduleName + ")");
                }
            });
            ModuleLayer moduleLayer = ctl.layer();
            Module module = moduleLayer.findModule(moduleName).orElseThrow(IllegalStateException::new);
            if (linkState.modifiers().contains(ModuleDescriptor.Modifier.NATIVE_ACCESS)) {
                Util.enableNativeAccess(ctl, module);
            }
            defined = new LinkState.Defined(
                linkState,
                module,
                ctl,
                exportedPackages
            );
            defined.addReads(Util.myModule);
            Util.myModule.addReads(module);
        }
        this.linkState = defined;
        return defined;
    }

    private void linkExportedPackages(LinkState.Defined linkState, LoadedModule loaded, Map<String, Module> modulesByPackage, Set<LoadedModule> visited) {
        if (visited.add(loaded)) {
            linkState.addReads(loaded.module());
            if (loaded.classLoader() instanceof ModuleClassLoader mcl) {
                Set<String> packages = mcl.linkInitial().packages().keySet();
                for (String pkg : packages) {
                    if (mcl.module().isExported(pkg, linkState.module())) {
                        modulesByPackage.putIfAbsent(pkg, mcl.module());
                    }
                }
                for (Dependency dependency : mcl.linkInitial().dependencies()) {
                    if (dependency.modifiers().contains(Dependency.Modifier.TRANSITIVE)) {
                        LoadedModule dep = dependency.moduleLoader().orElse(mcl.moduleLoader()).loadModule(dependency.moduleName());
                        if (dep == null) {
                            if (dependency.modifiers().contains(Dependency.Modifier.OPTIONAL)) {
                                continue;
                            }
                            throw new ModuleLoadException("Failed to link " + moduleName + ": dependency from " + mcl.moduleName
                                + " to " + dependency.moduleName() + " is missing");
                        }
                        linkExportedPackages(linkState, dep, modulesByPackage, visited);
                    }
                }
            } else {
                Module module = loaded.module();
                Set<String> packages = module.getPackages();
                for (String pkg : packages) {
                    if (module.isExported(pkg, linkState.module())) {
                        modulesByPackage.putIfAbsent(pkg, module);
                    }
                }
                java.lang.module.ModuleDescriptor descriptor = module.getDescriptor();
                if (descriptor != null) {
                    for (java.lang.module.ModuleDescriptor.Requires require : descriptor.requires()) {
                        if (require.modifiers().contains(java.lang.module.ModuleDescriptor.Requires.Modifier.TRANSITIVE)) {
                            Optional<Module> optDep = module.getLayer().findModule(require.name());
                            if (optDep.isEmpty()) {
                                if (require.modifiers().contains(java.lang.module.ModuleDescriptor.Requires.Modifier.STATIC)) {
                                    continue;
                                }
                                throw new ModuleLoadException("Failed to link " + moduleName + ": dependency from " + module.getName()
                                    + " to " + require.name() + " is missing");
                            }
                            linkExportedPackages(linkState, LoadedModule.forModule(optDep.get()), modulesByPackage, visited);
                        }
                    }
                }
            }
        }
    }

    private LinkState.Packages linkPackages() {
        LinkState.Defined linkState = linkDefined();
        if (linkState instanceof LinkState.Packages linked) {
            return linked;
        }
        HashSet<LoadedModule> visited = new HashSet<>();
        HashMap<String, Module> modulesByPackage = new HashMap<>();
        for (Dependency dependency : linkState.dependencies()) {
            String depName = dependency.moduleName();
            LoadedModule lm = dependency.moduleLoader().orElse(moduleLoader()).loadModule(depName);
            if (lm == null) {
                if (dependency.modifiers().contains(Dependency.Modifier.OPTIONAL)) {
                    continue;
                }
                throw new ModuleNotFoundException("Cannot resolve dependency " + depName + " of " + moduleName);
            }
            Module module = lm.module();
            linkState.addReads(module);
            // skip boot modules for memory efficiency
            if (! ModuleLayer.boot().modules().contains(module)) {
                linkExportedPackages(linkState, lm, modulesByPackage, visited);
            }
            for (Map.Entry<String, PackageAccess> entry : dependency.packageAccesses().entrySet()) {
                switch (entry.getValue()) {
                    case EXPORTED -> {
                        if (lm.classLoader() instanceof ModuleClassLoader mcl) {
                            mcl.linkDefined().addExports(entry.getKey(), module);
                        } else {
                            // might not work!
                            lm.module().addExports(entry.getKey(), module);
                        }
                    }
                    case OPEN -> {
                        if (lm.classLoader() instanceof ModuleClassLoader mcl) {
                            mcl.linkDefined().addOpens(entry.getKey(), module);
                        } else {
                            // might not work!
                            lm.module().addOpens(entry.getKey(), module);
                        }
                    }
                }
            }
        }
        // and don't forget our own packages
        for (String pkg : linkState.packages().keySet()) {
            modulesByPackage.put(pkg, linkState.module());
        }
        // link up directed exports and opens
        for (Map.Entry<String, io.github.dmlloyd.modules.desc.Package> entry : linkState.packages().entrySet()) {
            for (String target : entry.getValue().exportTargets()) {
                LoadedModule resolved = moduleLoader().doLoadModule(target);
                if (resolved != null) {
                    linkState.addExports(entry.getKey(), resolved.module());
                }
            }
            for (String target : entry.getValue().openTargets()) {
                LoadedModule resolved = moduleLoader().doLoadModule(target);
                if (resolved != null) {
                    linkState.addOpens(entry.getKey(), resolved.module());
                }
            }
        }
        // last, register service loader links
        for (LoadedModule dep : linkState.loadedDependencies()) {
            ModuleLayer layer = dep.module().getLayer();
            if (layer != ModuleLayer.boot()) {
                registerLayer(layer);
            }
        }
        return doLocked(ModuleClassLoader::linkPackagesLocked, modulesByPackage);
    }

    private LinkState.Packages linkPackagesLocked(final Map<String, Module> modulesByPackage) {
        // double-check it inside the lock
        LinkState.Defined defined = linkDefined();
        if (defined instanceof LinkState.Packages linked) {
            return linked;
        }
        log.debugf("Linking module %s to packages state", moduleName);
        LinkState.Packages linked = new LinkState.Packages(
            defined,
            Map.copyOf(modulesByPackage)
        );
        linkState = linked;
        return linked;
    }

    LinkState.Provides linkProvides() {
        LinkState.Packages linkState = linkPackages();
        if (linkState instanceof LinkState.Provides st) {
            return st;
        }
        for (String used : linkState.uses()) {
            try {
                linkState.addUses(loadClass(used));
            } catch (ClassNotFoundException ignored) {
            }
        }
        // define provided services
        for (Map.Entry<String, List<String>> entry : linkState.provides().entrySet()) {
            Class<?> service;
            try {
                service = loadClass(entry.getKey());
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (String implName : entry.getValue()) {
                Class<?> impl;
                try {
                    impl = loadClassDirect(implName);
                } catch (ClassNotFoundException e) {
                    continue;
                }
                linkState.addProvider(service, impl);
            }
        }
        return doLocked(ModuleClassLoader::linkProvidesLocked);
    }

    private LinkState.Provides linkProvidesLocked() {
        // double-check it inside the lock
        LinkState.Packages linkState = linkPackages();
        if (linkState instanceof LinkState.Provides st) {
            return st;
        }
        log.debugf("Linking module %s to provides state", moduleName);
        LinkState.Provides newState = new LinkState.Provides(
            linkState
        );
        this.linkState = newState;
        return newState;
    }

    LinkState.Uses linkUses() {
        LinkState.Provides linkState = linkProvides();
        if (linkState instanceof LinkState.Uses st) {
            return st;
        }
        for (LoadedModule dep : linkState.loadedDependencies()) {
            if (dep.classLoader() instanceof ModuleClassLoader mcl) {
                mcl.linkProvides();
            }
        }
        return doLocked(ModuleClassLoader::linkUsesLocked);
    }

    private LinkState.Uses linkUsesLocked() {
        // double-check it inside the lock
        LinkState.Provides linkState = linkProvides();
        if (linkState instanceof LinkState.Uses st) {
            return st;
        }
        log.debugf("Linking module %s to uses state", moduleName);
        LinkState.Uses newState = new LinkState.Uses(
            linkState
        );
        this.linkState = newState;
        return newState;
    }

    // Private

    private void registerLayer(ModuleLayer layer) {
        if (registeredLayers.add(layer)) {
            Util.bindLayerToLoader(layer, this);
        }
    }

    private int flagsOfModule(Modifiers<ModuleDescriptor.Modifier> mods) {
        int mask = AccessFlag.MODULE.mask();
        if (mods.contains(ModuleDescriptor.Modifier.OPEN)) {
            mask |= AccessFlag.OPEN.mask();
        }
        return mask;
    }

    private Resource loadModuleInfo() {
        if (linkInitial().modifiers().contains(ModuleDescriptor.Modifier.UNNAMED)) {
            // no module-info for unnamed modules
            return null;
        }
        // todo: copy annotations
        byte[] bytes = ClassFile.of().build(ConstantUtils.CD_module_info, zb -> {
            zb.withVersion(ClassFile.JAVA_9_VERSION, 0);
            zb.withFlags(flagsOfModule(linkInitial().modifiers()));
            zb.with(ModuleAttribute.of(
                ModuleDesc.of(moduleName),
                mab -> {
                    mab.moduleName(ModuleDesc.of(moduleName));
                    mab.moduleVersion(moduleVersion);
                    // java.base is always required
                    mab.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED, AccessFlag.SYNTHETIC), null);
                    // list unqualified exports & opens
                    linkInitial().packages()
                        .forEach((name, pkg) -> {
                            switch (pkg.packageAccess()) {
                                case EXPORTED -> mab.exports(PackageDesc.of(name), List.of());
                                case OPEN -> mab.opens(PackageDesc.of(name), List.of());
                            }
                        });
                }
            ));
            zb.with(ModulePackagesAttribute.of(linkInitial().packages().keySet().stream()
                .map(n -> zb.constantPool().packageEntry(PackageDesc.of(n)))
                .toList()
            ));
        });
        return new MemoryResource("module-info.class", bytes);
    }

    private Class<?> loadClassFromDescriptor(String descriptor, int idx) throws ClassNotFoundException {
        return switch (descriptor.charAt(idx)) {
            case 'B' -> byte.class;
            case 'C' -> char.class;
            case 'D' -> double.class;
            case 'F' -> float.class;
            case 'I' -> int.class;
            case 'J' -> long.class;
            case 'L' -> loadClass(descriptor.substring(idx + 1, descriptor.length() - 1));
            case 'S' -> short.class;
            case 'Z' -> boolean.class;
            case '[' -> loadClassFromDescriptor(descriptor, idx + 1).arrayType();
            default -> throw new ClassNotFoundException("Invalid descriptor: " + descriptor);
        };
    }

    private Class<?> defineOrGetClass(final String dotName, final Resource resource, final ProtectionDomain pd) throws IOException {
        return defineOrGetClass(dotName, resource.asBuffer(), pd);
    }

    private Class<?> defineOrGetClass(final String dotName, final ByteBuffer buffer, final ProtectionDomain pd) {
        String packageName = Util.packageName(dotName);
        if (! packageName.isEmpty()) {
            loadPackageDirect(packageName);
        }
        Class<?> clazz = findLoadedClass(dotName);
        if (clazz != null) {
            return clazz;
        }
        try {
            return defineClass(dotName, buffer, pd);
        } catch (VerifyError e) {
            // serious problem!
            throw e;
        } catch (LinkageError e) {
            // probably a duplicate
            Class<?> loaded = findLoadedClass(dotName);
            if (loaded != null) {
                return loaded;
            }
            // actually some other problem
            throw new LinkageError("Failed to link class " + dotName + " in " + this, e);
        }
    }

    // Somewhat unsupported operations

    protected final Object getClassLoadingLock(final String className) {
        /* this is tricky: we know that something is trying to load the class
         * under the lock; so instead load the class outside the lock, and use the
         * class itself as the class loading lock.
         * If the class is not found, return a new object, because no conflict will be possible anyway.
         */
        // called from java.lang.ClassLoader.loadClass(java.lang.Module, java.lang.String)
        try {
            return loadClass(className);
        } catch (ClassNotFoundException e) {
            return new Object();
        }
    }

    @SuppressWarnings("deprecation")
    protected final Package getPackage(final String name) {
        Package defined = getDefinedPackage(name);
        if (defined != null) {
            return defined;
        }
        Module module = linkPackages().modulesByPackage().get(name);
        if (module == null) {
            // no such package
            return null;
        }
        return loadPackage(module, name);
    }

    private Package loadPackage(Module module, String pkg) {
        ClassLoader cl = module.getClassLoader();
        if (cl instanceof ModuleClassLoader mcl) {
            return mcl.loadPackageDirect(pkg);
        } else {
            // best effort; todo: this could possibly be improved somewhat
            return cl == null ? null : cl.getDefinedPackage(pkg);
        }
    }

    protected final Package[] getPackages() {
        return linkPackages().modulesByPackage()
            .entrySet()
            .stream()
            .sorted()
            .map(e -> loadPackage(e.getValue(), e.getKey()))
            .filter(Objects::nonNull)
            .toArray(Package[]::new);
    }

    // Fully unsupported operations

    protected final Class<?> findClass(final String name) {
        throw new UnsupportedOperationException();
    }

    protected final Class<?> findClass(final String moduleName, final String name) {
        // called from java.lang.ClassLoader.loadClass(java.lang.Module, java.lang.String)
        // we've already tried loading the class, so just return null now
        return null;
    }

    protected final URL findResource(final String moduleName, final String name) throws IOException {
        // called from java.lang.Module.getResourceAsStream
        if (this.moduleName.equals(moduleName)) {
            Resource resource = loadResourceDirect(name);
            return resource == null ? null : resource.url();
        }
        return null;
    }

    protected final URL findResource(final String name) {
        throw new UnsupportedOperationException();
    }

    protected final Enumeration<URL> findResources(final String name) {
        throw new UnsupportedOperationException();
    }

    void close() throws IOException {
        if (linkState == LinkState.Closed.INSTANCE) {
            return;
        }
        ReentrantLock lock = linkLock;
        LinkState.Initial init;
        lock.lock();
        try {
            // refresh under lock
            if (linkState instanceof LinkState.Initial is) {
                init = is;
                linkState = LinkState.Closed.INSTANCE;
            } else {
                // it must be closed
                return;
            }
        } finally {
            lock.unlock();
        }
        IOException ioe = null;
        for (ResourceLoader loader : init.resourceLoaders()) {
            try {
                loader.close();
            } catch (Throwable t) {
                if (ioe == null) {
                    ioe = new IOException("Failed to close resource loader " + loader, t);
                } else {
                    ioe.addSuppressed(t);
                }
            }
        }
        if (ioe != null) {
            throw ioe;
        }
    }

    Controller controller() {
        return controller;
    }

    String mainClassName() {
        return mainClassName;
    }

    public static final class ClassLoaderConfiguration {
        private final ModuleLoader moduleLoader;
        private final String classLoaderName;
        private final List<ResourceLoader> resourceLoaders;
        private final String moduleName;
        private final String moduleVersion;
        private final List<Dependency> dependencies;
        private final Map<String, io.github.dmlloyd.modules.desc.Package> packages;
        private final Modifiers<ModuleDescriptor.Modifier> modifiers;
        private final Set<String> uses;
        private final Map<String, List<String>> provides;
        private final URI location;
        private final String mainClassName;

        ClassLoaderConfiguration(
            ModuleLoader moduleLoader,
            String classLoaderName,
            List<ResourceLoader> resourceLoaders,
            String moduleName,
            String moduleVersion,
            List<Dependency> dependencies,
            Map<String, io.github.dmlloyd.modules.desc.Package> packages,
            Modifiers<ModuleDescriptor.Modifier> modifiers,
            Set<String> uses,
            Map<String, List<String>> provides,
            URI location,
            String mainClassName
        ) {
            this.moduleLoader = moduleLoader;
            this.classLoaderName = classLoaderName;
            this.resourceLoaders = resourceLoaders;
            this.moduleName = moduleName;
            this.moduleVersion = moduleVersion;
            this.dependencies = dependencies;
            this.packages = packages;
            this.modifiers = modifiers;
            this.uses = uses;
            this.provides = provides;
            this.location = location;
            this.mainClassName = mainClassName;
        }

        ModuleLoader moduleLoader() {
            return moduleLoader;
        }

        String classLoaderName() {
            return classLoaderName;
        }

        List<ResourceLoader> resourceLoaders() {
            return resourceLoaders;
        }

        String moduleName() {
            return moduleName;
        }

        String moduleVersion() {
            return moduleVersion;
        }

        List<Dependency> dependencies() {
            return dependencies;
        }

        Map<String, io.github.dmlloyd.modules.desc.Package> packages() {
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

        String mainClassName() {
            return mainClassName;
        }
    }

    public final class Controller {
        Controller() {
        }

        /**
         * Unload this module.
         */
        public void close() throws IOException {
            ModuleClassLoader.this.close();
        }

        public ModuleClassLoader classLoader() {
            return ModuleClassLoader.this;
        }

        public Class<?> defineClass(String name, ByteBuffer bytes, ProtectionDomain protectionDomain) {
            return ModuleClassLoader.this.defineOrGetClass(name, bytes, protectionDomain);
        }
    }

    private record SingleModuleFinder(ModuleReference modRef) implements ModuleFinder {
        public Optional<ModuleReference> find(final String name) {
            if (name.equals(modRef.descriptor().name())) {
                return Optional.of(modRef);
            } else {
                return Optional.empty();
            }
        }

        public Set<ModuleReference> findAll() {
            return Set.of(modRef);
        }
    }

    private static final Logger log = Logger.getLogger("io.github.dmlloyd.modules");
    private static final StackWalker stackWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Function<Stream<StackWalker.StackFrame>, Class<?>> callerFinder = s -> s.map(StackWalker.StackFrame::getDeclaringClass).filter(c -> c.getClassLoader() != null).findFirst().orElse(null);
}

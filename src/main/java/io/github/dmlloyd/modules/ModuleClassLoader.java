package io.github.dmlloyd.modules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.dmlloyd.modules.desc.Dependency;
import io.github.dmlloyd.modules.desc.Modifiers;
import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.desc.PackageAccess;
import io.github.dmlloyd.modules.desc.PackageInfo;
import io.github.dmlloyd.modules.impl.Access;
import io.github.dmlloyd.modules.impl.Util;
import io.smallrye.classfile.ClassFile;
import io.smallrye.classfile.attribute.ModuleAttribute;
import io.smallrye.classfile.attribute.ModulePackagesAttribute;
import io.smallrye.classfile.extras.constant.ConstantUtils;
import io.smallrye.classfile.extras.constant.ModuleDesc;
import io.smallrye.classfile.extras.constant.PackageDesc;
import io.smallrye.classfile.extras.reflect.AccessFlag;
import io.smallrye.common.cpu.CPU;
import io.smallrye.common.os.OS;
import io.smallrye.common.resource.MemoryResource;
import io.smallrye.common.resource.PathResource;
import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;
import io.smallrye.common.resource.ResourceUtils;
import org.jboss.logging.Logger;

/**
 * A class loader for a module.
 */
public class ModuleClassLoader extends ClassLoader {
    static {
        if (! ClassLoader.registerAsParallelCapable()) {
            throw new InternalError("Class loader cannot be made parallel-capable");
        }
    }

    private static final Map<String, Module> bootModuleIndex;

    static {
        HashMap<String, Module> map = new HashMap<>(1000);
        populateIndex(map, Util.myLayer);
        bootModuleIndex = Map.copyOf(map);
    }

    private static void populateIndex(Map<String, Module> map, ModuleLayer layer) {
        for (Module module : layer.modules()) {
            for (String pn : module.getPackages()) {
                map.put(pn, module);
            }
        }
        for (ModuleLayer parent : layer.parents()) {
            populateIndex(map, parent);
        }
    }

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

    /**
     * Construct a new instance.
     *
     * @param config the configuration (must not be {@code null})
     * @param name the non-empty class loader name, or {@code null} for no name
     */
    public ModuleClassLoader(ClassLoaderConfiguration config, String name) {
        super(name, null);
        config.checkAndClear();
        if (! isRegisteredAsParallelCapable()) {
            throw new IllegalStateException("Class loader is not registered as parallel-capable");
        }
        this.moduleLoader = config.moduleLoader();
        ModuleDescriptor descriptor = config.descriptor();
        this.moduleName = descriptor.name();
        this.moduleVersion = descriptor.version().orElse(null);
        this.mainClassName = descriptor.mainClass().orElse(null);
        this.linkState = new LinkState.New(
            moduleName,
            mainClassName,
            descriptor.dependencies(),
            config.resourceLoaders(),
            descriptor.packages(),
            descriptor.modifiers(),
            descriptor.uses(),
            descriptor.provides(),
            descriptor.location().orElse(null)
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
     * {@return the name of this class loader's module}
     */
    public String moduleName() {
        return moduleName;
    }

    /**
     * {@return the module loaded by this class loader}
     */
    public final Module module() {
        return linkDefined().module();
    }

    /**
     * {@return the main class of this module, if any (not {@code null})}
     */
    public final Optional<Class<?>> mainClass() {
        String mainClassName = this.mainClassName;
        if (mainClassName == null) {
            return Optional.empty();
        }
        Class<?> mainClass;
        try {
            mainClass = loadClassDirect(mainClassName);
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
        return Optional.of(mainClass);
    }

    private static final String archName = OS.current().name().toLowerCase(Locale.ROOT) + "-" + CPU.host().name();

    protected String findLibrary(String libName) {
        for (ResourceLoader loader : linkDefined().resourceLoaders()) {
            Resource resource;
            try {
                resource = loader.findResource(archName + "/" + libName);
            } catch (IOException e) {
                // not found
                continue;
            }
            // todo: replace with pr.hasFile()
            if (resource instanceof PathResource pr && pr.path().getFileSystem() == FileSystems.getDefault()) {
                // todo: replace with pr.file()
                return pr.path().toFile().getAbsolutePath();
            }
        }
        return null;
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
        if (packageName.isEmpty() || linkNew().packages().containsKey(packageName)) {
            // force full linkage
            linkPackages();
            return loadClassDirect(name);
        }
        if (bootModuleIndex.containsKey(packageName)) {
            if (dotName.equals("java.util.ServiceLoader")) {
                // loading services! extra linking required
                linkUses();
            }
            // -> BootLoader.loadClass(...)
            Module module = bootModuleIndex.get(packageName);
            if (! module.isExported(packageName, module())) {
                throw new ClassNotFoundException("Cannot load " + name + ": package " + packageName + " not exported from " + module + " to " + module());
            }
            Class<?> result = Class.forName(module, dotName);
            if (result != null) {
                return result;
            }
            throw new ClassNotFoundException("Cannot find " + name + " from " + this);
        }
        LoadedModule lm = linkPackages().modulesByPackage().get(packageName);
        if (lm == null) {
            throw new ClassNotFoundException("Class loader for " + this + " does not link against package `" + packageName + "`");
        }
        Class<?> loaded;
        ClassLoader cl = lm.classLoader();
        if (cl == this) {
            loaded = loadClassDirect(dotName);
        } else {
            Module module = module();
            if (lm.module().isExported(packageName, module)) {
                if (cl instanceof ModuleClassLoader mcl) {
                    loaded = mcl.loadClassDirect(dotName);
                } else {
                    loaded = Class.forName(dotName, false, cl);
                }
            } else {
                throw new ClassNotFoundException(lm.name().map(n -> "Module " + n).orElse("Unnamed module") + " does not export package " + packageName + " to " + module().getName());
            }
        }
        if (resolve) {
            // note: this is a no-op in OpenJDK
            resolveClass(loaded);
        }
        return loaded;
    }

    public final Resource getExportedResource(final String name) {
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
        // todo: filter to exportable resources?
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
        // todo: filter to exportable resources?
        try {
            return loadResourcesDirect(name).stream().map(Resource::url);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public final Set<String> exportedPackages() {
        throw new UnsupportedOperationException("Not supported yet.");
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
    public static ModuleClassLoader ofModule(Module module) {
        return module.getClassLoader() instanceof ModuleClassLoader mcl ? mcl : null;
    }

    /**
     * {@return the module class loader of the given thread, or {@code null} if it does not have one}
     */
    public static ModuleClassLoader ofThread(Thread thread) {
        return thread.getContextClassLoader() instanceof ModuleClassLoader mcl ? mcl : null;
    }

    public String toString() {
        return "ModuleClassLoader[" + moduleName + "]";
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
        return getExportedResource0(caller, resource.pathName(), resource, null);
    }

    private List<Resource> getExportedResources(final String name, final Class<?> caller) throws IOException {
        List<Resource> resources = loadResourcesDirect(name);
        if (resources.isEmpty()) {
            return List.of();
        }
        return getExportedResource0(caller, resources.get(0).pathName(), resources, List.of());
    }

    private <R> R getExportedResource0(Class<?> caller, String pathName, R resource, R defaultVal) {
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
        if (linkDefined().packages().getOrDefault(pkgName, PackageInfo.PRIVATE).packageAccess().isAtLeast(PackageAccess.EXPORTED)) {
            return resource;
        }
        if (module().isOpen(pkgName, caller.getModule())) {
            return resource;
        }
        // no access
        return defaultVal;
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
        String binaryName = name.replace('/', '.');
        Class<?> loaded = findLoadedClass(binaryName);
        if (loaded != null) {
            return loaded;
        }
        LinkState.Defined linked = linkDefined();
        String packageName = Util.packageName(binaryName);
        if (! packageName.isEmpty() && ! linked.packages().containsKey(packageName)) {
            throw new ClassNotFoundException("Class `" + name + "` is not in a package that is reachable from " + moduleName);
        }

        String fullPath = name.replace('.', '/') + ".class";
        try {
            Resource resource = loadResourceDirect(fullPath);
            if (resource != null) {
                // found it!
                ProtectionDomain pd = linked.cachedProtectionDomain(resource);
                return defineOrGetClass(binaryName, resource, pd);
            }
        } catch (IOException e) {
            throw new ClassNotFoundException("Failed to load " + binaryName, e);
        }
        throw new ClassNotFoundException("Class `" + name + "` is not found in " + moduleName);
    }

    final Resource loadResourceDirect(String rawName) throws IOException {
        String name = ResourceUtils.canonicalizeRelativePath(rawName);
        if (name.equals("module-info.class")) {
            // this is always loaded as a resource
            return loadModuleInfo();
        }
        if (isServiceFileName(name)) {
            if (linkNew().modifiers().contains(ModuleDescriptor.Modifier.UNNAMED)) {
                return loadServicesFileDirect(name);
            } else {
                return null;
            }
        }
        for (ResourceLoader loader : linkNew().resourceLoaders()) {
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

    final List<Resource> loadResourcesDirect(final String rawName) throws IOException {
        String name = ResourceUtils.canonicalizeRelativePath(rawName);
        if (name.equals("module-info.class")) {
            // this is always loaded as a resource
            Resource moduleInfo = loadModuleInfo();
            return moduleInfo == null ? List.of() : List.of(moduleInfo);
        }
        if (isServiceFileName(name)) {
            if (linkNew().modifiers().contains(ModuleDescriptor.Modifier.UNNAMED)) {
                Resource resource = loadServicesFileDirect(name);
                return resource == null ? List.of() : List.of(resource);
            } else {
                return List.of();
            }
        }
        try {
            return linkNew().resourceLoaders().stream().map(l -> {
                try {
                    return l.findResource(name);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).filter(Objects::nonNull).collect(Util.toList());
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

    LinkState.New linkNew() {
        LinkState linkState = this.linkState;
        if (linkState instanceof LinkState.New state) {
            return state;
        }
        assert linkState == LinkState.Closed.INSTANCE;
        throw new IllegalStateException("Module " + moduleName + " has been unloaded");
    }

    LinkState.Dependencies linkDependencies() {
        // fast path
        if (linkState instanceof LinkState.Dependencies dependencies) {
            return dependencies;
        }
        LinkState.New linkState = linkNew();
        if (linkState instanceof LinkState.Dependencies dependencies) {
            return dependencies;
        }
        List<Dependency> lsDeps = linkState.dependencies();
        ArrayList<LoadedDependency> loadedDependencies = new ArrayList<>(lsDeps.size());
        for (Dependency dep : lsDeps) {
            ModuleLoader ml = dep.moduleLoader().orElse(moduleLoader);
            LoadedModule lm;
            if (dep.modifiers().contains(Dependency.Modifier.OPTIONAL)) {
                lm = ml.loadModule(dep.moduleName());
            } else {
                try {
                    lm = ml.requireModule(dep.moduleName());
                } catch (ModuleLoadException e) {
                    throw e.withMessage(e.getMessage() + " (required by " + moduleName + ")");
                }
            }
            if (lm != null) {
                if (lm.classLoader() == this && lm.name().isPresent()) {
                    throw new ModuleLoadException("Module " + moduleName() + " depends on itself");
                }
                loadedDependencies.add(new LoadedDependency(dep, lm));
            }
        }
        return doLocked(ModuleClassLoader::linkDependenciesLocked, loadedDependencies);
    }

    private LinkState.Dependencies linkDependenciesLocked(List<LoadedDependency> loadedDependencies) {
        LinkState.New linkState = linkNew();
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

    LinkState.Defined linkDefined() {
        // fast path
        if (linkState instanceof LinkState.Defined defined) {
            return defined;
        }
        LinkState.New linkState = linkDependencies();
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

    private static final List<Configuration> PARENT_CONFIGS = List.of(ModuleLayer.boot().configuration());
    private static final List<ModuleLayer> BOOT_LAYER_ONLY = List.of(ModuleLayer.boot());

    private LinkState.Defined linkDefinedLocked(java.lang.module.ModuleDescriptor descriptor) {
        LinkState.Dependencies linkState = linkDependencies();
        if (linkState instanceof LinkState.Defined defined) {
            return defined;
        }
        log.debugf("Linking module %s to defined state", moduleName);
        LinkState.Defined defined;
        if (linkState.modifiers().contains(ModuleDescriptor.Modifier.UNNAMED)) {
            // nothing needed
            defined = new LinkState.Defined(linkState, getUnnamedModule(), null);
        } else {
            // all the stuff that the JDK needs to have a module
            URI uri = linkState.location();
            ModuleReference modRef = new ModuleReference(descriptor, uri) {
                public ModuleReader open() {
                    throw new UnsupportedOperationException();
                }
            };
            final Configuration cf = Configuration.resolve(
                new SingleModuleFinder(modRef),
                PARENT_CONFIGS,
                Util.EMPTY_MF,
                List.of(moduleName)
            );
            ModuleLayer.Controller ctl = ModuleLayer.defineModules(cf, BOOT_LAYER_ONLY, ignored -> this);
            ModuleLayer moduleLayer = ctl.layer();
            Module module = moduleLayer.findModule(moduleName).orElseThrow(IllegalStateException::new);
            if (linkState.modifiers().contains(ModuleDescriptor.Modifier.NATIVE_ACCESS)) {
                Access.enableNativeAccess(module);
            }
            defined = new LinkState.Defined(
                linkState,
                module,
                ctl
            );
            defined.addReads(Util.myModule);
            Util.myModule.addReads(module);
        }
        this.linkState = defined;
        return defined;
    }

    LinkState.Packages linkPackages() {
        // fast path
        if (linkState instanceof LinkState.Packages packages) {
            return packages;
        }
        LinkState.Defined linkState = linkDefined();
        if (linkState instanceof LinkState.Packages packages) {
            return packages;
        }
        LoadedModule self = LoadedModule.forModule(linkState.module());
        HashSet<LoadedModule> visited = new HashSet<>();
        visited.add(self);
        // link immediate dependency packages
        HashMap<String, LoadedModule> modulesByPackage = new HashMap<>();
        for (LoadedDependency ld : linkState.loadedDependencies()) {
            Dependency dependency = ld.dependency();
            LoadedModule lm = ld.loadedModule();
            Module depModule = lm.module();
            if (dependency.isRead()) {
                linkState.addReads(depModule);
            }
            if (dependency.isServices()) {
                // link up service loaders early; needed in case someone uses ServiceLoader.load(svc, myMCL)
                // TODO in this case providers are still not registered
                ModuleLayer layer = depModule.getLayer();
                if (layer != ModuleLayer.boot()) {
                    registerLayer(layer);
                }
            }
            boolean linked = dependency.isLinked();
            linkTransitive(linkState, dependency.isRead(), linked, lm, modulesByPackage, visited);
            // link up special package accesses of dependency (only for immediate dependencies)
            Module myModule = module();
            for (Map.Entry<String, PackageAccess> entry : dependency.packageAccesses().entrySet()) {
                switch (entry.getValue()) {
                    case EXPORTED -> Access.addExports(depModule, entry.getKey(), myModule);
                    case OPEN -> Access.addOpens(depModule, entry.getKey(), myModule);
                    case PRIVATE -> {
                        continue;
                    }
                }
                if (linked) {
                    modulesByPackage.putIfAbsent(entry.getKey(), lm);
                }
            }
        }
        // and don't forget our own packages
        for (String pkg : linkState.packages().keySet()) {
            modulesByPackage.put(pkg, self);
        }
        // link up directed exports and opens (TODO: do this later when target module is loaded)
        for (Map.Entry<String, PackageInfo> entry : linkState.packages().entrySet()) {
            for (String target : entry.getValue().exportTargets()) {
                LoadedModule resolved = moduleLoader().loadModule(target);
                if (resolved != null) {
                    linkState.addExports(entry.getKey(), resolved.module());
                }
            }
            for (String target : entry.getValue().openTargets()) {
                LoadedModule resolved = moduleLoader().loadModule(target);
                if (resolved != null) {
                    linkState.addOpens(entry.getKey(), resolved.module());
                }
            }
        }
        return doLocked(ModuleClassLoader::linkPackagesLocked, modulesByPackage);
    }

    private LinkState.Packages linkPackagesLocked(final Map<String, LoadedModule> modulesByPackage) {
        // double-check it inside the lock
        LinkState.Defined defined = linkDefined();
        if (defined instanceof LinkState.Packages packages) {
            return packages;
        }
        log.debugf("Linking module %s to packages state", moduleName);
        LinkState.Packages linked = new LinkState.Packages(
            defined,
            Map.copyOf(modulesByPackage)
        );
        linkState = linked;
        return linked;
    }

    /**
     * Link the packages of the dependency, and then recurse to the transitive dependencies of the dependency.
     *
     * @param linkState this module's link state (must not be {@code null})
     * @param read {@code true} to register the dependency as readable, otherwise {@code false}
     * @param linked {@code true} to register the dependency as linked, otherwise {@code false}
     * @param loadedModule the loaded dependency module (must not be {@code null})
     * @param modulesByPackage the map being populated (must not be {@code null})
     * @param visited the visited module set (must not be {@code null})
     */
    private void linkTransitive(LinkState.Defined linkState, boolean read, boolean linked, LoadedModule loadedModule, Map<String, LoadedModule> modulesByPackage, Set<LoadedModule> visited) {
        if (visited.add(loadedModule)) {
            if (loadedModule.classLoader() instanceof ModuleClassLoader mcl) {
                if (linked) {
                    loadedModule.forEachExportedPackage(linkState.module(), pn -> {
                        modulesByPackage.putIfAbsent(pn, loadedModule);
                    });
                }
                for (LoadedDependency ld : mcl.linkDependencies().loadedDependencies()) {
                    Dependency dependency = ld.dependency();
                    boolean subLinked = linked && dependency.isLinked();
                    boolean subRead = read && dependency.isRead();
                    if (subRead) {
                        linkState.addReads(loadedModule.module());
                    }
                    if (dependency.isTransitive()) {
                        linkTransitive(linkState, subRead, subLinked, ld.loadedModule(), modulesByPackage, visited);
                    }
                }
            } else {
                Module module = loadedModule.module();
                java.lang.module.ModuleDescriptor descriptor = module.getDescriptor();
                if (descriptor != null) {
                    if (linked && ! ModuleLayer.boot().modules().contains(module)) {
                        loadedModule.forEachExportedPackage(linkState.module(), pn -> {
                            modulesByPackage.putIfAbsent(pn, loadedModule);
                        });
                    }
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
                            Module subModule = optDep.get();
                            LoadedModule subLm = LoadedModule.forModule(subModule);
                            if (read) {
                                linkState.addReads(loadedModule.module());
                            }
                            linkTransitive(linkState, read, linked, subLm, modulesByPackage, visited);
                        }
                    }
                }
            }
        }
    }

    LinkState.Provides linkProvides() {
        // fast path
        if (linkState instanceof LinkState.Provides provides) {
            return provides;
        }
        LinkState.Packages linkState = linkPackages();
        if (linkState instanceof LinkState.Provides provides) {
            return provides;
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
        if (linkState instanceof LinkState.Provides provides) {
            return provides;
        }
        log.debugf("Linking module %s to provides state", moduleName);
        LinkState.Provides newState = new LinkState.Provides(
            linkState
        );
        this.linkState = newState;
        return newState;
    }

    LinkState.Uses linkUses() {
        // fast path
        if (linkState instanceof LinkState.Uses uses) {
            return uses;
        }
        LinkState.Provides linkState = linkProvides();
        if (linkState instanceof LinkState.Uses uses) {
            return uses;
        }
        for (LoadedDependency ld : linkState.loadedDependencies()) {
            if (ld.dependency().isServices() && ld.loadedModule().classLoader() instanceof ModuleClassLoader mcl) {
                mcl.linkProvides();
            }
        }
        for (String used : linkState.uses()) {
            try {
                linkState.addUses(loadClass(used));
            } catch (ClassNotFoundException ignored) {
            }
        }
        return doLocked(ModuleClassLoader::linkUsesLocked);
    }

    private LinkState.Uses linkUsesLocked() {
        // double-check it inside the lock
        LinkState.Provides linkState = linkProvides();
        if (linkState instanceof LinkState.Uses uses) {
            return uses;
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
            Access.bindLayerToLoader(layer, this);
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
        if (linkNew().modifiers().contains(ModuleDescriptor.Modifier.UNNAMED)) {
            // no module-info for unnamed modules
            return null;
        }
        // todo: copy annotations
        byte[] bytes = ClassFile.of().build(ConstantUtils.CD_module_info, zb -> {
            zb.withVersion(ClassFile.JAVA_9_VERSION, 0);
            zb.withFlags(flagsOfModule(linkNew().modifiers()));
            zb.with(ModuleAttribute.of(
                ModuleDesc.of(moduleName),
                mab -> {
                    mab.moduleName(ModuleDesc.of(moduleName));
                    mab.moduleVersion(moduleVersion);
                    // java.base is always required
                    mab.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED, AccessFlag.SYNTHETIC), null);
                    // list unqualified exports & opens
                    linkNew().packages()
                        .forEach((name, pkg) -> {
                            switch (pkg.packageAccess()) {
                                case EXPORTED -> mab.exports(PackageDesc.of(name), List.of());
                                case OPEN -> mab.opens(PackageDesc.of(name), List.of());
                            }
                        });
                }
            ));
            zb.with(ModulePackagesAttribute.of(linkNew().packages().keySet().stream()
                .map(n -> zb.constantPool().packageEntry(PackageDesc.of(n)))
                .collect(Util.toList())
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

    private Class<?> defineOrGetClass(final String binaryName, final Resource resource, final ProtectionDomain pd) throws IOException {
        return defineOrGetClass(binaryName, resource.asBuffer(), pd);
    }

    private Class<?> defineOrGetClass(final String binaryName, final ByteBuffer buffer, final ProtectionDomain pd) {
        String packageName = Util.packageName(binaryName);
        if (! packageName.isEmpty()) {
            loadPackageDirect(packageName);
        }
        Class<?> clazz = findLoadedClass(binaryName);
        if (clazz != null) {
            return clazz;
        }
        try {
            return defineClass(binaryName, buffer, pd);
        } catch (VerifyError e) {
            // serious problem!
            log.debugf(e, "Verifying class %s failed", binaryName);
            throw e;
        } catch (LinkageError e) {
            // probably a duplicate
            Class<?> loaded = findLoadedClass(binaryName);
            if (loaded != null) {
                return loaded;
            }
            // actually some other problem
            LinkageError le = new LinkageError("Failed to link class " + binaryName + " in " + this, e);
            log.debugf(e, "Failed to load %s", binaryName);
            throw le;
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
        LoadedModule lm = linkPackages().modulesByPackage().get(name);
        if (lm == null) {
            // no such package
            return null;
        }
        return loadPackage(lm, name);
    }

    private Package loadPackage(LoadedModule module, String pkg) {
        ClassLoader cl = module.classLoader();
        if (cl instanceof ModuleClassLoader mcl) {
            return mcl.loadPackageDirect(pkg);
        } else {
            // best effort
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


    String mainClassName() {
        return mainClassName;
    }

    void close() throws IOException {
        if (linkState == LinkState.Closed.INSTANCE) {
            return;
        }
        ReentrantLock lock = linkLock;
        LinkState.New init;
        lock.lock();
        try {
            // refresh under lock
            if (linkState instanceof LinkState.New newState) {
                init = newState;
                this.linkState = LinkState.Closed.INSTANCE;
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

    void forEachExportedPackage(Module toModule, Consumer<String> action) {
        linkNew().packages().keySet().stream().filter(p -> isExported(p, toModule)).forEach(action);
    }

    boolean isExported(String packageName, Module toModule) {
        PackageInfo info = linkNew().packages().get(packageName);
        if (info == null) {
            return false;
        }
        if (info.packageAccess().isAtLeast(PackageAccess.EXPORTED)) {
            return true;
        }
        return info.exportTargets().contains(toModule.getName());
    }

    public static final class ClassLoaderConfiguration {
        private Thread valid;
        private final ModuleLoader moduleLoader;
        private final List<ResourceLoader> resourceLoaders;
        private final ModuleDescriptor descriptor;

        ClassLoaderConfiguration(final ModuleLoader moduleLoader, final List<ResourceLoader> resourceLoaders, final ModuleDescriptor descriptor) {
            valid = Thread.currentThread();
            this.moduleLoader = moduleLoader;
            this.resourceLoaders = resourceLoaders;
            this.descriptor = descriptor;
        }

        void checkAndClear() {
            if (valid == Thread.currentThread()) {
                valid = null;
                return;
            }
            throw new SecurityException("Use of class loader configuration outside of context");
        }

        void clear() {
            valid = null;
        }

        ModuleLoader moduleLoader() {
            return moduleLoader;
        }

        List<ResourceLoader> resourceLoaders() {
            return resourceLoaders;
        }

        public ModuleDescriptor descriptor() {
            return descriptor;
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

package io.github.dmlloyd.modules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
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
import io.github.dmlloyd.modules.desc.Export;
import io.github.dmlloyd.modules.desc.Modifiers;
import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.desc.Open;
import io.github.dmlloyd.modules.desc.Provide;
import io.smallrye.common.resource.MemoryResource;
import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;
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

    private final String moduleName;
    private final String moduleVersion;
    private final ModuleLoader moduleLoader;

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
     */
    public ModuleClassLoader(ClassLoaderConfiguration config) {
        super(config.classLoaderName(), null);
        this.moduleLoader = config.moduleLoader();
        this.moduleName = config.moduleName();
        this.moduleVersion = config.moduleVersion();
        this.linkState = new LinkState.Initial(
            config.dependencies(),
            config.resourceLoaders(),
            config.exports(),
            config.opens(),
            config.packages(),
            config.modifiers(),
            config.uses(),
            config.provides()
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
            return loadClassFromDescriptor(name);
        }
        String dotName = name.replace('/', '.');
        int lastDot = dotName.lastIndexOf('.');
        if (lastDot == - 1) {
            return loadClassDirect(name);
        } else {
        }
        String packageName = dotName.substring(0, lastDot);
        Module module = linkFull().modulesByPackage().get(packageName);
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
        return linkFull().exportedPackages();
    }

    /**
     * {@return the module class loader of the calling class, or {@code null} if the calling class does not have one}
     */
    public static ModuleClassLoader current() {
        return stackWalker.walk(callerClassLoaderFinder) instanceof ModuleClassLoader mcl ? mcl : null;
    }

    /**
     * {@return the module class loader of the given module, or {@code null} if it does not have one}
     */
    public static ModuleClassLoader forModule(Module module) {
        return module.getClassLoader() instanceof ModuleClassLoader mcl ? mcl : null;
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
        if (resource.pathName().endsWith(".class")) {
            return resource;
        }

        String dotName = resource.pathName().replace('/', '.');
        int dot = dotName.lastIndexOf('.');
        if (dot == - 1) {
            return resource;
        }
        if (caller == null || caller.getModule().getClassLoader() == this) {
            return resource;
        }
        String pkgName = dotName.substring(0, dot);
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
        String dotName = pathName.replace('/', '.');
        int dot = dotName.lastIndexOf('.');
        if (dot == - 1) {
            return resources;
        }
        if (caller == null || caller.getModule().getClassLoader() == this) {
            return resources;
        }
        String pkgName = dotName.substring(0, dot);
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
        String slashName = name.replace('.', '/');
        int lastDot = dotName.lastIndexOf('.');
        String packageName;
        if (lastDot == - 1) {
            packageName = "";
        } else {
            packageName = dotName.substring(0, lastDot);
        }
        LinkState.Linked linked = linkFull();
        if (! linked.packages().contains(packageName)) {
            throw new ClassNotFoundException("Class `" + name + "` is not in a package contained within this loader");
        }

        String fullPath = slashName + ".class";
        try {
            for (ResourceLoader loader : linked.resourceLoaders()) {
                Resource resource = loader.findResource(fullPath);
                if (resource != null) {
                    // found it!
                    ProtectionDomain pd = linked.cachedProtectionDomain(resource);
                    return defineClass(dotName, resource, pd);
                }
            }
        } catch (IOException e) {
            throw new ClassNotFoundException("Failed to load " + dotName, e);
        }
        throw new ClassNotFoundException("Class `" + name + "` is not found in this loader");
    }

    final Resource loadResourceDirect(final String name) throws IOException {
        if (name.equals("module-info.class")) {
            // this is always loaded as a resource
            return loadModuleInfo();
        }
        for (ResourceLoader loader : linkInitial().resourceLoaders()) {
            Resource resource = loader.findResource(name);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    final List<Resource> loadResourcesDirect(final String name) throws IOException {
        if (name.equals("module-info.class")) {
            // this is always loaded as a resource
            return List.of(loadModuleInfo());
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
        List<ResourceLoader> list = linkDefined().resourceLoaders();
        Manifest manifest = null;
        ResourceLoader loader = null;
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

    private LinkState.Initial linkInitial() {
        LinkState linkState = this.linkState;
        if (linkState instanceof LinkState.Initial state) {
            return state;
        }
        assert linkState == LinkState.Closed.INSTANCE;
        throw new IllegalStateException("Module " + moduleName + " has been unloaded");
    }

    private LinkState.Defined linkDefined() {
        LinkState.Initial linkState = linkInitial();
        if (linkState instanceof LinkState.Defined defined) {
            return defined;
        }
        return doLocked(ModuleClassLoader::linkDefinedLocked);
    }

    private LinkState.Defined linkDefinedLocked() {
        LinkState.Initial linkState = linkInitial();
        if (linkState instanceof LinkState.Defined defined) {
            return defined;
        }
        log.debugf("Linking module %s to defined state", moduleName);
        // all the stuff that the JDK needs to have a module
        java.lang.module.ModuleDescriptor descriptor;
        try (InputStream is = loadModuleInfo().openStream()) {
            descriptor = java.lang.module.ModuleDescriptor.read(is);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        ModuleReference modRef = new ModuleReference(descriptor, null) {
            public ModuleReader open() {
                throw new UnsupportedOperationException();
            }
        };
        final Configuration cf = ModuleLayer.boot().configuration().resolve(
            new SingleModuleFinder(modRef),
            Util.EMPTY_MF,
            List.of(moduleName)
        );
        ModuleLayer.Controller ctl = ModuleLayer.defineModules(cf, List.of(ModuleLayer.boot()), __ -> this);
        ModuleLayer moduleLayer = ctl.layer();
        Module module = moduleLayer.findModule(moduleName).orElseThrow(IllegalStateException::new);
        if (linkState.modifiers().contains(ModuleDescriptor.Modifier.ENABLE_NATIVE_ACCESS)) {
            NativeAccessImpl.enableNativeAccess(ctl, module);
        }
        LinkState.Defined defined = new LinkState.Defined(
            linkState,
            module,
            ctl,
            linkState.exports().stream().filter(e -> e.targets().isEmpty()).map(Export::packageName).collect(Collectors.toUnmodifiableSet())
        );
        this.linkState = defined;
        return defined;
    }

    private LinkState.Linked linkFull() {
        LinkState.Defined linkState = linkDefined();
        if (linkState instanceof LinkState.Linked linked) {
            return linked;
        }
        List<Dependency> dependencies = linkState.dependencies();
        List<Module> loaders = new ArrayList<>(dependencies.size());
        for (Dependency dependency : dependencies) {
            String depName = dependency.moduleName();
            Module resolved = dependency.moduleLoader().orElse(moduleLoader()).loadModule(depName);
            if (resolved != null) {
                // link to it
                linkState.layerController().addReads(linkState.module(), resolved);
                loaders.add(resolved);
            } else if (! dependency.modifiers().contains(Dependency.Modifier.OPTIONAL)) {
                throw new ModuleNotFoundException("Cannot resolve dependency " + depName + " of " + moduleName);
            }
        }
        // the actual map to build
        HashMap<String, Module> modulesByPackage = new HashMap<>();
        for (Module module : loaders) {
            Set<String> packages = module.getPackages();
            for (String pkg : packages) {
                if (module.isExported(pkg, linkState.module())) {
                    modulesByPackage.putIfAbsent(pkg, module);
                }
            }
        }
        // and don't forget our own packages
        for (String pkg : linkState.packages()) {
            modulesByPackage.put(pkg, linkState.module());
        }
        // link up directed exports and opens
        for (Export export : linkState.exports()) {
            if (export.targets().isPresent()) {
                // seek out targets
                for (String target : export.targets().get()) {
                    Module resolved = moduleLoader().doLoadModule(target);
                    if (resolved != null) {
                        linkState.layerController().addExports(linkState.module(), export.packageName(), resolved);
                    }
                }
            }
        }
        for (Open open : linkState.opens()) {
            if (open.targets().isPresent()) {
                // seek out targets
                for (String target : open.targets().get()) {
                    Module resolved = moduleLoader().doLoadModule(target);
                    if (resolved != null) {
                        linkState.layerController().addOpens(linkState.module(), open.packageName(), resolved);
                    }
                }
            }
        }
        return doLocked(this_ -> this_.linkFullLocked(modulesByPackage));
    }

    private LinkState.Linked linkFullLocked(final Map<String, Module> modulesByPackage) {
        // double-check it inside the lock
        LinkState.Defined defined = linkDefined();
        if (defined instanceof LinkState.Linked linked) {
            return linked;
        }
        log.debugf("Linking module %s to fully linked state", moduleName);
        LinkState.Linked linked = new LinkState.Linked(
            defined,
            Map.copyOf(modulesByPackage)
        );
        linkState = linked;
        return linked;
    }

    // Private

    private int flagsOfModule(Modifiers<ModuleDescriptor.Modifier> mods) {
        int mask = AccessFlag.MODULE.mask();
        if (mods.contains(ModuleDescriptor.Modifier.OPEN)) {
            mask |= AccessFlag.OPEN.mask();
        }
        return mask;
    }

    private Resource loadModuleInfo() {
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
                    mab.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null);
                    // other dependencies are ad-hoc; don't list them here
                    // list unqualified exports
                    linkInitial().exports()
                        .stream()
                        .filter(e -> e.targets().isEmpty())
                        .forEach(e -> mab.exports(PackageDesc.of(e.packageName()), List.of()));
                    // list unqualified opens
                    linkInitial().opens()
                        .stream()
                        .filter(o -> o.targets().isEmpty())
                        .forEach(o -> mab.opens(PackageDesc.of(o.packageName()), List.of()));
                    // uses
                    linkInitial().uses().forEach(clz -> mab.uses(ClassDesc.of(clz)));
                    // provides
                    linkInitial().provides().forEach(p -> mab.provides(
                        ClassDesc.of(p.serviceName()),
                        p.withClasses().stream().map(ClassDesc::of).toArray(ClassDesc[]::new))
                    );
                }
            ));
            zb.with(ModulePackagesAttribute.of(
                linkInitial().packages()
                    .stream()
                    .map(n -> zb.constantPool().packageEntry(PackageDesc.of(n)))
                    .toList()
            ));
        });
        return new MemoryResource("module-info.class", bytes);
    }

    private Class<?> loadClassFromDescriptor(String descriptor) throws ClassNotFoundException {
        return switch (descriptor.charAt(0)) {
            case 'B' -> byte.class;
            case 'C' -> char.class;
            case 'D' -> double.class;
            case 'F' -> float.class;
            case 'I' -> int.class;
            case 'J' -> long.class;
            case 'L' -> loadClass(descriptor.substring(1, descriptor.length() - 1));
            case 'S' -> short.class;
            case 'Z' -> boolean.class;
            case '[' -> loadClassFromDescriptor(descriptor.substring(1)).arrayType();
            default -> throw new ClassNotFoundException("Invalid descriptor: " + descriptor);
        };
    }

    private Class<?> defineClass(final String dotName, final Resource resource, final ProtectionDomain pd) throws IOException {
        int lastDot = dotName.lastIndexOf('.');
        if (lastDot != -1) {
            loadPackageDirect(dotName.substring(0, lastDot));
        }
        ByteBuffer buffer = resource.asBuffer();
        try {
            return defineClass(dotName, buffer, pd);
        } catch (LinkageError e) {
            // probably a duplicate
            Class<?> loaded = findLoadedClass(dotName);
            if (loaded != null) {
                return loaded;
            }
            // actually some other problem
            throw e;
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
        Module module = linkFull().modulesByPackage().get(name);
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
        return linkFull().modulesByPackage()
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
        linkLock.lock();
        LinkState.Initial init;
        try {
            LinkState oldState = linkState;
            if (! (oldState instanceof LinkState.Initial ls)) {
                return;
            } else {
                init = ls;
                linkState = LinkState.Closed.INSTANCE;
            }
        } finally {
            linkLock.unlock();
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

    public static final class ClassLoaderConfiguration {
        private final ModuleLoader moduleLoader;
        private final String classLoaderName;
        private final List<ResourceLoader> resourceLoaders;
        private final String moduleName;
        private final String moduleVersion;
        private final List<Dependency> dependencies;
        private final Set<Export> exports;
        private final Set<Open> opens;
        private final Set<String> packages;
        private final Modifiers<ModuleDescriptor.Modifier> modifiers;
        private final Set<String> uses;
        private final Set<Provide> provides;

        ClassLoaderConfiguration(
            ModuleLoader moduleLoader,
            String classLoaderName,
            List<ResourceLoader> resourceLoaders,
            String moduleName,
            String moduleVersion,
            List<Dependency> dependencies,
            Set<Export> exports,
            Set<Open> opens,
            Set<String> packages,
            Modifiers<ModuleDescriptor.Modifier> modifiers,
            Set<String> uses,
            Set<Provide> provides
        ) {
            this.moduleLoader = moduleLoader;
            this.classLoaderName = classLoaderName;
            this.resourceLoaders = resourceLoaders;
            this.moduleName = moduleName;
            this.moduleVersion = moduleVersion;
            this.dependencies = dependencies;
            this.exports = exports;
            this.opens = opens;
            this.packages = packages;
            this.modifiers = modifiers;
            this.uses = uses;
            this.provides = provides;
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

    public final class Controller {
        private Controller() {
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

    private static final class NativeAccessImpl {
        private static final MethodHandle handle;

        static {
            MethodType methodType = MethodType.methodType(
                ModuleLayer.Controller.class,
                Module.class
            );
            MethodHandle h = null;
            try {
                h = MethodHandles.lookup().findVirtual(ModuleLayer.Controller.class, "enableNativeAccess", methodType);
            } catch (NoSuchMethodException | IllegalAccessException ignored) {
            }
            if (h == null) {
                h = MethodHandles.empty(methodType);
            }
            handle = h;
        }

        private NativeAccessImpl() {}

        static void enableNativeAccess(final ModuleLayer.Controller ctl, final Module module) {
            try {
                // force correct method signature
                var ignored = (ModuleLayer.Controller) handle.invokeExact(ctl, module);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new UndeclaredThrowableException(t);
            }
        }
    }

    private static final Logger log = Logger.getLogger("io.github.dmlloyd.modules");
    private static final StackWalker stackWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Function<Stream<StackWalker.StackFrame>, Class<?>> callerFinder = s -> s.map(StackWalker.StackFrame::getDeclaringClass).filter(c -> c.getClassLoader() != null).findFirst().orElse(null);
    private static final Function<Stream<StackWalker.StackFrame>, ClassLoader> callerClassLoaderFinder = s -> s.map(StackWalker.StackFrame::getDeclaringClass).map(Class::getClassLoader).findFirst().orElse(null);
}

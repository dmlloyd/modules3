package io.github.dmlloyd.modules;

import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.privateLookupIn;

import java.io.IOException;
import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.file.DirectoryStream;
import java.security.AllPermission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;

final class Util {
    static final ClassDesc[] NO_DESCS = new ClassDesc[0];
    static final PermissionCollection EMPTY_PERMISSIONS;
    static final PermissionCollection ALL_PERMISSIONS;
    static final Module myModule = Util.class.getModule();
    static final ModuleLayer myLayer = myModule.getLayer();
    private static final MethodHandle implAddUses;
    private static final MethodHandle addProvides;
    private static final MethodHandle enableNativeAccess;
    private static final MethodHandle moduleLayerBindToLoader;

    static {
        Permissions emptyPermissions = new Permissions();
        emptyPermissions.setReadOnly();
        EMPTY_PERMISSIONS = emptyPermissions;
        AllPermission all = new AllPermission();
        PermissionCollection allPermissions = all.newPermissionCollection();
        allPermissions.add(all);
        allPermissions.setReadOnly();
        ALL_PERMISSIONS = allPermissions;
    }

    static {
        try {
            MethodHandles.Lookup lookup = privateLookupIn(Module.class, lookup());
            implAddUses = lookup.findVirtual(Module.class, "implAddUses", MethodType.methodType(void.class, Class.class));
            @SuppressWarnings("Java9ReflectionClassVisibility")
            Class<?> modules = Class.forName("jdk.internal.module.Modules", false, null);
            addProvides = lookup.findStatic(modules, "addProvides", MethodType.methodType(void.class, Module.class, Class.class, Class.class));
            moduleLayerBindToLoader = lookup.findVirtual(ModuleLayer.class, "bindToLoader", MethodType.methodType(void.class, ClassLoader.class)).asType(MethodType.methodType(void.class, ModuleLayer.class, ModuleClassLoader.class));
        } catch (ClassNotFoundException e) {
            NoClassDefFoundError error = new NoClassDefFoundError(e.getMessage());
            error.setStackTrace(e.getStackTrace());
            throw error;
        } catch (NoSuchMethodException e) {
            NoSuchMethodError error = new NoSuchMethodError(e.getMessage());
            error.setStackTrace(e.getStackTrace());
            throw error;
        } catch (IllegalAccessException e) {
            IllegalAccessError error = new IllegalAccessError(e.getMessage() + " -- use: --add-opens java.base/java.lang=" + myModule.getName() + " --add-exports java.base/jdk.internal.module=" + myModule.getName());
            error.setStackTrace(e.getStackTrace());
            throw error;
        }
    }

    static {
        MethodType methodType = MethodType.methodType(
            ModuleLayer.Controller.class,
            Module.class
        );
        MethodHandle h = null;
        try {
            h = lookup().findVirtual(ModuleLayer.Controller.class, "enableNativeAccess", methodType);
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
        }
        if (h == null) {
            h = MethodHandles.empty(methodType);
        }
        enableNativeAccess = h;
    }

    private Util() {}

    public static String packageName(String className) {
        int idx = className.lastIndexOf('.');
        return idx == -1 ? "" : className.substring(0, idx);
    }

    public static String resourcePackageName(String resourcePath) {
        int idx = resourcePath.lastIndexOf('/');
        return idx == -1 ? "" : resourcePath.substring(0, idx).replace('/', '.');
    }

    static final ModuleFinder EMPTY_MF = new ModuleFinder() {
        public Optional<ModuleReference> find(final String name) {
            return Optional.empty();
        }

        public Set<ModuleReference> findAll() {
            return Set.of();
        }
    };

    static void addUses(Module module, Class<?> type) {
        try {
            implAddUses.invokeExact(module, type);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    static void addProvides(Module m, Class<?> service, Class<?> impl) {
        try {
            addProvides.invokeExact(m, service, impl);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    static void enableNativeAccess(final ModuleLayer.Controller ctl, final Module module) {
        if (ctl == null) {
            return;
        }
        try {
            // force correct method signature
            var ignored = (ModuleLayer.Controller) enableNativeAccess.invokeExact(ctl, module);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new UndeclaredThrowableException(t);
        }
    }

    static void bindLayerToLoader(ModuleLayer layer, ModuleClassLoader loader) {
        try {
            moduleLayerBindToLoader.invokeExact(layer, loader);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    static Set<String> defaultPackageFinder(final List<ResourceLoader> resourceLoaders) {
        return defaultPackageFinder(resourceLoaders, new HashSet<>());
    }

    static Set<String> defaultPackageFinder(List<ResourceLoader> resourceLoaders, HashSet<String> packages) {
        for (ResourceLoader resourceLoader : resourceLoaders) {
            try {
                defaultPackageFinder(resourceLoader.findResource("/"), packages);
            } catch (IOException e) {
                throw new ModuleLoadException("Failed to compute package list from " + resourceLoader, e);
            }
        }
        return packages;
    }

    static void defaultPackageFinder(Resource directory, HashSet<String> packages) throws IOException {
        try (DirectoryStream<Resource> ds = directory.openDirectoryStream()) {
            for (Resource resource : ds) {
                if (resource.isDirectory()) {
                    defaultPackageFinder(resource, packages);
                } else {
                    String pathName = resource.pathName();
                    if (pathName.endsWith(".class")) {
                        int idx = pathName.lastIndexOf('/');
                        if (idx != -1) {
                            packages.add(pathName.substring(0, idx).replace('/', '.'));
                        }
                    }
                }
            }
        }
    }
}


package io.github.dmlloyd.modules;

import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.privateLookupIn;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.AllPermission;
import java.security.PermissionCollection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jdk.internal.module.Modules;

import io.github.dmlloyd.modules.impl.TextIter;

final class Util {
    static final PermissionCollection emptyPermissions;
    static final PermissionCollection allPermissions;
    static final Module myModule = Util.class.getModule();
    static final ModuleLayer myLayer = myModule.getLayer();
    static final Map<String, Module> myLayerModules = myLayer.modules().stream().collect(Collectors.toUnmodifiableMap(
        Module::getName,
        Function.identity()
    ));
    // ↓↓↓↓↓↓↓ private ↓↓↓↓↓↓↓
    private static final MethodHandle enableNativeAccess;
    private static final MethodHandle moduleLayerBindToLoader;

    static {
        // initialize permission collections
        AllPermission all = new AllPermission();
        PermissionCollection epc = all.newPermissionCollection();
        epc.setReadOnly();
        emptyPermissions = epc;
        PermissionCollection apc = all.newPermissionCollection();
        apc.add(all);
        apc.setReadOnly();
        allPermissions = apc;
        // initialize method handles
        try {
            Modules.addOpens(Object.class.getModule(), "java.lang", myModule);
            MethodHandles.Lookup lookup = privateLookupIn(Module.class, lookup());
            moduleLayerBindToLoader = lookup.findVirtual(ModuleLayer.class, "bindToLoader", MethodType.methodType(void.class, ClassLoader.class)).asType(MethodType.methodType(void.class, ModuleLayer.class, ModuleClassLoader.class));
        } catch (NoSuchMethodException e) {
            throw toError(e);
        } catch (IllegalAccessException | IllegalAccessError e) {
            IllegalAccessError error = new IllegalAccessError(e.getMessage() + " -- use: --add-exports java.base/jdk.internal.module=" + myModule.getName());
            error.setStackTrace(e.getStackTrace());
            throw error;
        }
        MethodType methodType = MethodType.methodType(
            ModuleLayer.Controller.class,
            Module.class
        );
        // this one is flexible: it's only since Java 22 (otherwise, ignore)
        MethodHandle h;
        try {
            h = privateLookupIn(ModuleLayer.Controller.class, lookup()).findVirtual(ModuleLayer.Controller.class, "enableNativeAccess", methodType);
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
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
        Modules.addUses(module, type);
    }

    static void addProvides(Module m, Class<?> service, Class<?> impl) {
        Modules.addProvides(m, service, impl);
    }

    static void addExports(Module fromModule, String packageName, Module toModule) {
        Modules.addExports(fromModule, packageName, toModule);
    }

    static void addOpens(Module fromModule, String packageName, Module toModule) {
        Modules.addOpens(fromModule, packageName, toModule);
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

    static String autoModuleName(TextIter iter) {
        StringBuilder sb = new StringBuilder(iter.text().length());
        boolean dot = false;
        while (iter.hasNext()) {
            // parse each "word"; the first numeric is a version
            if (Character.isLetter(iter.peekNext())) {
                dot = false;
                do {
                    sb.appendCodePoint(iter.next());
                } while (iter.hasNext() && Character.isLetterOrDigit(iter.peekNext()));
            } else if (Character.isDigit(iter.peekNext())) {
                if (dot) {
                    // delete dot from string
                    sb.setLength(sb.length() - 1);
                }
                // version starts here
                return sb.toString();
            } else if (! dot) {
                dot = true;
                sb.append('.');
            }
        }
        // no version
        return sb.toString();
    }

    static NoSuchMethodError toError(NoSuchMethodException e) {
        var error = new NoSuchMethodError(e.getMessage());
        error.setStackTrace(e.getStackTrace());
        return error;
    }

    static <K, V> Map<K, V> newHashMap(Object ignored) {
        return new HashMap<>();
    }
}


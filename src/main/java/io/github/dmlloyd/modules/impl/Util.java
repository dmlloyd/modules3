package io.github.dmlloyd.modules.impl;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.dmlloyd.modules.ModuleClassLoader;
import jdk.internal.module.Modules;

/**
 * General utilities for the implementation.
 */
public final class Util {
    private Util() {}

    public static final PermissionCollection emptyPermissions;
    public static final PermissionCollection allPermissions;
    public static final Module myModule = Util.class.getModule();
    public static final ModuleLayer myLayer = myModule.getLayer();
    public static final Map<String, Module> myLayerModules = myLayer.modules().stream()
        .collect(Collectors.toUnmodifiableMap(
            Module::getName,
            Function.identity()
        ));
    public static final ModuleFinder EMPTY_MF = new ModuleFinder() {
        public Optional<ModuleReference> find(final String name) {
            return Optional.empty();
        }

        public Set<ModuleReference> findAll() {
            return Set.of();
        }
    };

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
        MethodType toMethodType = MethodType.methodType(
            void.class,
            Module.class
        );
        // this one is flexible: it's only since Java 22 (otherwise, ignore)
        MethodHandle h = MethodHandles.empty(toMethodType);
        try {
            if (Runtime.version().feature() >= 22) {
                h = privateLookupIn(ModuleLayer.Controller.class, lookup()).findVirtual(ModuleLayer.Controller.class, "enableNativeAccess", methodType).asType(toMethodType);
            }
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
        }
        enableNativeAccess = h;
    }

    public static String packageName(String binaryName) {
        int idx = binaryName.lastIndexOf('.');
        return idx == -1 ? "" : binaryName.substring(0, idx);
    }

    public static String resourcePackageName(String resourcePath) {
        int idx = resourcePath.lastIndexOf('/');
        return idx == -1 ? "" : resourcePath.substring(0, idx).replace('/', '.');
    }

    public static void addUses(Module module, Class<?> type) {
        Modules.addUses(module, type);
    }

    public static void addProvides(Module m, Class<?> service, Class<?> impl) {
        Modules.addProvides(m, service, impl);
    }

    public static void addExports(Module fromModule, String packageName, Module toModule) {
        Modules.addExports(fromModule, packageName, toModule);
    }

    public static void addOpens(Module fromModule, String packageName, Module toModule) {
        Modules.addOpens(fromModule, packageName, toModule);
    }

    public static void enableNativeAccess(final ModuleLayer.Controller ctl, final Module module) {
        if (ctl == null) {
            return;
        }
        try {
            enableNativeAccess.invokeExact(ctl, module);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new UndeclaredThrowableException(t);
        }
    }

    public static void bindLayerToLoader(ModuleLayer layer, ModuleClassLoader loader) {
        try {
            moduleLayerBindToLoader.invokeExact(layer, loader);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    public static String autoModuleName(TextIter iter) {
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
                iter.next();
                dot = true;
                sb.append('.');
            } else {
                // skip
                iter.next();
            }
        }
        // no version
        return sb.toString();
    }

    public static NoSuchMethodError toError(NoSuchMethodException e) {
        var error = new NoSuchMethodError(e.getMessage());
        error.setStackTrace(e.getStackTrace());
        return error;
    }

    public static <K, V> Map<K, V> newHashMap(Object ignored) {
        return new HashMap<>();
    }

    public static <K, V> Collector<Map.Entry<K, V>, ?, Map<K, V>> toMap() {
        return Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    public static <E> Collector<E, ?, List<E>> toList() {
        return Collectors.toUnmodifiableList();
    }

    public static <E> Set<E> merge(Set<E> set1, Set<E> set2) {
        if (set1 == null || set1.isEmpty()) {
            return set2;
        } else if (set2 == null || set2.isEmpty()) {
            return set1;
        } else {
            return Stream.concat(set1.stream(), set2.stream()).collect(Collectors.toUnmodifiableSet());
        }
    }

    public static <E> List<E> concat(List<E> list1, List<E> list2) {
        if (list1 == null || list1.isEmpty()) {
            return list2;
        } else if (list2 == null || list2.isEmpty()) {
            return list1;
        } else {
            return Stream.concat(list1.stream(), list2.stream()).collect(toList());
        }
    }

    public static <K, V> Map<K, V> merge(Map<K, V> map1, Map<K, V> map2) {
        if (map1 == null || map1.isEmpty()) {
            return map2;
        } else if (map2 == null || map2.isEmpty()) {
            return map1;
        } else {
            return Stream.concat(map1.entrySet().stream(), map2.entrySet().stream()).collect(toMap());
        }
    }

    public static <K, V> Map<K, V> merge(Map<K, V> map1, Map<K, V> map2, BinaryOperator<V> merge) {
        if (map1 == null || map1.isEmpty()) {
            return map2;
        } else if (map2 == null || map2.isEmpty()) {
            return map1;
        } else {
            return Stream.concat(map1.entrySet().stream(), map2.entrySet().stream())
                .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    merge
                )
            );
        }
    }
}

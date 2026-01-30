package io.github.dmlloyd.modules.impl;

import static java.lang.invoke.MethodHandles.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;

import io.github.dmlloyd.modules.ModuleClassLoader;

/**
 * Access utilities for the runtime.
 */
public final class Access {
    private Access() {}

    // ↓↓↓↓↓↓↓ private ↓↓↓↓↓↓↓

    private static final Lookup lookup = lookup();
    private static final MethodHandle addOpens;
    private static final MethodHandle addExports;
    private static final MethodHandle addUses;
    private static final MethodHandle addProvides;
    private static final MethodHandle enableNativeAccess;
    private static final MethodHandle moduleLayerBindToLoader;
    private static final MethodHandle newLookup;

    static {
        // initialize method handles
        try {
            Class<?> modules = Class.forName("jdk.internal.module.Modules", true, null);
            addOpens = lookup.findStatic(modules, "addOpens", MethodType.methodType(void.class, Module.class, String.class, Module.class));
            addExports = lookup.findStatic(modules, "addExports", MethodType.methodType(void.class, Module.class, String.class, Module.class));
            addUses = lookup.findStatic(modules, "addUses", MethodType.methodType(void.class, Module.class, Class.class));
            addProvides = lookup.findStatic(modules, "addProvides", MethodType.methodType(void.class, Module.class, Class.class, Class.class));
            addOpens(Object.class.getModule(), "java.lang", Util.myModule);
            Lookup lookup = privateLookupIn(Module.class, Access.lookup);
            moduleLayerBindToLoader = lookup.findVirtual(ModuleLayer.class, "bindToLoader", MethodType.methodType(void.class, ClassLoader.class)).asType(MethodType.methodType(void.class, ModuleLayer.class, ModuleClassLoader.class));
            addOpens(Object.class.getModule(), "java.lang.invoke", Util.myModule);
            Constructor<Lookup> dc = Lookup.class.getDeclaredConstructor(Class.class, Class.class, int.class);
            dc.setAccessible(true);
            newLookup = lookup.unreflectConstructor(dc);
        } catch (NoSuchMethodException e) {
            throw toError(e);
        } catch (ClassNotFoundException e) {
            throw toError(e);
        } catch (IllegalAccessException | IllegalAccessError e) {
            IllegalAccessError error = new IllegalAccessError(e.getMessage() + " -- use: --add-exports java.base/jdk.internal.module=" + Util.myModule.getName());
            error.setStackTrace(e.getStackTrace());
            throw error;
        }
        MethodType methodType = MethodType.methodType(
            Module.class
        );
        MethodType toMethodType = MethodType.methodType(
            void.class,
            Module.class
        );
        // this one is flexible: it's only since Java 22 (otherwise, ignore)
        MethodHandle h = empty(toMethodType);
        try {
            if (Runtime.version().feature() >= 22) {
                //java.lang.Module.implAddEnableNativeAccess
                h = privateLookupIn(Module.class, lookup).findVirtual(Module.class, "implAddEnableNativeAccess", methodType).asType(toMethodType);
            }
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
        }
        enableNativeAccess = h;
    }

    // ↓↓↓↓↓↓↓ module-public ↓↓↓↓↓↓↓

    public static void addUses(Module module, Class<?> type) {
        try {
            addUses.invokeExact(module, type);
        } catch (Throwable e) {
            throw sneaky(e);
        }
    }

    public static void addProvides(Module m, Class<?> service, Class<?> impl) {
        try {
            addProvides.invokeExact(m, service, impl);
        } catch (Throwable e) {
            throw sneaky(e);
        }
    }

    public static void addExports(Module fromModule, String packageName, Module toModule) {
        try {
            addExports.invokeExact(fromModule, packageName, toModule);
        } catch (Throwable e) {
            throw sneaky(e);
        }
    }

    public static void addOpens(Module fromModule, String packageName, Module toModule) {
        try {
            addOpens.invokeExact(fromModule, packageName, toModule);
        } catch (Throwable e) {
            throw sneaky(e);
        }
    }

    public static void enableNativeAccess(final Module module) {
        try {
            enableNativeAccess.invokeExact(module);
        } catch (Throwable e) {
            throw sneaky(e);
        }
    }

    public static void bindLayerToLoader(ModuleLayer layer, ModuleClassLoader loader) {
        try {
            moduleLayerBindToLoader.invokeExact(layer, loader);
        } catch (Throwable e) {
            throw sneaky(e);
        }
    }

    public static Lookup originalLookup(Class<?> clazz) {
        assert clazz.getClassLoader() instanceof ModuleClassLoader;
        try {
            return (Lookup) newLookup.invokeExact(clazz, (Class<?>) null, lookup.lookupModes());
        } catch (Throwable e) {
            throw sneaky(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }

    private static NoSuchMethodError toError(NoSuchMethodException e) {
        var error = new NoSuchMethodError(e.getMessage());
        error.setStackTrace(e.getStackTrace());
        return error;
    }

    private static NoClassDefFoundError toError(ClassNotFoundException e) {
        var error = new NoClassDefFoundError(e.getMessage());
        error.setStackTrace(e.getStackTrace());
        return error;
    }
}

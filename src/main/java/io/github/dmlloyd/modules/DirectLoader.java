package io.github.dmlloyd.modules;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;

/**
 * A loader for classes and resources.
 */
public interface DirectLoader {

    /**
     * Load the named class directly from this loader.
     * Array or primitive types are not supported.
     * The name must be dot-separated ("{@code .}").
     *
     * @param name the class name (must not be {@code null})
     * @return the loaded class (not {@code null})
     * @throws ClassNotFoundException if the class is not found in this loader
     */
    Class<?> loadClassDirect(String name) throws ClassNotFoundException;

    /**
     * Load the name resources directly from this loader.
     * The name must be slash-separated ("{@code /}").
     *
     * @param name the resource name (must not be {@code null})
     * @return the list of resources with the given name (not {@code null})
     * @throws IOException if loading resources failed
     */
    default List<Resource> loadResourcesDirect(String name) throws IOException {
        return List.of();
    }

    default Resource loadResourceDirect(String name) throws IOException {
        List<Resource> list = loadResourcesDirect(name);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Load a package directly from this loader.
     * The name must be dot-separated ("{@code .}").
     *
     * @param name the package name (must not be {@code null})
     * @return the package, or {@code null} if the package isn't found
     */
    default Package loadPackageDirect(String name) {
        return null;
    }

    /**
     * Load a module directly from this loader.
     *
     * @param name the module name (must not be {@code null})
     * @return the loaded module (not {@code null})
     * @throws ModuleNotFoundException if the module is not found in this loader
     */
    default Module loadModuleDirect(String name) throws ModuleNotFoundException {
        throw new ModuleNotFoundException("No modules in this loader");
    }

    /**
     * {@return the set of exported packages from this loader}
     * By default, no packages are exported.
     */
    default Set<String> exportedPackages() {
        return Set.of();
    }

    /**
     * Construct a direct loader instance for the given class loader.
     * If the class loader implements {@code DirectLoader}, it is returned.
     * Otherwise, the resultant loader does not load any resources, packages, or modules (only classes).
     * Care must be taken to ensure that the class loader is implemented correctly.
     *
     * @param classLoader the class loader (must not be {@code null})
     * @return a direct loader which loads from the class loader (not {@code null})
     */
    static DirectLoader forClassLoader(ClassLoader classLoader) {
        if (classLoader instanceof DirectLoader dl) {
            return dl;
        } else if (classLoader == null) {
            return BOOT;
        } else if (classLoader == ClassLoader.getPlatformClassLoader()) {
            return PLATFORM;
        } else if (classLoader == ClassLoader.getSystemClassLoader()) {
            return SYSTEM;
        } else {
            return classLoader::loadClass;
        }
    }

    static DirectLoader forModule(Module module) {
        if (module.getClassLoader() instanceof DirectLoader dl) {
            return dl;
        }
        DirectLoader dl = DirectLoader.forClassLoader(module.getClassLoader());
        return new DirectLoader() {
            public Class<?> loadClassDirect(final String name) throws ClassNotFoundException {
                String dotName = name.replace('/', '.');
                int dot = dotName.lastIndexOf('.');
                if (dot != -1 && ! module.getPackages().contains(dotName.substring(0, dot))) {
                    throw new ClassNotFoundException(dotName);
                }
                return dl.loadClassDirect(name);
            }

            public Set<String> exportedPackages() {
                return module.getPackages().stream().filter(module::isExported).collect(Collectors.toUnmodifiableSet());
            }

            public Package loadPackageDirect(final String name) {
                return module.getPackages().contains(name) ? dl.loadPackageDirect(name) : null;
            }

            public Module loadModuleDirect(final String name) throws ModuleNotFoundException {
                if (name.equals(module.getName())) {
                    return module;
                } else {
                    throw new ModuleNotFoundException("Module " + name + " not found in loader for module " + module.getName());
                }
            }
        };
    }

    static DirectLoader forModuleLayer(ModuleLayer layer) {
        Map<String, DirectLoader> loadersByModuleName = layer.modules()
            .stream()
            .map(m -> Map.entry(m.getName(), forModule(m)))
            .collect(mapCollector());
        Map<String, DirectLoader> loadersByPackage = loadersByModuleName.values()
            .stream()
            .flatMap(l -> l.exportedPackages().stream().map(p -> Map.entry(p, l)))
            .collect(mapCollector());
        return new DirectLoader() {
            public Class<?> loadClassDirect(final String name) throws ClassNotFoundException {
                String dotName = name.replace('/', '.');
                int dot = dotName.lastIndexOf('.');
                if (dot != - 1) {
                    DirectLoader loader = loadersByPackage.get(dotName.substring(0, dot));
                    if (loader != null) {
                        return loader.loadClassDirect(dotName);
                    }
                }
                throw new ClassNotFoundException(name);
            }

            public Package loadPackageDirect(final String name) {
                DirectLoader loader = loadersByPackage.get(name);
                return loader == null ? null : loader.loadPackageDirect(name);
            }

            public Set<String> exportedPackages() {
                return loadersByPackage.keySet();
            }

            public Module loadModuleDirect(final String name) throws ModuleNotFoundException {
                DirectLoader loader = loadersByModuleName.get(name);
                if (loader == null) {
                    throw new ModuleNotFoundException(name);
                }
                return loader.loadModuleDirect(name);
            }
        };
    }

    private static <K, V> Collector<Map.Entry<K, V>, ?, Map<K, V>> mapCollector() {
        return Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    DirectLoader SYSTEM = ClassLoader.getSystemClassLoader()::loadClass;
    DirectLoader PLATFORM = forModuleLayer(ModuleLayer.boot());
    DirectLoader BOOT = name -> Class.forName(name, false, null);


    static DirectLoader forResourceLoader(ResourceLoader resourceLoader) {
        if (resourceLoader instanceof DirectLoader dl) {
            return dl;
        } else {
            return new DirectLoader() {
                public Class<?> loadClassDirect(final String name) throws ClassNotFoundException {
                    throw new ClassNotFoundException(name);
                }

                public List<Resource> loadResourcesDirect(final String name) throws IOException {
                    return List.of(resourceLoader.findResource(name));
                }
            };
        }
    }

    static DirectLoader filtered(DirectLoader delegate, Predicate<? super String> predicate) {
        return new DirectLoader() {
            public Class<?> loadClassDirect(final String name) throws ClassNotFoundException {
                String dotName = name.replace('/', '.');
                int dotIdx = dotName.lastIndexOf('.');
                if (dotIdx == -1) {
                    return delegate.loadClassDirect(name);
                }
                if (predicate.test(dotName.substring(0, dotIdx))) {
                    return delegate.loadClassDirect(name);
                } else throw new ClassNotFoundException(dotName);
            }

            public Set<String> exportedPackages() {
                return delegate.exportedPackages().stream().filter(predicate).collect(Collectors.toUnmodifiableSet());
            }
        };
    }
}

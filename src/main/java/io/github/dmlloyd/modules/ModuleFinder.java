package io.github.dmlloyd.modules;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.desc.ResourceLoaderOpener;
import io.smallrye.common.resource.JarFileResourceLoader;
import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;

/**
 * A module definition finder.
 */
public interface ModuleFinder extends Closeable {
    ModuleDescriptor findModule(String name);

    default ModuleFinder andThen(ModuleFinder other) {
        if (this == EMPTY) {
            return other;
        }
        return name -> {
            ModuleDescriptor res = findModule(name);
            return res != null ? res : other.findModule(name);
        };
    }

    default void close() {}

    ModuleFinder EMPTY = __ -> null;

    static ModuleFinder fromFileSystem(List<Path> roots) {
        List<Path> paths = List.copyOf(roots);
        return new ModuleFinder() {

            public ModuleDescriptor findModule(final String name) {
                for (Path realPath : paths) {
                    int idx = 0;
                    int dot = name.indexOf('.');
                    while (dot != -1) {
                        realPath = realPath.resolve(name.substring(idx, dot));
                        idx = dot + 1;
                        dot = name.indexOf('.', idx);
                    }
                    realPath = realPath.resolve(name.substring(idx));
                    if (! Files.isDirectory(realPath)) {
                        return null;
                    }
                    List<Path> items = null;
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(realPath)) {
                        for (Path subPath : ds) {
                            if (subPath.getFileName().toString().endsWith(".jar") && Files.isRegularFile(subPath)) {
                                if (items == null) {
                                    // common case
                                    items = List.of(subPath);
                                } else if (items.size() == 1) {
                                    // uncommon
                                    ArrayList<Path> newList = new ArrayList<>(8);
                                    newList.addAll(items);
                                    items = newList;
                                } else {
                                    // uncommon
                                    items.add(subPath);
                                }
                            }
                        }
                    } catch (NoSuchFileException | FileNotFoundException notFound) {
                        return null;
                    } catch (Throwable t) {
                        throw new ModuleLoadException("Failed to read directory for module " + name, t);
                    }
                    if (items != null) {
                        if (items.size() > 1) {
                            // ensure consistent behavior across file systems
                            items.sort(Comparator.comparing(p -> p.getFileName().toString()));
                        }
                        List<ResourceLoader> resourceLoaders = new ArrayList<>(items.size());
                        try {
                            for (Path item : items) {
                                try {
                                    resourceLoaders.add(new JarFileResourceLoader(item));
                                } catch (IOException e) {
                                    throw new ModuleLoadException("Failed to open JAR file", e);
                                }
                            }
                            // now, find the module descriptor
                            for (ResourceLoader resourceLoader : resourceLoaders) {
                                Resource resource;
                                try {
                                    resource = resourceLoader.findResource("module-info.class");
                                    if (resource != null) {
                                        byte[] bytes;
                                        try (InputStream is = resource.openStream()) {
                                            bytes = is.readAllBytes();
                                        }
                                        return ModuleDescriptor.fromModuleInfo(bytes, () -> {
                                            throw new UnsupportedOperationException("todo");
                                        }).withResourceLoaders(resourceLoaders.stream().map(ResourceLoaderOpener::forLoader).toList());
                                    }
                                } catch (NoSuchFileException | FileNotFoundException ignored) {
                                    // just try the next one
                                } catch (IOException e) {
                                    throw new ModuleLoadException("Failed to load module descriptor", e);
                                }
                            }
                            throw new ModuleLoadException("No JARs in " + realPath + " contain a module descriptor");
                        } catch (Throwable t) {
                            for (ResourceLoader resourceLoader : resourceLoaders) {
                                try {
                                    resourceLoader.close();
                                } catch (Throwable t2) {
                                    t.addSuppressed(t2);
                                }
                            }
                            throw t;
                        }
                    }
                }
                // no valid module found
                return null;
            }
        };
    }
}

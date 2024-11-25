package io.github.dmlloyd.modules;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.github.dmlloyd.modules.desc.ResourceLoaderOpener;
import io.smallrye.common.resource.JarFileResourceLoader;
import io.smallrye.common.resource.PathResource;
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
                    Path moduleXml = null;
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(realPath)) {
                        for (Path subPath : ds) {
                            if (subPath.getFileName().toString().equals("module.xml") && Files.isRegularFile(subPath)) {
                                moduleXml = subPath;
                            } else if (subPath.getFileName().toString().endsWith(".jar") && Files.isRegularFile(subPath)) {
                                if (items == null) {
                                    // common case
                                    items = List.of(subPath);
                                } else if (items.size() == 1) {
                                    // uncommon
                                    ArrayList<Path> newList = new ArrayList<>(8);
                                    newList.addAll(items);
                                    newList.add(subPath);
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
                                // todo - temp workaround for https://github.com/smallrye/smallrye-common/pull/378
                                PathResource pr = new PathResource(item.toString(), item);
                                try {
                                    resourceLoaders.add(new JarFileResourceLoader(pr));
                                } catch (IOException e) {
                                    throw new ModuleLoadException("Failed to open JAR file", e);
                                }
                            }
                            // now, find the module descriptor
                            if (moduleXml != null) {
                                try (BufferedReader br = Files.newBufferedReader(moduleXml, StandardCharsets.UTF_8)) {
                                    XMLStreamReader xml = XMLInputFactory.newDefaultFactory().createXMLStreamReader(br);
                                    try (XMLCloser ignored = xml::close) {
                                        return ModuleDescriptor.fromXml(xml)
                                            .withResourceLoaders(resourceLoaders.stream().map(ResourceLoaderOpener::forLoader).toList());
                                    }
                                } catch (XMLStreamException | IOException e) {
                                    throw new ModuleLoadException("Failed to read " + moduleXml, e);
                                }
                            }
                            for (ResourceLoader resourceLoader : resourceLoaders) {
                                Resource resource;
                                try {
                                    resource = resourceLoader.findResource("module-info.class");
                                    if (resource != null) {
                                        byte[] bytes;
                                        try (InputStream is = resource.openStream()) {
                                            bytes = is.readAllBytes();
                                        }
                                        return ModuleDescriptor.fromModuleInfo(bytes, () -> defaultPackageFinder(resourceLoaders))
                                            .withResourceLoaders(resourceLoaders.stream().map(ResourceLoaderOpener::forLoader).toList());
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


    private static Set<String> defaultPackageFinder(final List<ResourceLoader> resourceLoaders) {
        return defaultPackageFinder(resourceLoaders, new HashSet<>());
    }

    private static Set<String> defaultPackageFinder(List<ResourceLoader> resourceLoaders, HashSet<String> packages) {
        for (ResourceLoader resourceLoader : resourceLoaders) {
            try {
                defaultPackageFinder(resourceLoader.findResource("/"), packages);
            } catch (IOException e) {
                throw new ModuleLoadException("Failed to compute package list from " + resourceLoader, e);
            }
        }
        return packages;
    }

    private static void defaultPackageFinder(Resource directory, HashSet<String> packages) throws IOException {
        try (DirectoryStream<Resource> ds = directory.openDirectoryStream()) {
            for (Resource resource : ds) {
                if (resource.isDirectory()) {
                    defaultPackageFinder(resource, packages);
                } else if (resource.pathName().endsWith(".class")) {
                    int idx = resource.pathName().lastIndexOf('/');
                    if (idx != -1) {
                        packages.add(resource.pathName().substring(0, idx).replace('/', '.'));
                    }
                }
            }
        }
    }

    interface XMLCloser extends AutoCloseable {
        void close() throws XMLStreamException;
    }
}

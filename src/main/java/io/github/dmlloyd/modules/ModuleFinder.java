package io.github.dmlloyd.modules;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;

/**
 * A module definition finder.
 */
public interface ModuleFinder extends Closeable {
    FoundModule findModule(String name);

    default ModuleFinder andThen(ModuleFinder other) {
        if (this == EMPTY) {
            return other;
        }
        return name -> {
            var res = findModule(name);
            return res != null ? res : other.findModule(name);
        };
    }

    default void close() {}

    ModuleFinder EMPTY = __ -> null;

    static ModuleFinder fromFileSystem(List<Path> roots) {
        List<Path> paths = List.copyOf(roots);
        return new ModuleFinder() {

            public FoundModule findModule(final String name) {
                interface XMLCloser extends AutoCloseable {
                    void close() throws XMLStreamException;
                }
                for (Path realPath : paths) {
                    realPath = realPath.resolve(name);
                    if (! Files.isDirectory(realPath)) {
                        return null;
                    }
                    List<Path> items = null;
                    Path moduleXml = null;
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(realPath)) {
                        for (Path subPath : ds) {
                            if (subPath.endsWith("module.xml") && Files.isRegularFile(subPath)) {
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
                        List<ResourceLoaderOpener> openers = new ArrayList<>(items.size());
                        for (Path item : items) {
                            openers.add(ResourceLoaderOpener.forJarFile(item));
                        }
                        final Path finalModuleXml = moduleXml;
                        return new FoundModule(openers, (moduleName, loaders) -> {
                            // now, find the module descriptor
                            if (finalModuleXml != null) {
                                try (BufferedReader br = Files.newBufferedReader(finalModuleXml, StandardCharsets.UTF_8)) {
                                    XMLStreamReader xml = XMLInputFactory.newDefaultFactory().createXMLStreamReader(br);
                                    try (XMLCloser ignored = xml::close) {
                                        return ModuleDescriptor.fromXml(xml);
                                    }
                                } catch (XMLStreamException | IOException e) {
                                    throw new ModuleLoadException("Failed to read " + finalModuleXml, e);
                                }
                            }
                            for (ResourceLoader resourceLoader : loaders) {
                                Resource resource;
                                try {
                                    resource = resourceLoader.findResource("module-info.class");
                                    if (resource != null) {
                                        return ModuleDescriptor.fromModuleInfo(resource, loaders);
                                    }
                                } catch (NoSuchFileException | FileNotFoundException ignored) {
                                    // just try the next one
                                } catch (IOException e) {
                                    throw new ModuleLoadException("Failed to load module descriptor", e);
                                }
                            }
                            throw new ModuleLoadException("No JARs contain a module descriptor");
                        });
                    }
                }
                // no valid module found
                return null;
            }
        };
    }
}

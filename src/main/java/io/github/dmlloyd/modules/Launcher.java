package io.github.dmlloyd.modules;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.LogManager;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.dmlloyd.modules.desc.Dependency;
import io.github.dmlloyd.modules.desc.PackageAccess;
import io.github.dmlloyd.modules.impl.Util;
import io.smallrye.common.constraint.Assert;
import io.smallrye.common.resource.JarFileResourceLoader;

/**
 * The main entry point to bootstrap a basic modular environment.
 */
public final class Launcher implements Runnable {
    private final Configuration configuration;

    public Launcher(Configuration configuration) {
        this.configuration = Assert.checkNotNullParam("configuration", configuration);
    }

    public void run() {
        ModuleLoader parent = ModuleLoader.ofClass(Launcher.class);
        if (parent == null) {
            ModuleLayer launcherLayer = Util.myLayer;
            if (launcherLayer == null) {
                launcherLayer = ModuleLayer.boot();
            }
            parent = ModuleLoader.forLayer("launcher", launcherLayer);
        }
        ModuleFinder filesystemModuleFinder = ModuleFinder.fromFileSystem(configuration.modulePath());
        ModuleLoader loader = new DelegatingModuleLoader("init", filesystemModuleFinder, parent);
        String launchName = configuration.launchName();
        Module bootModule;
        String mainClassName;
        Class<?> mainClass;
        switch (configuration.launchMode()) {
            case JAR -> {
                JarFileModuleFinder jarFinder;
                try {
                    Path launchPath = Path.of(launchName);
                    JarFileResourceLoader rl = new JarFileResourceLoader(launchPath);
                    try {
                        jarFinder = new JarFileModuleFinder(rl, launchPath.getFileName().toString(), configuration.accesses());
                    } catch (Throwable t) {
                        try {
                            rl.close();
                        } catch (Throwable t2) {
                            t.addSuppressed(t2);
                        }
                        throw t;
                    }
                } catch (IOException e) {
                    throw new ModuleLoadException("Failed to open boot module JAR \"" + launchName + "\"", e);
                }
                ModuleLoader jarLoader = new DelegatingModuleLoader("launcher", jarFinder, loader);
                LoadedModule loaded = jarLoader.loadModule(jarFinder.descriptor().name());
                // we gave the name that we got from the finder
                assert loaded != null;
                bootModule = loaded.module();
                mainClassName = jarFinder.descriptor().mainClass().orElseThrow(NoSuchElementException::new);
            }
            case MODULE -> {
                String moduleName;
                int idx = launchName.indexOf('/');
                if (idx == -1) {
                    moduleName = launchName;
                } else {
                    moduleName = launchName.substring(0, idx);
                }
                LoadedModule loaded = loader.loadModule(moduleName);
                if (loaded == null) {
                    throw new ModuleLoadException("Failed to locate boot module \"" + moduleName + "\"");
                }
                bootModule = loaded.module();
                if (idx == -1) {
                    if (loaded.classLoader() instanceof ModuleClassLoader mcl) {
                        mainClassName = mcl.mainClassName();
                    } else {
                        ModuleDescriptor descriptor = bootModule.getDescriptor();
                        if (descriptor != null) {
                            mainClassName = descriptor.mainClass().orElseThrow(NoSuchElementException::new);
                        } else {
                            mainClassName = null;
                        }
                    }
                } else {
                    mainClassName = launchName.substring(idx + 1);
                }
            }
            default -> throw new IllegalStateException();
        }
        if (configuration.infoOnly()) {
            printModuleInfo(bootModule, new HashSet<>(), new HashSet<>(), "");
            return;
        }
        if (mainClassName == null) {
            throw new IllegalArgumentException("No main class found on boot module \"" + bootModule + "\"");
        }
        // programs which depend on TCCL are wrong; however, no reason to cause trouble over it
        ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(bootModule.getClassLoader());
            try {
                mainClass = bootModule.getClassLoader().loadClass(mainClassName);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Failed to load main class", e);
            }
            MethodHandle mainMethod;
            try {
                mainMethod = MethodHandles.publicLookup().findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class));
            } catch (NoSuchMethodException e) {
                System.err.printf("No main method found on %s", mainClass);
                System.err.flush();
                return;
            } catch (IllegalAccessException e) {
                System.err.printf("Main method not accessible on %s", mainClass);
                System.err.flush();
                return;
            }
            String[] argsArray = configuration.arguments.toArray(String[]::new);
            try {
                //noinspection ConfusingArgumentToVarargsMethod
                mainMethod.invokeExact(argsArray);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new UndeclaredThrowableException(t);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(oldCl);
        }
    }

    private void printModuleInfo(final Module module, final Set<Module> visited, final Set<Module> path, final String prefix) {
        System.out.print(module.getName());
        module.getDescriptor().rawVersion().ifPresent(v -> System.out.print("@" + v));
        boolean wasVisited = ! visited.add(module);
        if (path.add(module)) {
            try {
                // not a loop
                ClassLoader cl = module.getClassLoader();
                if (cl instanceof ModuleClassLoader mcl) {
                    System.out.println();
                    List<Dependency> dependencies = mcl.linkDependencies().dependencies();
                    Iterator<LoadedModule> iter = Util.mapped(Util.filtered(dependencies.iterator(), Dependency::isNonSynthetic), d -> d.moduleLoader().orElse(mcl.moduleLoader()).loadModule(d.moduleName()));
                    if (iter.hasNext()) {
                        if (wasVisited) {
                            return;
                        }
                        LoadedModule current = iter.next();
                        while (iter.hasNext()) {
                            LoadedModule next = iter.next();
                            System.out.print(prefix);
                            System.out.print(" ├─ ");
                            printModuleInfo(current.module(), visited, path, prefix.concat(" │  "));
                            current = next;
                        }
                        System.out.print(prefix);
                        System.out.print(" └─ ");
                        printModuleInfo(current.module(), visited, path, prefix.concat("    "));
                    }
                } else {
                    System.out.print(" (");
                    if (cl == null) {
                        System.out.print("boot");
                    } else if (cl.getName() == null) {
                        System.out.print(cl);
                    } else {
                        System.out.print(cl.getName());
                    }
                    System.out.println(")");
                }
            } finally {
                path.remove(module);
            }
        } else {
            // a loop; end here
            System.out.println(" ↺");
        }
    }

    public static void main(String[] args) {
        main(List.of(args));
    }

    /**
     * The main entry point.
     *
     * @param args the command-line arguments (must not be {@code null})
     */
    public static void main(List<String> args) {
        // force logging initialization
        ServiceLoader<LogManager> logManagerLoader = ServiceLoader.load(LogManager.class);
        Iterator<LogManager> lmIter = logManagerLoader.iterator();
        while (true) try {
            if (! lmIter.hasNext()) break;
            lmIter.next();
        } catch (ServiceConfigurationError ignored) {
        }
        args = List.copyOf(args);
        Iterator<String> iterator = args.iterator();
        List<Path> modulePath = List.of(Path.of("."));
        Map<String, Map<String, PackageAccess>> accesses = Map.of();
        Mode mode = Mode.MODULE;
        boolean infoOnly = false;
        while (iterator.hasNext()) {
            String argument = iterator.next();
            switch (argument) {
                case "-h", "--help" -> {
                    System.out.printf("""
                        Usage: java [<jvm options>...] -m io.github.dmlloyd.modules [<options...>] <module-name>[/<class-name>]
                               java [<jvm options>...] -m io.github.dmlloyd.modules [<options...>] --jar <jar-file>
                        where <module-name> is a valid module name, <jar-file> is a JAR file, and <options> is any of:
                            --help                    Display this message
                            -mp,--module-path <paths> Specifies the location of the module root(s)
                                                      as a list of paths separated by `%s` characters
                            --jar                     Run a modular JAR in the modular environment
                            --info                    Display information about the module instead of running it
                                                
                        Additionally, it is recommended to pass --enable-native-access=%s on
                        Java 22 or later to ensure that permitted submodules can also have native access enabled.
                        Granting this capability will transitively grant the capability to any module which is configured
                        for native access.
                        """, File.pathSeparator, Objects.requireNonNullElse(Util.myModule.getName(), "ALL-UNNAMED"));
                    System.out.flush();
                    System.exit(0);
                }
                case "--version" -> {
                    Module myModule = Launcher.class.getModule();
                    String version;
                    if (myModule.isNamed()) {
                        version = myModule.getDescriptor().rawVersion().orElse("<unknown>");
                    } else {
                        version = "<unknown>";
                    }
                    System.out.printf("Version: %s%n", version);
                    System.out.flush();
                    System.exit(0);
                }
                case "-mp", "--module-path" -> {
                    if (! iterator.hasNext()) {
                        System.err.printf("Option `%s` requires an argument%n", argument);
                        System.err.flush();
                        System.exit(1);
                    }
                    modulePath = Stream.of(iterator.next().split(File.pathSeparator)).map(Path::of).collect(Util.toList());
                }
                case "--jar" -> {
                    if (mode != Mode.MODULE) {
                        System.err.println("Option --jar may only be given once");
                        System.err.flush();
                        System.exit(1);
                    }
                    mode = Mode.JAR;
                }
                case "--info" -> {
                    if (infoOnly) {
                        System.err.println("Option --info may only be given once");
                        System.err.flush();
                        System.exit(1);
                    }
                    infoOnly = true;
                }
                case "--list-boot-modules" -> {
                    printAllModules(Util.myLayer);
                }
                case "--add-exports", "--add-opens" -> {
                    // export to the main module
                    // format: module/packageName
                    if (! iterator.hasNext()) {
                        System.err.printf("Option `%s` requires an argument%n", argument);
                        System.err.flush();
                        System.exit(1);
                    }
                    String str = iterator.next();
                    int idx = str.indexOf('/');
                    if (idx == -1) {
                        System.err.printf("Expected argument in <moduleName>/<packageName> format%n");
                        System.err.flush();
                        System.exit(1);
                    }
                    String moduleName = str.substring(0, idx);
                    String packageName = str.substring(idx + 1);
                    if (accesses.isEmpty()) {
                        accesses = new HashMap<>();
                    }
                    switch (argument) {
                        // use putIfAbsent for exports so it doesn't override opens
                        case "--add-exports" -> accesses.computeIfAbsent(moduleName, Util::newHashMap).putIfAbsent(packageName, PackageAccess.EXPORTED);
                        // use put for opens so it always overrides exports
                        case "--add-opens" -> accesses.computeIfAbsent(moduleName, Util::newHashMap).put(packageName, PackageAccess.OPEN);
                    }
                }
                default -> {
                    if (argument.startsWith("-")) {
                        System.err.printf("Unrecognized option `%s`. Try `--help` for a list of supported options.%n", argument);
                        System.err.flush();
                        System.exit(1);
                    }
                    Configuration conf = new Configuration(argument, mode, infoOnly, modulePath, Util.listOf(iterator), accesses);
                    Launcher launcher = new Launcher(conf);
                    launcher.run();
                    return;
                }
            }
        }
        System.err.println("No module name given");
        System.err.flush();
        System.exit(1);
    }

    private static void printAllModules(ModuleLayer layer) {
        for (Module module : layer.modules()) {
            System.out.println(module.getName() + " (" + module.getClassLoader() + ")");
        }
        for (ModuleLayer parent : layer.parents()) {
            printAllModules(parent);
        }
    }

    public enum Mode {
        MODULE,
        JAR,
    }

    public record Configuration(String launchName, Mode launchMode, boolean infoOnly, List<Path> modulePath, List<String> arguments,
                                Map<String, Map<String, PackageAccess>> accesses) {
        public Configuration {
            Assert.checkNotNullParam("launchName", launchName);
            Assert.checkNotNullParam("launchMode", launchMode);
            modulePath = List.copyOf(modulePath);
            arguments = List.copyOf(arguments);
            accesses = accesses.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
        }
    }
}

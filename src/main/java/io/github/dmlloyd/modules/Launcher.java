package io.github.dmlloyd.modules;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

import io.smallrye.common.constraint.Assert;

/**
 * The main entry point to bootstrap a basic modular environment.
 */
public final class Launcher implements Runnable {
    private final Configuration configuration;

    public Launcher(Configuration configuration) {
        this.configuration = Assert.checkNotNullParam("configuration", configuration);
    }

    public void run() {
        ModuleFinder finder = ModuleFinder.fromFileSystem(configuration.modulePath());
        ModuleLoader loader = new DelegatingModuleLoader("app", finder, ModuleLoader.forLayer("boot", getClass().getModule().getLayer()));
        String launchName = configuration.launchName();
        Module bootModule;
        String mainClassName;
        Class<?> mainClass;
        switch (configuration.launchMode()) {
            case JAR -> {
                JarFileModuleFinder jarFinder;
                try {
                    jarFinder = new JarFileModuleFinder(Path.of(launchName));
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
                            mainClassName = descriptor.mainClass().orElseThrow(IllegalArgumentException::new);
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
            System.out.printf("""
                TODO: print module info
                
                """);
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

    /**
     * The runnable main entry point.
     * This method exits the JVM when the program finishes.
     *
     * @param args the command-line arguments (must not be {@code null})
     */
    public static void main(String[] args) {
        System.exit(main(List.of(args)));
    }

    /**
     * The main entry point.
     *
     * @param args the command-line arguments (must not be {@code null})
     * @return the exit code (0 for success, and any other value for failure)
     */
    public static int main(List<String> args) {
        args = List.copyOf(args);
        Iterator<String> iterator = args.iterator();
        List<Path> modulePath = List.of(Path.of("."));
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
                        
                        Additionally, it is recommended to pass --enable-native-access=io.github.dmlloyd.modules on
                        Java 22 or later to ensure that permitted submodules can also have native access enabled.
                        Granting this capability will transitively grant the capability to any module which is configured
                        for native access.
                        """, File.pathSeparator);
                    System.out.flush();
                    return 0;
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
                    return 0;
                }
                case "-mp", "--module-path" -> {
                    if (! iterator.hasNext()) {
                        System.err.printf("Option `%s` requires an argument%n", argument);
                        System.err.flush();
                        return 1;
                    }
                    modulePath = Stream.of(iterator.next().split(File.pathSeparator)).map(Path::of).toList();
                }
                case "--jar" -> {
                    if (mode != Mode.MODULE) {
                        System.err.println("Option --jar may only be given once");
                        System.err.flush();
                        return 1;
                    }
                    mode = Mode.JAR;
                }
                case "--info" -> {
                    if (infoOnly) {
                        System.err.println("Option --info may only be given once");
                        System.err.flush();
                        return 1;
                    }
                    infoOnly = true;
                }
                default -> {
                    if (argument.startsWith("-")) {
                        System.err.printf("Unrecognized option `%s`. Try `--help` for a list of supported options.%n", argument);
                        System.err.flush();
                        return 1;
                    }
                    Configuration conf = new Configuration(argument, mode, infoOnly, modulePath, listOf(iterator));
                    Launcher launcher = new Launcher(conf);
                    launcher.run();
                    return 0;
                }
            }
        }
        System.err.println("No module name given");
        System.err.flush();
        return 1;
    }

    private static <T> List<T> listOf(Iterator<T> iter) {
        if (iter.hasNext()) {
            T t0 = iter.next();
            if (iter.hasNext()) {
                T t1 = iter.next();
                if (iter.hasNext()) {
                    // too many
                    ArrayList<T> list = new ArrayList<>();
                    list.add(t0);
                    list.add(t1);
                    iter.forEachRemaining(list::add);
                    return List.copyOf(list);
                } else {
                    return List.of(t0, t1);
                }
            } else {
                return List.of(t0);
            }
        } else {
            return List.of();
        }
    }

    public enum Mode {
        MODULE,
        JAR,
    }

    public record Configuration(String launchName, Mode launchMode, boolean infoOnly, List<Path> modulePath, List<String> arguments) {
        public Configuration {
            Assert.checkNotNullParam("launchName", launchName);
            Assert.checkNotNullParam("launchMode", launchMode);
            modulePath = List.copyOf(modulePath);
            arguments = List.copyOf(arguments);
        }
    }
}

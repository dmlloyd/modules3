package io.github.dmlloyd.modules.desc;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.dmlloyd.classfile.Annotation;
import io.github.dmlloyd.classfile.Attributes;
import io.github.dmlloyd.classfile.ClassFile;
import io.github.dmlloyd.classfile.ClassModel;
import io.github.dmlloyd.classfile.attribute.ModuleAttribute;
import io.github.dmlloyd.classfile.attribute.ModuleMainClassAttribute;
import io.github.dmlloyd.classfile.attribute.ModulePackagesAttribute;
import io.github.dmlloyd.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import io.github.dmlloyd.classfile.constantpool.ClassEntry;
import io.github.dmlloyd.classfile.constantpool.ModuleEntry;
import io.github.dmlloyd.classfile.constantpool.PackageEntry;
import io.github.dmlloyd.classfile.constantpool.Utf8Entry;
import io.github.dmlloyd.classfile.extras.reflect.AccessFlag;
import io.github.dmlloyd.modules.ModuleClassLoader;
import io.github.dmlloyd.modules.NativeAccess;
import io.smallrye.common.constraint.Assert;

/**
 * A descriptor for initially defining a module.
 */
public record ModuleDescriptor(
    String name,
    Optional<String> version,
    Optional<String> classLoaderName,
    Modifiers<Modifier> modifiers,
    Optional<String> mainClass,
    Function<ModuleClassLoader.ClassLoaderConfiguration, ModuleClassLoader> classLoaderFactory,
    List<Dependency> dependencies,
    Set<Export> exports,
    Set<Open> opens,
    Set<String> uses,
    Set<Provide> provides,
    List<ResourceLoaderOpener> resourceLoaderOpeners,
    Set<String> packages
) {

    public ModuleDescriptor {
        Assert.checkNotNullParam("name", name);
        Assert.checkNotNullParam("version", version);
        Assert.checkNotNullParam("classLoaderName", classLoaderName);
        Assert.checkNotNullParam("modifiers", modifiers);
        Assert.checkNotNullParam("mainClass", mainClass);
        Assert.checkNotNullParam("classLoaderFactory", classLoaderFactory);
        dependencies = List.copyOf(dependencies);
        exports = Set.copyOf(exports);
        opens = Set.copyOf(opens);
        uses = Set.copyOf(uses);
        provides = Set.copyOf(provides);
        resourceLoaderOpeners = List.copyOf(resourceLoaderOpeners);
        packages = Set.copyOf(packages);
    }

    public ModuleDescriptor withResourceLoaders(final List<ResourceLoaderOpener> resourceLoaderOpeners) {
        return new ModuleDescriptor(
            name,
            version,
            classLoaderName,
            modifiers,
            mainClass,
            classLoaderFactory,
            dependencies,
            exports,
            opens,
            uses,
            provides,
            resourceLoaderOpeners,
            packages
        );
    }

    /**
     * Module-wide modifiers.
     */
    public enum Modifier implements ModifierFlag {
        /**
         * Enable native access for this module.
         */
        ENABLE_NATIVE_ACCESS,
        /**
         * The entire module is open for reflective access.
         * Not recommended.
         */
        OPEN,
    }

    /**
     * Obtain a module descriptor from a {@code module-info.class} file's contents.
     *
     * @param moduleInfoBytes the bytes of the {@code module-info.class} (must not be {@code null})
     * @param packageFinder the package finder to use if the descriptor does not contain a list of packages (must not be {@code null})
     * @return the module descriptor (not {@code null})
     */
    public static ModuleDescriptor fromModuleInfo(
        byte[] moduleInfoBytes,
        Supplier<Set<String>> packageFinder
    ) {
        ClassModel classModel = ClassFile.of().parse(moduleInfoBytes);
        if (! classModel.isModuleInfo()) {
            throw new IllegalArgumentException("Not a valid module descriptor");
        }
        ModuleAttribute ma = classModel.findAttribute(Attributes.module()).orElseThrow(ModuleDescriptor::noModuleAttribute);
        Optional<ModulePackagesAttribute> mpa = classModel.findAttribute(Attributes.modulePackages());
        Optional<ModuleMainClassAttribute> mca = classModel.findAttribute(Attributes.moduleMainClass());
        Optional<RuntimeVisibleAnnotationsAttribute> rva = classModel.findAttribute(Attributes.runtimeVisibleAnnotations());
        Modifiers<ModuleDescriptor.Modifier> mods = Modifiers.of();
        if (classModel.flags().has(AccessFlag.OPEN)) {
            mods = mods.with(Modifier.OPEN);
        }
        if (rva.isPresent()) {
            RuntimeVisibleAnnotationsAttribute a = rva.get();
            Optional<Annotation> opt = a.annotations().stream().filter(an -> an.className().equalsString(NativeAccess.class.getName())).findAny();
            if (opt.isPresent()) {
                mods = mods.with(Modifier.ENABLE_NATIVE_ACCESS);
            }
        }
        return new ModuleDescriptor(
            ma.moduleName().name().stringValue(),
            ma.moduleVersion().map(Utf8Entry::stringValue),
            Optional.empty(),
            mods,
            mca.map(ModuleMainClassAttribute::mainClass).map(ClassEntry::name).map(Utf8Entry::stringValue).map(s -> s.replace('/', '.')),
            ModuleClassLoader::new,
            ma.requires().stream().map(
                r -> new Dependency(
                    r.requires().name().stringValue(),
                    Modifiers.of(),
                    Optional.empty()
                )
            ).toList(),
            ma.exports().stream().map(
                e -> new Export(
                    e.exportedPackage().name().stringValue().replace('/', '.'),
                    Modifiers.of(),
                    opt(e.exportsTo().stream()
                        .map(ModuleEntry::name)
                        .map(Utf8Entry::stringValue)
                        .collect(Collectors.toUnmodifiableSet())
                    )
                )
            ).collect(Collectors.toUnmodifiableSet()),
            ma.opens().stream().map(
                o -> new Open(
                    o.openedPackage().name().stringValue(),
                    Modifiers.of(),
                    opt(o.opensTo().stream()
                        .map(ModuleEntry::name)
                        .map(Utf8Entry::stringValue)
                        .collect(Collectors.toUnmodifiableSet())
                    )
                )
            ).collect(Collectors.toUnmodifiableSet()),
            ma.uses().stream().map(ClassEntry::name).map(Utf8Entry::stringValue).collect(Collectors.toUnmodifiableSet()),
            ma.provides().stream().map(
                mpi -> new Provide(
                    mpi.provides().name().stringValue(),
                    mpi.providesWith().stream()
                        .map(ClassEntry::name)
                        .map(Utf8Entry::stringValue)
                        .toList()
                )
            ).collect(Collectors.toUnmodifiableSet()),
            List.of(),
            mpa.map(ModulePackagesAttribute::packages).map(p -> p.stream()
                .map(PackageEntry::name)
                .map(Utf8Entry::stringValue)
                .map(s -> s.replace('/', '.'))
                .map(String::intern)
                .collect(Collectors.toUnmodifiableSet())
            ).orElseGet(packageFinder)
        );
    }

    private static <E, C extends Collection<E>> Optional<C> opt(final C items) {
        return items.isEmpty() ? Optional.empty() : Optional.of(items);
    }

    private static IllegalArgumentException noModuleAttribute() {
        return new IllegalArgumentException("No module attribute found in module descriptor");
    }
}

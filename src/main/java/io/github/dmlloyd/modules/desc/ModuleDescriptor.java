package io.github.dmlloyd.modules.desc;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

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
    Optional<URI> location,
    Function<ModuleClassLoader.ClassLoaderConfiguration, ModuleClassLoader> classLoaderFactory,
    List<Dependency> dependencies,
    Set<String> uses,
    Map<String, List<String>> provides,
    List<ResourceLoaderOpener> resourceLoaderOpeners,
    Map<String, Package> packages
) {

    public ModuleDescriptor {
        Assert.checkNotNullParam("name", name);
        Assert.checkNotNullParam("version", version);
        Assert.checkNotNullParam("classLoaderName", classLoaderName);
        Assert.checkNotNullParam("modifiers", modifiers);
        Assert.checkNotNullParam("mainClass", mainClass);
        Assert.checkNotNullParam("location", location);
        Assert.checkNotNullParam("classLoaderFactory", classLoaderFactory);
        dependencies = List.copyOf(dependencies);
        packages = Map.copyOf(packages);
        uses = Set.copyOf(uses);
        provides = Map.copyOf(provides);
        resourceLoaderOpeners = List.copyOf(resourceLoaderOpeners);
        packages = Map.copyOf(packages);
    }

    public ModuleDescriptor withName(final String name) {
        return new ModuleDescriptor(
            name,
            version,
            classLoaderName,
            modifiers,
            mainClass,
            location,
            classLoaderFactory,
            dependencies,
            uses,
            provides,
            resourceLoaderOpeners,
            packages
        );
    }


    public ModuleDescriptor withResourceLoaders(final List<ResourceLoaderOpener> resourceLoaderOpeners) {
        return new ModuleDescriptor(
            name,
            version,
            classLoaderName,
            modifiers,
            mainClass,
            location,
            classLoaderFactory,
            dependencies,
            uses,
            provides,
            resourceLoaderOpeners,
            packages
        );
    }

    public ModuleDescriptor withAdditionalDependencies(final List<Dependency> list) {
        if (list.isEmpty()) {
            return this;
        } else {
            return new ModuleDescriptor(
                name,
                version,
                classLoaderName,
                modifiers,
                mainClass,
                location,
                classLoaderFactory,
                Stream.concat(dependencies.stream(), list.stream()).toList(),
                uses,
                provides,
                resourceLoaderOpeners,
                packages
            );
        }
    }

    /**
     * Module-wide modifiers.
     */
    public enum Modifier implements ModifierFlag {
        /**
         * Enable native access for this module.
         */
        NATIVE_ACCESS,
        /**
         * The entire module is open for reflective access.
         * Not recommended.
         */
        OPEN,
        /**
         * Define the module as "automatic" which exports and opens all packages.
         * Automatic modules also can use any service.
         * A module cannot be both automatic and unnamed.
         */
        AUTOMATIC,
        /**
         * Define the module as an "unnamed" module, which
         * reads all modules.
         * A module cannot be both automatic and unnamed.
         */
        UNNAMED,
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
                mods = mods.with(Modifier.NATIVE_ACCESS);
            }
        }
        Map<String, Package> packagesMap = new HashMap<>();
        ma.opens().forEach(e -> {
            String packageName = e.openedPackage().name().stringValue().replace('/', '.').intern();
            if (e.opensTo().isEmpty()) {
                // open to all
                packagesMap.put(packageName, Package.OPEN);
            } else {
                // open to some, otherwise private (for now)
                packagesMap.put(packageName, new Package(
                    PackageAccess.PRIVATE,
                    Set.of(),
                    e.opensTo().stream()
                        .map(ModuleEntry::name)
                        .map(Utf8Entry::stringValue)
                        .map(String::intern)
                        .collect(Collectors.toUnmodifiableSet()
                    )
                ));
            }
        });
        ma.exports().forEach(e -> {
            String packageName = e.exportedPackage().name().stringValue().replace('/', '.').intern();
            if (e.exportsTo().isEmpty()) {
                // exports to all
                packagesMap.compute(packageName, (name, oldVal) -> {
                    if (oldVal == null) {
                        return Package.EXPORTED;
                    } else if (oldVal.packageAccess() == PackageAccess.PRIVATE) {
                        return new Package(PackageAccess.EXPORT, Set.of(), oldVal.openTargets());
                    } else {
                        // already exported (opened actually)
                        return oldVal;
                    }
                });
            } else {
                // exports to some, otherwise whatever the existing level was
                Set<String> exportTargets = e.exportsTo().stream()
                    .map(ModuleEntry::name)
                    .map(Utf8Entry::stringValue)
                    .map(String::intern)
                    .collect(Collectors.toUnmodifiableSet()
                );
                packagesMap.compute(packageName, (name, oldVal) -> {
                    if (oldVal == null) {
                        return new Package(PackageAccess.PRIVATE, exportTargets, Set.of());
                    } else {
                        return new Package(oldVal.packageAccess(), exportTargets, oldVal.openTargets());
                    }
                });
            }
        });
        Stream<String> stream;
        if (mpa.isEmpty()) {
            stream = packageFinder.get().stream();
        } else {
            stream = mpa.get().packages().stream()
                .map(PackageEntry::name)
                .map(Utf8Entry::stringValue)
                .map(s -> s.replace('/', '.'));
        }
        stream.map(String::intern).forEach(name ->
            packagesMap.putIfAbsent(name, Package.PRIVATE)
        );
        return new ModuleDescriptor(
            ma.moduleName().name().stringValue(),
            ma.moduleVersion().map(Utf8Entry::stringValue),
            Optional.empty(),
            mods,
            mca.map(ModuleMainClassAttribute::mainClass)
                .map(ClassEntry::name)
                .map(Utf8Entry::stringValue)
                .map(s -> s.replace('/', '.'))
                .map(String::intern),
            Optional.empty(),
            ModuleClassLoader::new,
            ma.requires().stream().map(
                r -> new Dependency(
                    r.requires().name().stringValue(),
                    toModifiers(r.requiresFlags()),
                    Optional.empty()
                )
            ).toList(),
            ma.uses().stream()
                .map(ClassEntry::name)
                .map(Utf8Entry::stringValue)
                .map(s -> s.replace('/', '.'))
                .map(String::intern)
                .collect(Collectors.toUnmodifiableSet()
            ),
            ma.provides().stream().map(
                mpi -> Map.entry(mpi.provides().name().stringValue().replace('/', '.').intern(),
                    mpi.providesWith().stream()
                        .map(ClassEntry::name)
                        .map(Utf8Entry::stringValue)
                        .map(s -> s.replace('/', '.'))
                        .map(String::intern)
                        .toList())
            ).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)),
            List.of(),
            packagesMap
        );
    }

    private static Modifiers<Dependency.Modifier> toModifiers(final Set<AccessFlag> accessFlags) {
        Modifiers<Dependency.Modifier> mods = Modifiers.of();
        for (AccessFlag accessFlag : accessFlags) {
            switch (accessFlag) {
                case STATIC_PHASE -> mods = mods.with(Dependency.Modifier.OPTIONAL);
                case SYNTHETIC -> mods = mods.with(Dependency.Modifier.SYNTHETIC);
                case MANDATED -> mods = mods.with(Dependency.Modifier.MANDATED);
                case TRANSITIVE -> mods = mods.with(Dependency.Modifier.TRANSITIVE);
            }
        }
        return mods;
    }

    public static ModuleDescriptor fromXml(XMLStreamReader xml) throws XMLStreamException {
        switch (xml.nextTag()) {
            case XMLStreamConstants.START_ELEMENT -> {
                checkNamespace(xml);
                switch (xml.getLocalName()) {
                    case "module" -> {
                        return parseModuleElement(xml);
                    }
                    default -> throw unknownElement(xml);
                }
            }
            default -> throw unexpectedContent(xml);
        }
    }

    private static ModuleDescriptor parseModuleElement(final XMLStreamReader xml) throws XMLStreamException {
        String name = null;
        Optional<String> version = Optional.empty();
        Modifiers<Modifier> mods = Modifiers.of();
        Optional<String> mainClass = Optional.empty();
        List<Dependency> dependencies = List.of();
        Set<String> uses = Set.of();
        Map<String, List<String>> provides = Map.of();
        Map<String, Package> packages = Map.of();
        // attributes
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            final String attrVal = xml.getAttributeValue(i);
            switch (xml.getAttributeLocalName(i)) {
                case "name" -> name = attrVal;
                case "automatic" -> {
                    if (attrVal.equals("true")) {
                        mods = mods.with(Modifier.AUTOMATIC);
                    }
                }
                case "unnamed" -> {
                    if (attrVal.equals("true")) {
                        mods = mods.with(Modifier.UNNAMED);
                    }
                }
                case "open" -> {
                    if (attrVal.equals("true")) {
                        mods = mods.with(Modifier.OPEN);
                    }
                }
                case "native-access" -> {
                    if (attrVal.equals("true")) {
                        mods = mods.with(Modifier.NATIVE_ACCESS);
                    }
                }
                case "version" -> version = Optional.of(attrVal);
                default -> throw unknownAttribute(xml, i);
            }
        }
        if (name == null) {
            throw missingAttribute(xml, "name");
        }
        for (;;) {
            switch (xml.nextTag()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    checkNamespace(xml);
                    switch (xml.getLocalName()) {
                        case "dependencies" -> dependencies = parseDependenciesElement(xml);
                        case "packages" -> packages = parsePackagesElement(xml);
                        case "uses" -> uses = parseUsesElement(xml);
                        case "provides" -> provides = parseProvidesElement(xml);
                        case "main-class" -> mainClass = Optional.of(parseMainClassElement(xml));
                        default -> throw unknownElement(xml);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    return new ModuleDescriptor(
                        name,
                        version,
                        mods.contains(Modifier.UNNAMED) ? version.isPresent() ? Optional.of("[" + name + "@" + version.get() + "]") : Optional.of("[" + name + "]") : Optional.empty(),
                        mods,
                        mainClass,
                        Optional.empty(),
                        ModuleClassLoader::new,
                        dependencies,
                        uses,
                        provides,
                        List.of(),
                        packages
                    );
                }
            }
        }
    }

    private static List<Dependency> parseDependenciesElement(final XMLStreamReader xml) throws XMLStreamException {
        List<Dependency> dependencies = new ArrayList<>();
        for (;;) {
            switch (xml.nextTag()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    checkNamespace(xml);
                    switch (xml.getLocalName()) {
                        case "dependency" -> dependencies.add(parseDependencyElement(xml));
                        default -> throw unknownElement(xml);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    return dependencies;
                }
            }
        }
    }

    private static Dependency parseDependencyElement(final XMLStreamReader xml) throws XMLStreamException {
        String name = null;
        Modifiers<Dependency.Modifier> modifiers = Modifiers.of();
        Map<String, PackageAccess> packageAccesses = Map.of();
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            switch (xml.getAttributeLocalName(i)) {
                case "name" -> name = xml.getAttributeValue(i);
                case "transitive" -> {
                    if (Boolean.parseBoolean(xml.getAttributeValue(i))) {
                        modifiers = modifiers.with(Dependency.Modifier.TRANSITIVE);
                    }
                }
                case "optional" -> {
                    if (Boolean.parseBoolean(xml.getAttributeValue(i))) {
                        modifiers = modifiers.with(Dependency.Modifier.OPTIONAL);
                    }
                }
                default -> throw unknownAttribute(xml, i);
            }
        }
        if (name == null) {
            throw missingAttribute(xml, "name");
        }
        for (;;) {
            switch (xml.nextTag()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    checkNamespace(xml);
                    switch (xml.getLocalName()) {
                        case "access" -> {
                            if (packageAccesses.isEmpty()) {
                                packageAccesses = new HashMap<>();
                            }
                            parseAccessElement(xml, packageAccesses);
                        }
                        default -> throw unknownElement(xml);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    return new Dependency(name, modifiers, Optional.empty(), packageAccesses);
                }
            }
        }
    }

    private static void parseAccessElement(final XMLStreamReader xml, final Map<String, PackageAccess> packageAccesses) throws XMLStreamException {
        String name = null;
        PackageAccess access = PackageAccess.EXPORT;
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            switch (xml.getAttributeLocalName(i)) {
                case "name" -> name = xml.getAttributeValue(i);
                case "level" -> access = switch (xml.getAttributeValue(i)) {
                    case "export" -> PackageAccess.EXPORT;
                    case "open" -> PackageAccess.OPEN;
                    default -> throw unknownAttributeValue(xml, i);
                };
                default -> throw unknownAttribute(xml, i);
            }
        }
        if (name == null) {
            throw missingAttribute(xml, "name");
        }
        packageAccesses.put(name, access);
        if (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
            throw unknownElement(xml);
        }
    }

    private static Map<String, Package> parsePackagesElement(final XMLStreamReader xml) throws XMLStreamException {
        Map<String, Package> packages = new HashMap<>();
        for (;;) {
            switch (xml.nextTag()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    checkNamespace(xml);
                    switch (xml.getLocalName()) {
                        case "private" -> parsePrivatePackageElement(xml, packages);
                        case "export" -> parseExportPackageElement(xml, packages);
                        case "open" -> parseOpenPackageElement(xml, packages);
                        default -> throw unknownElement(xml);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    return packages;
                }
            }
        }
    }

    private static void parsePrivatePackageElement(final XMLStreamReader xml, final Map<String, Package> packages) throws XMLStreamException {
        String pkg = null;
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            final String attrVal = xml.getAttributeValue(i);
            switch (xml.getAttributeLocalName(i)) {
                case "package" -> pkg = attrVal;
                default -> throw unknownAttribute(xml, i);
            }
        }
        if (pkg == null) {
            throw missingAttribute(xml, "package");
        }
        Set<String> exportTargets = Set.of();
        Set<String> openTargets = Set.of();
        for (;;) {
            switch (xml.nextTag()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    checkNamespace(xml);
                    switch (xml.getLocalName()) {
                        case "export-to" -> {
                            switch (exportTargets.size()) {
                                case 0 -> exportTargets = Set.of(parsePackageToElement(xml));
                                case 1 -> {
                                    exportTargets = new HashSet<>(exportTargets);
                                    exportTargets.add(parsePackageToElement(xml));
                                }
                                default -> exportTargets.add(parsePackageToElement(xml));
                            }
                        }
                        case "open-to" -> {
                            switch (openTargets.size()) {
                                case 0 -> openTargets = Set.of(parsePackageToElement(xml));
                                case 1 -> {
                                    openTargets = new HashSet<>(openTargets);
                                    openTargets.add(parsePackageToElement(xml));
                                }
                                default -> openTargets.add(parsePackageToElement(xml));
                            }
                        }
                        default -> throw unknownElement(xml);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if (exportTargets.isEmpty() && openTargets.isEmpty()) {
                        packages.put(pkg, Package.PRIVATE);
                    } else {
                        packages.put(pkg, new Package(PackageAccess.PRIVATE, exportTargets, openTargets));
                    }
                    return;
                }
            }
        }
    }

    private static void parseExportPackageElement(final XMLStreamReader xml, final Map<String, Package> packages) throws XMLStreamException {
        String pkg = null;
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            final String attrVal = xml.getAttributeValue(i);
            switch (xml.getAttributeLocalName(i)) {
                case "package" -> pkg = attrVal;
                default -> throw unknownAttribute(xml, i);
            }
        }
        if (pkg == null) {
            throw missingAttribute(xml, "package");
        }
        Set<String> openTargets = Set.of();
        for (;;) {
            switch (xml.nextTag()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    checkNamespace(xml);
                    switch (xml.getLocalName()) {
                        case "open-to" -> {
                            switch (openTargets.size()) {
                                case 0 -> openTargets = Set.of(parsePackageToElement(xml));
                                case 1 -> {
                                    openTargets = new HashSet<>(openTargets);
                                    openTargets.add(parsePackageToElement(xml));
                                }
                                default -> openTargets.add(parsePackageToElement(xml));
                            }
                        }
                        default -> throw unknownElement(xml);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if (openTargets.isEmpty()) {
                        packages.put(pkg, Package.EXPORTED);
                    } else {
                        packages.put(pkg, new Package(PackageAccess.EXPORT, Set.of(), openTargets));
                    }
                    return;
                }
            }
        }
    }

    private static void parseOpenPackageElement(final XMLStreamReader xml, final Map<String, Package> packages) throws XMLStreamException {
        String pkg = null;
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            final String attrVal = xml.getAttributeValue(i);
            switch (xml.getAttributeLocalName(i)) {
                case "package" -> pkg = attrVal;
                default -> throw unknownAttribute(xml, i);
            }
        }
        if (pkg == null) {
            throw missingAttribute(xml, "package");
        }
        if (xml.nextTag() != XMLStreamConstants.END_ELEMENT) {
            throw unknownElement(xml);
        }
        packages.put(pkg, Package.OPEN);
    }

    private static String parsePackageToElement(final XMLStreamReader xml) throws XMLStreamException {
        String mod = null;
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            final String attrVal = xml.getAttributeValue(i);
            switch (xml.getAttributeLocalName(i)) {
                case "module" -> mod = attrVal;
                default -> throw unknownAttribute(xml, i);
            }
        }
        if (mod == null) {
            throw missingAttribute(xml, "module");
        }
        if (xml.nextTag() != XMLStreamConstants.END_ELEMENT) {
            throw unknownElement(xml);
        }
        return mod;
    }

    private static Set<String> parseUsesElement(final XMLStreamReader xml) throws XMLStreamException {
        Set<String> uses = new HashSet<>();
        for (;;) {
            switch (xml.nextTag()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    checkNamespace(xml);
                    switch (xml.getLocalName()) {
                        case "use" -> uses.add(parseUseElement(xml));
                        default -> throw unknownElement(xml);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    return uses;
                }
            }
        }
    }

    private static String parseUseElement(final XMLStreamReader xml) throws XMLStreamException {
        String name = null;
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            final String attrVal = xml.getAttributeValue(i);
            switch (xml.getAttributeLocalName(i)) {
                case "name" -> name = attrVal;
                default -> throw unknownAttribute(xml, i);
            }
        }
        if (name == null) {
            throw missingAttribute(xml, "name");
        }
        if (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
            throw unknownElement(xml);
        }
        return name;
    }

    private static Map<String, List<String>> parseProvidesElement(final XMLStreamReader xml) throws XMLStreamException {
        Map<String, List<String>> provides = new HashMap<>();
        for (;;) {
            switch (xml.nextTag()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    checkNamespace(xml);
                    switch (xml.getLocalName()) {
                        case "provide" -> parseProvideElement(xml, provides);
                        default -> throw unknownElement(xml);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    return provides;
                }
            }
        }
    }

    private static void parseProvideElement(final XMLStreamReader xml, final Map<String, List<String>> provides) throws XMLStreamException {
        String name = null;
        List<String> impls = new ArrayList<>();
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            final String attrVal = xml.getAttributeValue(i);
            switch (xml.getAttributeLocalName(i)) {
                case "name" -> name = attrVal;
                default -> throw unknownAttribute(xml, i);
            }
        }
        if (name == null) {
            throw missingAttribute(xml, "name");
        }
        for (;;) {
            switch (xml.nextTag()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    checkNamespace(xml);
                    switch (xml.getLocalName()) {
                        case "with" -> impls.add(parseWithElement(xml));
                        default -> throw unknownElement(xml);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    provides.put(name, List.copyOf(impls));
                    return;
                }
            }
        }
    }

    private static String parseWithElement(final XMLStreamReader xml) throws XMLStreamException {
        String name = null;
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            final String attrVal = xml.getAttributeValue(i);
            switch (xml.getAttributeLocalName(i)) {
                case "name" -> name = attrVal;
                default -> throw unknownAttribute(xml, i);
            }
        }
        if (name == null) {
            throw missingAttribute(xml, "name");
        }
        if (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
            throw unknownElement(xml);
        }
        return name;
    }

    private static String parseMainClassElement(final XMLStreamReader xml) throws XMLStreamException {
        String name = null;
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            final String attrVal = xml.getAttributeValue(i);
            switch (xml.getAttributeLocalName(i)) {
                case "name" -> name = attrVal;
                default -> throw unknownAttribute(xml, i);
            }
        }
        if (name == null) {
            throw missingAttribute(xml, "name");
        }
        if (xml.nextTag() == XMLStreamConstants.START_ELEMENT) {
            throw unknownElement(xml);
        }
        return name;
    }

    private static XMLStreamException missingAttribute(final XMLStreamReader xml, final String name) {
        return new XMLStreamException("Missing required attribute \"" + name + "\"", xml.getLocation());
    }

    private static void checkNamespace(final XMLStreamReader xml) throws XMLStreamException {
        if (! "urn:jboss:module:3.0".equals(xml.getNamespaceURI())) {
            throw unknownElement(xml);
        }
    }

    private static XMLStreamException unexpectedContent(final XMLStreamReader xml) {
        return new XMLStreamException("Unexpected content encountered", xml.getLocation());
    }

    private static XMLStreamException unknownElement(final XMLStreamReader xml) {
        return new XMLStreamException("Unknown element \"" + xml.getName() + "\"", xml.getLocation());
    }

    private static XMLStreamException unknownAttribute(final XMLStreamReader xml, final int idx) {
        return new XMLStreamException("Unknown attribute \"" + xml.getAttributeName(idx) + "\"", xml.getLocation());
    }

    private static XMLStreamException unknownAttributeValue(final XMLStreamReader xml, final int idx) {
        return new XMLStreamException("Unknown attribute value \"" + xml.getAttributeValue(idx) + "\" for attribute \"" + xml.getAttributeName(idx) + "\"", xml.getLocation());
    }

    private static IllegalArgumentException noModuleAttribute() {
        return new IllegalArgumentException("No module attribute found in module descriptor");
    }
}

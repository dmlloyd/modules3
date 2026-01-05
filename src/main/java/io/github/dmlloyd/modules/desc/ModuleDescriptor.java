package io.github.dmlloyd.modules.desc;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.FindException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.jar.Attributes.Name;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import io.smallrye.classfile.Annotation;
import io.smallrye.classfile.AnnotationElement;
import io.smallrye.classfile.AnnotationValue;
import io.smallrye.classfile.Attributes;
import io.smallrye.classfile.ClassFile;
import io.smallrye.classfile.ClassModel;
import io.smallrye.classfile.attribute.ModuleAttribute;
import io.smallrye.classfile.attribute.ModuleExportInfo;
import io.smallrye.classfile.attribute.ModuleMainClassAttribute;
import io.smallrye.classfile.attribute.ModuleOpenInfo;
import io.smallrye.classfile.attribute.ModulePackagesAttribute;
import io.smallrye.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import io.smallrye.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import io.smallrye.classfile.constantpool.ClassEntry;
import io.smallrye.classfile.constantpool.ModuleEntry;
import io.smallrye.classfile.constantpool.PackageEntry;
import io.smallrye.classfile.constantpool.Utf8Entry;
import io.smallrye.classfile.extras.reflect.AccessFlag;
import io.github.dmlloyd.modules.NativeAccess;
import io.github.dmlloyd.modules.impl.TextIter;
import io.github.dmlloyd.modules.impl.Util;
import io.smallrye.common.constraint.Assert;
import io.smallrye.common.resource.Resource;
import io.smallrye.common.resource.ResourceLoader;

/**
 * A descriptor for initially defining a module.
 */
// todo: write XML, read/write binary
public record ModuleDescriptor(
    String name,
    Optional<String> version,
    Modifiers<Modifier> modifiers,
    Optional<String> mainClass,
    Optional<URI> location,
    List<Dependency> dependencies,
    Set<String> uses,
    Map<String, List<String>> provides,
    Map<String, PackageInfo> packages
) {

    public ModuleDescriptor {
        Assert.checkNotNullParam("name", name);
        Assert.checkNotNullParam("version", version);
        Assert.checkNotNullParam("modifiers", modifiers);
        Assert.checkNotNullParam("mainClass", mainClass);
        Assert.checkNotNullParam("location", location);
        dependencies = List.copyOf(dependencies);
        packages = Map.copyOf(packages);
        uses = Set.copyOf(uses);
        provides = Map.copyOf(provides);
        packages = Map.copyOf(packages);
    }

    public ModuleDescriptor withName(final String name) {
        return new ModuleDescriptor(
            name,
            version,
            modifiers,
            mainClass,
            location,
            dependencies,
            uses,
            provides,
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
                modifiers,
                mainClass,
                location,
                Util.concat(dependencies, list),
                uses,
                provides,
                packages
            );
        }
    }

    public ModuleDescriptor withPackages(final Map<String, PackageInfo> packages) {
        if (packages == this.packages) {
            return this;
        } else {
            return new ModuleDescriptor(
                name,
                version,
                modifiers,
                mainClass,
                location,
                dependencies,
                uses,
                provides,
                packages
            );
        }
    }

    public ModuleDescriptor withAdditionalPackages(final Map<String, PackageInfo> packages) {
        if (packages.isEmpty()) {
            return this;
        }
        Map<String, PackageInfo> existing = packages();
        if (existing.isEmpty()) {
            return withPackages(packages);
        } else {
            return withPackages(Util.merge(existing, packages, PackageInfo::mergedWith));
        }
    }

    public ModuleDescriptor withDiscoveredPackages(final List<ResourceLoader> loaders) throws IOException {
        ModuleDescriptor desc = this;
        for (ResourceLoader loader : loaders) {
            desc = desc.withDiscoveredPackages(loader);
        }
        return desc;
    }

    public ModuleDescriptor withDiscoveredPackages(final ResourceLoader loader) throws IOException {
        return withDiscoveredPackages(loader, (pn, existing) -> {
            if (pn.contains(".impl.") || pn.endsWith(".impl")
                || pn.contains(".private_.") || pn.endsWith(".private_")
                || pn.contains("._private.") || pn.endsWith("._private")
            ) {
                return existing == null ? PackageInfo.PRIVATE : existing;
            } else {
                return existing == null ? PackageInfo.EXPORTED : existing.withAccessAtLeast(PackageAccess.EXPORTED);
            }
        });
    }

    public ModuleDescriptor withDiscoveredPackages(final ResourceLoader loader, final PackageAccess access) throws IOException {
        return withDiscoveredPackages(loader, (ignored0, existing) -> existing == null ? PackageInfo.forAccess(access) : existing.withAccessAtLeast(access));
    }

    public ModuleDescriptor withDiscoveredPackages(final ResourceLoader loader, final BiFunction<String, PackageInfo, PackageInfo> packageFunction) throws IOException {
        Map<String, PackageInfo> packages = searchPackages(loader.findResource("/"), packageFunction, this.packages, new HashSet<>());
        if (packages == this.packages) {
            return this;
        } else {
            return new ModuleDescriptor(
                name,
                version,
                modifiers,
                mainClass,
                location,
                dependencies,
                uses,
                provides,
                packages
            );
        }
    }

    public ModuleDescriptor withAdditionalServiceProviders(Map<String, List<String>> provides) {
        if (provides.isEmpty()) {
            return this;
        } else {
            return new ModuleDescriptor(
                name,
                version,
                modifiers,
                mainClass,
                location,
                dependencies,
                uses,
                Util.merge(provides(), provides, Util::concat),
                packages
            );
        }
    }

    private Map<String, PackageInfo> searchPackages(final Resource dir, final BiFunction<String, PackageInfo, PackageInfo> packageFunction, Map<String, PackageInfo> map, Set<String> found) throws IOException {
        try (DirectoryStream<Resource> ds = dir.openDirectoryStream()) {
            for (Resource child : ds) {
                if (child.isDirectory()) {
                    map = searchPackages(child, packageFunction, map, found);
                } else {
                    String pathName = child.pathName();
                    if (pathName.endsWith(".class")) {
                        int idx = pathName.lastIndexOf('/');
                        if (idx != -1) {
                            String pn = pathName.substring(0, idx).replace('/', '.');
                            if (found.add(pn)) {
                                PackageInfo existing = map.get(pn);
                                PackageInfo update = packageFunction.apply(pn, existing);
                                if (update == null || update.equals(existing)) {
                                    // skip it
                                    continue;
                                }
                                if (map == packages) {
                                    map = new HashMap<>(packages);
                                }
                                map.put(pn, update);
                            }
                        }
                    }
                }
            }
        }
        return map;
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
        ;
        public static final List<Modifier> values = List.of(values());

        private static final List<Modifiers<Modifier>> sets = List.copyOf(IntStream.range(0, 16)
            .mapToObj(bits -> new Modifiers<Modifier>(values, Modifier::forBits, bits))
            .toList()
        );

        public static Modifiers<Modifier> set() {
            return sets.get(0);
        }

        public static Modifiers<Modifier> set(Modifier modifier) {
            return sets.get(bit(modifier));
        }

        public static Modifiers<Modifier> set(Modifier modifier0, Modifier modifier1) {
            return sets.get(bit(modifier0) | bit(modifier1));
        }

        public static Modifiers<Modifier> set(Modifier modifier0, Modifier modifier1, Modifier modifier2) {
            return sets.get(bit(modifier0) | bit(modifier1) | bit(modifier2));
        }

        public static Modifiers<Modifier> set(Modifier modifier0, Modifier modifier1, Modifier modifier2, Modifier modifier3) {
            return sets.get(bit(modifier0) | bit(modifier1) | bit(modifier2) | bit(modifier3));
        }

        private static int bit(final Modifier item) {
            return item == null ? 0 : 1 << item.ordinal();
        }

        private static Modifiers<Modifier> forBits(int bits) {
            return sets.get(bits);
        }
    }

    /**
     * Obtain a module descriptor from a {@code module-info.class} file's contents.
     *
     * @param moduleInfo the bytes of the {@code module-info.class} (must not be {@code null})
     * @param resourceLoaders the loaders from which packages may be discovered if not given in the descriptor (must not be {@code null})
     * @return the module descriptor (not {@code null})
     */
    public static ModuleDescriptor fromModuleInfo(
        Resource moduleInfo,
        List<ResourceLoader> resourceLoaders
    ) throws IOException {
        return fromModuleInfo(moduleInfo, resourceLoaders, Map.of());
    }

    /**
     * Obtain a module descriptor from a {@code module-info.class} file's contents.
     *
     * @param moduleInfo the bytes of the {@code module-info.class} (must not be {@code null})
     * @param resourceLoaders the loaders from which packages may be discovered if not given in the descriptor (must not be {@code null})
     * @param extraAccesses extra package accesses to merge into dependencies (must not be {@code null})
     * @return the module descriptor (not {@code null})
     */
    public static ModuleDescriptor fromModuleInfo(
        Resource moduleInfo,
        List<ResourceLoader> resourceLoaders,
        Map<String, Map<String, PackageAccess>> extraAccesses
    ) throws IOException {
        final Map<String, Map<String, PackageAccess>> modifiedExtraAccesses = new HashMap<>(extraAccesses);
        ClassModel classModel;
        try (InputStream is = moduleInfo.openStream()) {
            classModel = ClassFile.of().parse(is.readAllBytes());
        }
        if (! classModel.isModuleInfo()) {
            throw new IllegalArgumentException("Not a valid module descriptor");
        }
        ModuleAttribute ma = classModel.findAttribute(Attributes.module()).orElseThrow(ModuleDescriptor::noModuleAttribute);
        Optional<ModulePackagesAttribute> mpa = classModel.findAttribute(Attributes.modulePackages());
        Optional<ModuleMainClassAttribute> mca = classModel.findAttribute(Attributes.moduleMainClass());
        Optional<RuntimeVisibleAnnotationsAttribute> rva = classModel.findAttribute(Attributes.runtimeVisibleAnnotations());
        Optional<RuntimeInvisibleAnnotationsAttribute> ria = classModel.findAttribute(Attributes.runtimeInvisibleAnnotations());
        Modifiers<ModuleDescriptor.Modifier> mods = ModuleDescriptor.Modifier.set();
        boolean open = classModel.flags().has(AccessFlag.OPEN);
        if (open) {
            mods = mods.with(Modifier.OPEN);
        }
        if (rva.isPresent()) {
            RuntimeVisibleAnnotationsAttribute a = rva.get();
            Optional<Annotation> opt = a.annotations().stream().filter(an -> an.className().equalsString(NativeAccess.class.getName())).findAny();
            if (opt.isPresent()) {
                mods = mods.with(Modifier.NATIVE_ACCESS);
            }
        }
        if (ria.isPresent()) {
            RuntimeInvisibleAnnotationsAttribute a = ria.get();
            for (Annotation ann : a.annotations()) {
                switch (ann.className().stringValue()) {
                    case "io.smallrye.common.annotation.AddExports", "io.smallrye.common.annotation.AddOpens" ->
                        processAccessAnnotation(ann, modifiedExtraAccesses);
                    case "io.smallrye.common.annotation.AddExports$List",  "io.smallrye.common.annotation.AddOpens$List" -> {
                        for (AnnotationElement element : ann.elements()) {
                            if (element.name().stringValue().equals("value")) {
                                AnnotationValue.OfArray val = (AnnotationValue.OfArray) element.value();
                                for (AnnotationValue value : val.values()) {
                                    processAccessAnnotation(((AnnotationValue.OfAnnotation)value).annotation(), modifiedExtraAccesses);
                                }
                            }
                        }
                    }
                    case "io.smallrye.common.annotation.NativeAccess" -> mods = mods.with(Modifier.NATIVE_ACCESS);
                }
            }
        }
        Map<String, PackageInfo> packagesMap = new HashMap<>();
        for (ModuleOpenInfo moduleOpenInfo : ma.opens()) {
            String packageName = moduleOpenInfo.openedPackage().name().stringValue().replace('/', '.').intern();
            packagesMap.put(packageName, open ? PackageInfo.OPEN : PackageInfo.of(
                PackageAccess.PRIVATE,
                Set.of(),
                moduleOpenInfo.opensTo().stream()
                    .map(ModuleEntry::name)
                    .map(Utf8Entry::stringValue)
                    .map(String::intern)
                    .collect(Collectors.toUnmodifiableSet()
                )
            ));
        }
        for (ModuleExportInfo e : ma.exports()) {
            String packageName = e.exportedPackage().name().stringValue().replace('/', '.').intern();
            if (open) {
                packagesMap.put(packageName, PackageInfo.OPEN);
            } else if (e.exportsTo().isEmpty()) {
                // exports to all
                packagesMap.compute(packageName, (name, oldVal) -> {
                    if (oldVal == null) {
                        return PackageInfo.EXPORTED;
                    } else {
                        return oldVal.withAccessAtLeast(PackageAccess.EXPORTED);
                    }
                });
            } else {
                // exports to some, otherwise whatever the existing level was
                Set<String> exportTargets = e.exportsTo().stream()
                    .map(ModuleEntry::name)
                    .map(Utf8Entry::stringValue)
                    .map(String::intern)
                    .collect(Collectors.toUnmodifiableSet());
                packagesMap.put(packageName, packagesMap.getOrDefault(packageName, PackageInfo.PRIVATE).withExportTargets(exportTargets));
            }
        }
        mpa.ifPresent(modulePackagesAttribute -> modulePackagesAttribute.packages().stream()
            .map(PackageEntry::name)
            .map(Utf8Entry::stringValue)
            .map(s -> s.replace('/', '.'))
            .map(String::intern)
            .forEach(name -> packagesMap.putIfAbsent(name, PackageInfo.PRIVATE))
        );
        String moduleName = ma.moduleName().name().stringValue();
        ModuleDescriptor desc = new ModuleDescriptor(
            moduleName,
            ma.moduleVersion().map(Utf8Entry::stringValue),
            mods,
            mca.map(ModuleMainClassAttribute::mainClass)
                .map(ClassEntry::name)
                .map(Utf8Entry::stringValue)
                .map(s -> s.replace('/', '.'))
                .map(String::intern),
            Optional.empty(),
            ma.requires().stream().map(
                r -> new Dependency(
                    r.requires().name().stringValue(),
                    toModifiers(r.requiresFlags()),
                    Optional.empty(),
                    modifiedExtraAccesses.getOrDefault(r.requires().name().stringValue(), Map.of())
                )
            ).collect(Util.toList()),
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
                        .collect(Util.toList()))
            ).collect(Util.toMap()),
            packagesMap
        );
        if (mpa.isEmpty()) {
            desc = desc.withDiscoveredPackages(resourceLoaders);
        }
        return desc;
    }

    private static void processAccessAnnotation(Annotation ann, Map<String, Map<String, PackageAccess>> modifiedExtraAccesses) {
        String moduleName = null;
        List<String> packages = null;
        for (AnnotationElement element : ann.elements()) {
            switch (element.name().stringValue()) {
                case "module" -> moduleName = ((AnnotationValue.OfString)element.value()).stringValue();
                case "packages" -> packages = ((AnnotationValue.OfArray)element.value()).values().stream().map(AnnotationValue.OfString.class::cast).map(AnnotationValue.OfString::stringValue).toList();
            }
        }
        if (moduleName == null || moduleName.equals("ALL-UNNAMED") || packages == null) {
            // ignore invalid annotation
            return;
        }
        switch (ann.className().stringValue()) {
            // override all
            case "io.smallrye.common.annotation.AddOpens" -> {
                for (String pn : packages) {
                    modifiedExtraAccesses.computeIfAbsent(moduleName, Util::newHashMap).put(pn, PackageAccess.OPEN);
                }
            }
            // do not override OPEN
            case "io.smallrye.common.annotation.AddExports" -> {
                for (String pn : packages) {
                    modifiedExtraAccesses.computeIfAbsent(moduleName, Util::newHashMap).putIfAbsent(pn, PackageAccess.EXPORTED);
                }
            }
            default -> throw Assert.impossibleSwitchCase(ann.className().stringValue());
        }
    }

    public static ModuleDescriptor fromManifest(String defaultName, String defaultVersion, Manifest manifest, List<ResourceLoader> resourceLoaders) throws IOException {
        return fromManifest(defaultName, defaultVersion, manifest, resourceLoaders, Map.of());
    }

    public static ModuleDescriptor fromManifest(String defaultName, String defaultVersion, Manifest manifest, List<ResourceLoader> resourceLoaders, Map<String, Map<String, PackageAccess>> extraAccesses) throws IOException {
        var mainAttributes = manifest.getMainAttributes();
        String moduleName = mainAttributes.getValue("Automatic-Module-Name");
        String version = mainAttributes.getValue("Module-Version");
        if (version == null) {
            version = mainAttributes.getValue(Name.IMPLEMENTATION_VERSION);
        }
        if (version == null) {
            version = defaultVersion;
        }
        boolean enableNativeAccess = ! Objects.requireNonNullElse(mainAttributes.getValue("Enable-Native-Access"), "").trim().isEmpty();
        String mainClass = mainAttributes.getValue(Name.MAIN_CLASS);
        String depString = mainAttributes.getValue("Dependencies");
        Map<String, Set<String>> addOpens = parseManifestAdd(mainAttributes.getValue("Add-Opens"));
        Map<String, Set<String>> addExports = parseManifestAdd(mainAttributes.getValue("Add-Exports"));

        List<Dependency> dependencies = List.of();
        if (depString != null) {
            TextIter iter = TextIter.of(depString);
            iter.skipWhiteSpace();
            while (iter.hasNext()) {
                String depName = dotName(iter);
                Modifiers<Dependency.Modifier> mods = Dependency.Modifier.set(Dependency.Modifier.SERVICES);
                iter.skipWhiteSpace();
                while (iter.hasNext()) {
                    if (iter.peekNext() == ',') {
                        // done with this dependency
                        break;
                    }
                    if (iter.match("optional")) {
                        mods = mods.with(Dependency.Modifier.OPTIONAL);
                    } else if (iter.match("export")) {
                        mods = mods.with(Dependency.Modifier.TRANSITIVE);
                    } else {
                        iter.skipUntil(cp -> Character.isWhitespace(cp) || cp == ',');
                        iter.skipWhiteSpace();
                    }
                    // else ignored
                }
                Map<String, PackageAccess> accesses;
                if (addOpens.containsKey(depName) || addExports.containsKey(depName)) {
                    accesses = Stream.concat(
                        Stream.concat(
                            addExports.get(depName).stream().map(pkg -> Map.entry(pkg, PackageAccess.EXPORTED)),
                            addOpens.get(depName).stream().map(pkg -> Map.entry(pkg, PackageAccess.OPEN))
                        ),
                        extraAccesses.getOrDefault(depName, Map.of()).entrySet().stream()
                    ).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, PackageAccess::max));
                } else {
                    accesses = Map.of();
                }
                if (dependencies.isEmpty()) {
                    dependencies = new ArrayList<>();
                }
                dependencies.add(new Dependency(depName, mods, Optional.empty(), accesses));
            }
        }
        if (moduleName == null) {
            moduleName = defaultName;
        }
        if (moduleName == null || moduleName.isEmpty()) {
            throw new FindException("A valid module name is required");
        }
        Modifiers<Modifier> mods = Modifier.set(Modifier.AUTOMATIC);
        if (enableNativeAccess) {
            mods = mods.with(Modifier.NATIVE_ACCESS);
        }
        return new ModuleDescriptor(
            moduleName,
            Optional.ofNullable(version),
            mods,
            Optional.ofNullable(mainClass),
            Optional.empty(),
            dependencies,
            Set.of(),
            Map.of(),
            Map.of()
        ).withDiscoveredPackages(resourceLoaders);
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

    private static <E> HashSet<E> newHashSet(Object ignored) {
        return new HashSet<>();
    }

    private static Map<String, Set<String>> parseManifestAdd(String value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Set<String>> map = Map.of();
        TextIter iter = TextIter.of(value);
        iter.skipWhiteSpace();
        while (iter.hasNext()) {
            String moduleName = dotName(iter);
            if (iter.peekNext() != '/') {
                throw invalidChar(value, iter.peekNext(), iter.position());
            }
            iter.next(); // consume /
            String packageName = dotName(iter);
            if (map.isEmpty()) {
                map = new LinkedHashMap<>();
            }
            map.computeIfAbsent(moduleName, ModuleDescriptor::newHashSet).add(packageName);
            iter.skipWhiteSpace();
        }
        return map;
    }

    private static IllegalArgumentException invalidChar(final String str, final int cp, final int idx) {
        return new IllegalArgumentException("Invalid character '%s' at index %d of \"%s\"".formatted(Character.toString(cp), Integer.valueOf(idx), str));
    }

    private static String dotName(TextIter iter) {
        int cp = iter.peekNext();
        if (Character.isJavaIdentifierStart(cp)) {
            int start = iter.position();
            iter.next(); // consume
            while (iter.hasNext()) {
                cp = iter.peekNext();
                if (! Character.isJavaIdentifierPart(cp)) {
                    // done
                    return iter.substring(start);
                }
            }
            // end of string
            return iter.substring(start);
        }
        throw invalidChar(iter.text(), cp, iter.position());
    }

    private static ModuleDescriptor parseModuleElement(final XMLStreamReader xml) throws XMLStreamException {
        String name = null;
        Optional<String> version = Optional.empty();
        Modifiers<Modifier> mods = Modifier.set();
        Optional<String> mainClass = Optional.empty();
        List<Dependency> dependencies = List.of();
        Set<String> uses = Set.of();
        Map<String, List<String>> provides = Map.of();
        Map<String, PackageInfo> packages = Map.of();
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
                        mods,
                        mainClass,
                        Optional.empty(),
                        dependencies,
                        uses,
                        provides,
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
                        // TODO: separate dep elements for linked, unlinked services, etc. dependency types
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
        Modifiers<Dependency.Modifier> modifiers = Dependency.Modifier.set(Dependency.Modifier.SERVICES, Dependency.Modifier.LINKED, Dependency.Modifier.READ);
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
                case "linked" -> {
                    if (! Boolean.parseBoolean(xml.getAttributeValue(i))) {
                        modifiers = modifiers.without(Dependency.Modifier.LINKED);
                    }
                }
                case "services" -> {
                    if (! Boolean.parseBoolean(xml.getAttributeValue(i))) {
                        modifiers = modifiers.without(Dependency.Modifier.SERVICES);
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
        PackageAccess access = PackageAccess.EXPORTED;
        int cnt = xml.getAttributeCount();
        for (int i = 0; i < cnt; i ++) {
            switch (xml.getAttributeLocalName(i)) {
                case "name" -> name = xml.getAttributeValue(i);
                case "level" -> access = switch (xml.getAttributeValue(i)) {
                    case "export" -> PackageAccess.EXPORTED;
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

    private static Map<String, PackageInfo> parsePackagesElement(final XMLStreamReader xml) throws XMLStreamException {
        Map<String, PackageInfo> packages = new HashMap<>();
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

    private static void parsePrivatePackageElement(final XMLStreamReader xml, final Map<String, PackageInfo> packages) throws XMLStreamException {
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
                        packages.put(pkg, PackageInfo.PRIVATE);
                    } else {
                        packages.put(pkg, new PackageInfo(PackageAccess.PRIVATE, exportTargets, openTargets));
                    }
                    return;
                }
            }
        }
    }

    private static void parseExportPackageElement(final XMLStreamReader xml, final Map<String, PackageInfo> packages) throws XMLStreamException {
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
                        packages.put(pkg, PackageInfo.EXPORTED);
                    } else {
                        packages.put(pkg, new PackageInfo(PackageAccess.EXPORTED, Set.of(), openTargets));
                    }
                    return;
                }
            }
        }
    }

    private static void parseOpenPackageElement(final XMLStreamReader xml, final Map<String, PackageInfo> packages) throws XMLStreamException {
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
        packages.put(pkg, PackageInfo.OPEN);
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

    private static Modifiers<Dependency.Modifier> toModifiers(final Set<AccessFlag> accessFlags) {
        Modifiers<Dependency.Modifier> mods = Dependency.Modifier.set(Dependency.Modifier.SERVICES);
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

    private static void checkNamespace(final XMLStreamReader xml) throws XMLStreamException {
        if (! "urn:jboss:module:3.0".equals(xml.getNamespaceURI())) {
            throw unknownElement(xml);
        }
    }

    private static XMLStreamException missingAttribute(final XMLStreamReader xml, final String name) {
        return new XMLStreamException("Missing required attribute \"" + name + "\"", xml.getLocation());
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

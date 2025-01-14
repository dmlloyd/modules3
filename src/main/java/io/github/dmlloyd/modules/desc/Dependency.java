package io.github.dmlloyd.modules.desc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.dmlloyd.modules.ModuleLoader;
import io.smallrye.common.constraint.Assert;

/**
 * A dependency description.
 * If no optional dependency resolver is given, the module's own dependency resolver will be used.
 *
 * @param moduleName         the dependency name (must not be {@code null})
 * @param modifiers          the dependency modifiers (must not be {@code null})
 * @param moduleLoader       the optional module loader to use for this dependency (must not be {@code null})
 * @param packageAccesses    extra package access to the given dependency (must not be {@code null})
 */
public record Dependency(
    String moduleName,
    Modifiers<Modifier> modifiers,
    Optional<ModuleLoader> moduleLoader,
    Map<String, PackageAccess> packageAccesses
) {
    public Dependency {
        Assert.checkNotNullParam("moduleName", moduleName);
        Assert.checkNotNullParam("modifiers", modifiers);
        Assert.checkNotNullParam("moduleLoader", moduleLoader);
        packageAccesses = Map.copyOf(packageAccesses);
    }

    public Dependency(String moduleName, Modifiers<Modifier> modifiers, Optional<ModuleLoader> moduleLoader) {
        this(moduleName, modifiers, moduleLoader, Map.of());
    }

    /**
     * Construct a new instance with no modifiers and no module loader.
     *
     * @param moduleName the dependency name (must not be {@code null})
     */
    public Dependency(String moduleName) {
        this(moduleName, Modifiers.of(), Optional.empty());
    }

    /**
     * Construct a new instance with no modifiers and no module loader.
     *
     * @param moduleName the dependency name (must not be {@code null})
     * @param modifier the modifier to add (must not be {@code null})
     */
    public Dependency(String moduleName, Modifier modifier) {
        this(moduleName, Modifiers.of(modifier), Optional.empty());
    }

    public boolean isNonSynthetic() {
        return ! modifiers.contains(Modifier.SYNTHETIC);
    }

    /**
     * Modifiers for dependencies.
     */
    public enum Modifier implements ModifierFlag {
        SYNTHETIC,
        MANDATED,
        OPTIONAL,
        TRANSITIVE,
        ;

        public static final List<Modifier> values = List.of(values());
    }
}

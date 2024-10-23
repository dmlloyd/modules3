package io.github.dmlloyd.modules.desc;

import java.util.List;
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
 */
public record Dependency(
    String moduleName,
    Modifiers<Modifier> modifiers,
    Optional<ModuleLoader> moduleLoader
) {
    public Dependency {
        Assert.checkNotNullParam("moduleName", moduleName);
        Assert.checkNotNullParam("modifiers", modifiers);
        Assert.checkNotNullParam("moduleLoader", moduleLoader);
    }

    /**
     * Modifiers for dependencies.
     */
    public enum Modifier implements ModifierFlag {
        SYNTHETIC,
        MANDATORY,
        OPTIONAL,
        TRANSITIVE,
        ;

        public static final List<Modifier> values = List.of(values());
    }
}

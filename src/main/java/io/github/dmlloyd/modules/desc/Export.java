package io.github.dmlloyd.modules.desc;

import java.util.Optional;
import java.util.Set;

import io.smallrye.common.constraint.Assert;

/**
 * Export a package, optionally to specific targets only.
 * The targets must be findable using this module's own dependency resolver.
 *
 * @param packageName the package name (must not be {@code null})
 * @param modifiers   the package export modifiers (must not be {@code null})
 * @param targets     the optional package export targets (must not be {@code null})
 */
public record Export(
    String packageName,
    Modifiers<Modifier> modifiers,
    Optional<Set<String>> targets
) {
    public Export {
        Assert.checkNotNullParam("packageName", packageName);
        Assert.checkNotNullParam("modifiers", modifiers);
        targets = Assert.checkNotNullParam("targets", targets).map(Set::copyOf);
    }

    /**
     * Construct a new instance with no modifiers.
     * The package is exported unconditionally.
     *
     * @param packageName the package name (must not be {@code null})
     */
    public Export(String packageName) {
        this(packageName, Modifiers.of(), Optional.empty());
    }

    /**
     * The modifier flags for exports.
     */
    public enum Modifier implements ModifierFlag {
        SYNTHETIC,
        MANDATED,
    }
}

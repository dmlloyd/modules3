package io.github.dmlloyd.modules.desc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.smallrye.common.constraint.Assert;

/**
 * An open, which makes a package available for reflection by the target or by anyone.
 *
 * @param packageName the package name (must not be {@code null})
 * @param modifiers the modifiers (must not be {@code null})
 * @param targets the optional module targets (must not be {@code null})
 */
public record Open(
    String packageName,
    Modifiers<Modifier> modifiers,
    Optional<Set<String>> targets
) {
    public Open {
        Assert.checkNotNullParam("packageName", packageName);
        if (packageName.contains("/")) {
            throw new IllegalArgumentException("Invalid package name: " + packageName);
        }
        Assert.checkNotNullParam("modifiers", modifiers);
        targets = Assert.checkNotNullParam("targets", targets).map(Set::copyOf);
    }

    /**
     * Construct a new instance with no modifiers.
     * The package is opened unconditionally.
     *
     * @param packageName the package name (must not be {@code null})
     */
    public Open(final String packageName) {
        this(packageName, Modifiers.of(), Optional.empty());
    }

    /**
     * The modifier flags for opens.
     */
    public enum Modifier implements ModifierFlag {
        SYNTHETIC,
        MANDATED,
        ;

        public static final List<Modifier> values = List.of(values());
    }
}

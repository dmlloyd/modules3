package io.github.dmlloyd.modules.desc;

import java.util.Set;

import io.smallrye.common.constraint.Assert;

/**
 * Information about a package in the module.
 *
 * @param packageAccess the outbound access level of the package (must not be {@code null})
 * @param exportTargets specific export targets in addition to those implied by {@link #packageAccess()} or {@link #openTargets()} (must not be {@code null})
 * @param openTargets specific open targets in addition to those implied by {@link #packageAccess()} (must not be {@code null})
 */
public record Package(
    PackageAccess packageAccess,
    Set<String> exportTargets,
    Set<String> openTargets
) {

    public Package {
        Assert.checkNotNullParam("packageAccess", packageAccess);
        exportTargets = packageAccess.isAtLeast(PackageAccess.EXPORTED) ? Set.of() : Set.copyOf(exportTargets);
        openTargets = packageAccess.isAtLeast(PackageAccess.OPEN) ? Set.of() : Set.copyOf(openTargets);
    }

    public static final Package PRIVATE = new Package(PackageAccess.PRIVATE, Set.of(), Set.of());
    public static final Package EXPORTED = new Package(PackageAccess.EXPORTED, Set.of(), Set.of());
    public static final Package OPEN = new Package(PackageAccess.OPEN, Set.of(), Set.of());
}

package io.github.dmlloyd.modules.desc;

import java.util.Set;

import io.github.dmlloyd.modules.impl.Util;
import io.smallrye.common.constraint.Assert;

/**
 * Information about a package in the module.
 *
 * @param packageAccess the outbound access level of the package (must not be {@code null})
 * @param exportTargets specific export targets in addition to those implied by {@link #packageAccess()} or {@link #openTargets()} (must not be {@code null})
 * @param openTargets specific open targets in addition to those implied by {@link #packageAccess()} (must not be {@code null})
 */
public record PackageInfo(
    PackageAccess packageAccess,
    Set<String> exportTargets,
    Set<String> openTargets
) {

    /**
     * Default record constructor (but use {@link #of} instead, whenever possible).
     *
     * @param packageAccess the outbound access level of the package (must not be {@code null})
     * @param exportTargets specific export targets in addition to those implied by {@link #packageAccess()} or {@link #openTargets()} (must not be {@code null})
     * @param openTargets specific open targets in addition to those implied by {@link #packageAccess()} (must not be {@code null})
     */
    public PackageInfo {
        Assert.checkNotNullParam("packageAccess", packageAccess);
        exportTargets = packageAccess.isAtLeast(PackageAccess.EXPORTED) ? Set.of() : Set.copyOf(exportTargets);
        openTargets = packageAccess.isAtLeast(PackageAccess.OPEN) ? Set.of() : Set.copyOf(openTargets);
    }

    public static final PackageInfo PRIVATE = new PackageInfo(PackageAccess.PRIVATE, Set.of(), Set.of());
    public static final PackageInfo EXPORTED = new PackageInfo(PackageAccess.EXPORTED, Set.of(), Set.of());
    public static final PackageInfo OPEN = new PackageInfo(PackageAccess.OPEN, Set.of(), Set.of());

    public static PackageInfo forAccess(final PackageAccess access) {
        return switch (access) {
            case PRIVATE -> PRIVATE;
            case EXPORTED -> EXPORTED;
            case OPEN -> OPEN;
        };
    }

    public static PackageInfo of(PackageAccess packageAccess, Set<String> exportTargets, Set<String> openTargets) {
        exportTargets = Set.copyOf(exportTargets);
        openTargets = Set.copyOf(openTargets);
        if (packageAccess.isAtLeast(PackageAccess.OPEN)) {
            return OPEN;
        } else if (openTargets.isEmpty()) {
            if (packageAccess.isAtLeast(PackageAccess.EXPORTED)) {
                return EXPORTED;
            } else if (exportTargets.isEmpty()) {
                return PRIVATE;
            }
        }
        return new PackageInfo(packageAccess, exportTargets, openTargets);
    }

    public PackageInfo mergedWith(PackageInfo other) {
        return of(
            PackageAccess.max(packageAccess(), other.packageAccess()),
            Util.merge(exportTargets(), other.exportTargets()),
            Util.merge(openTargets(), other.openTargets())
        );
    }

    public PackageInfo withAccessAtLeast(PackageAccess newAccess) {
        return of(
            PackageAccess.max(packageAccess(), newAccess),
            exportTargets,
            openTargets
        );
    }

    public PackageInfo withExportTargets(final Set<String> exportTargets) {
        return of(
            packageAccess(),
            Util.merge(exportTargets(), exportTargets),
            openTargets()
        );
    }
}

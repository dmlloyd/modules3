package io.github.dmlloyd.modules.desc;

import java.util.List;

/**
 * The access level of a package.
 */
public enum PackageAccess {
    PRIVATE,
    EXPORTED,
    OPEN,
    ;

    public static final List<PackageAccess> values = List.of(values());

    public boolean isAtLeast(PackageAccess other) {
        return this.compareTo(other) >= 0;
    }

    public static PackageAccess min(final PackageAccess access1, PackageAccess access2) {
        return values.get(Math.min(access1.ordinal(), access2.ordinal()));
    }

    public static PackageAccess max(final PackageAccess access1, PackageAccess access2) {
        return values.get(Math.max(access1.ordinal(), access2.ordinal()));
    }
}

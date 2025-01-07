package io.github.dmlloyd.modules.desc;

/**
 *
 */
public enum PackageAccess{
    PRIVATE,
    EXPORT,
    OPEN,
    ;

    public boolean isAtLeast(PackageAccess other) {
        return this.compareTo(other) >= 0;
    }
}

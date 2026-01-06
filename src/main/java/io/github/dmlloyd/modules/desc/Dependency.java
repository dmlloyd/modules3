package io.github.dmlloyd.modules.desc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import io.github.dmlloyd.modules.ModuleLoader;
import io.github.dmlloyd.modules.impl.Util;
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
        this(moduleName, Modifier.set(), Optional.empty());
    }

    /**
     * Construct a new instance with no modifiers and no module loader.
     *
     * @param moduleName the dependency name (must not be {@code null})
     * @param modifier the modifier to add (must not be {@code null})
     */
    public Dependency(String moduleName, Modifier modifier) {
        this(moduleName, Modifier.set(modifier), Optional.empty());
    }

    public Dependency withAdditionalPackageAccesses(Map<String, PackageAccess> packageAccesses) {
        Assert.checkNotNullParam("packageAccesses", packageAccesses);
        return new Dependency(this.moduleName, this.modifiers, this.moduleLoader, Util.merge(this.packageAccesses, packageAccesses, PackageAccess::max));
    }

    /**
     * {@return {@code true} if the dependency is synthetic}
     */
    public boolean isSynthetic() {
        return modifiers.contains(Modifier.SYNTHETIC);
    }

    /**
     * {@return {@code true} if the dependency is <em>not</em> synthetic}
     */
    public boolean isNonSynthetic() {
        return ! modifiers.contains(Modifier.SYNTHETIC);
    }

    /**
     * {@return {@code true} if the dependency is mandated}
     */
    public boolean isMandated() {
        return modifiers.contains(Modifier.MANDATED);
    }

    /**
     * {@return {@code true} if the dependency is <em>not</em> mandated}
     */
    public boolean isNonMandated() {
        return ! modifiers.contains(Modifier.MANDATED);
    }

    /**
     * {@return {@code true} if the dependency is optional}
     */
    public boolean isOptional() {
        return modifiers.contains(Modifier.OPTIONAL);
    }

    /**
     * {@return {@code true} if the dependency is <em>not</em> optional}
     */
    public boolean isNonOptional() {
        return ! modifiers.contains(Modifier.OPTIONAL);
    }

    /**
     * {@return {@code true} if the dependency is transitive}
     */
    public boolean isTransitive() {
        return modifiers.contains(Modifier.TRANSITIVE);
    }

    /**
     * {@return {@code true} if the dependency is <em>not</em> transitive}
     */
    public boolean isNonTransitive() {
        return ! modifiers.contains(Modifier.TRANSITIVE);
    }

    public boolean isLinked() {
        return modifiers.contains(Modifier.LINKED);
    }

    public boolean isNonLinked() {
        return ! modifiers.contains(Modifier.LINKED);
    }

    public boolean isRead() {
        return modifiers.contains(Modifier.READ);
    }

    public boolean isNonRead() {
        return ! modifiers.contains(Modifier.READ);
    }

    public boolean isServices() {
        return modifiers.contains(Modifier.SERVICES);
    }

    public boolean isNonServices() {
        return ! modifiers.contains(Modifier.SERVICES);
    }

    /**
     * Modifiers for dependencies.
     */
    public enum Modifier implements ModifierFlag {
        /**
         * The dependency is synthetic.
         * Synthetic dependencies are added by frameworks.
         */
        SYNTHETIC,
        /**
         * The dependency is mandated by specification.
         */
        MANDATED,
        /**
         * The dependency is optional.
         * If it is not found when the module is linked, do not fail.
         */
        OPTIONAL,
        /**
         * The dependency is transitive, which is to say that a dependency
         * on the module containing this dependency implies a dependency on the target as well.
         * Only globally exported packages from a transitive dependency will be made available.
         */
        TRANSITIVE,
        /**
         * The dependency should be linked for class loading.
         * Implies {@link #READ}.
         */
        LINKED,
        /**
         * The dependency should be readable from the source module.
         * Implied for unnamed and automatic modules.
         */
        READ,
        /**
         * Service implementations in the dependency are available to the source module.
         */
        SERVICES,
        ;

        public static final List<Modifier> values = List.of(values());

        private static final List<Modifiers<Modifier>> sets = List.copyOf(IntStream.range(0, 128)
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

        public static Modifiers<Modifier> set(Modifier modifier0, Modifier modifier1, Modifier modifier2, Modifier modifier3, Modifier modifier4) {
            return sets.get(bit(modifier0) | bit(modifier1) | bit(modifier2) | bit(modifier3) | bit(modifier4));
        }

        public static Modifiers<Modifier> set(Modifier modifier0, Modifier modifier1, Modifier modifier2, Modifier modifier3, Modifier modifier4, Modifier modifier5) {
            return sets.get(bit(modifier0) | bit(modifier1) | bit(modifier2) | bit(modifier3) | bit(modifier4) | bit(modifier5));
        }

        public static Modifiers<Modifier> set(Modifier modifier0, Modifier modifier1, Modifier modifier2, Modifier modifier3, Modifier modifier4, Modifier modifier5, Modifier modifier6) {
            return sets.get(bit(modifier0) | bit(modifier1) | bit(modifier2) | bit(modifier3) | bit(modifier4) | bit(modifier5) | bit(modifier6));
        }

        private static int bit(final Modifier item) {
            return item == null ? 0 : 1 << item.ordinal();
        }

        private static Modifiers<Modifier> forBits(int bits) {
            return sets.get(bits);
        }
    }

    /**
     * The standard {@code java.base} dependency, for convenience.
     */
    public static final Dependency JAVA_BASE = new Dependency("java.base", Modifier.set(Modifier.SYNTHETIC, Modifier.MANDATED), Optional.empty(), Map.of());
}

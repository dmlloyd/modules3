package io.github.dmlloyd.modules;

import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.security.AllPermission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

final class Util {
    static final PermissionCollection EMPTY_PERMISSIONS;
    static final PermissionCollection ALL_PERMISSIONS;

    static {
        Permissions emptyPermissions = new Permissions();
        emptyPermissions.setReadOnly();
        EMPTY_PERMISSIONS = emptyPermissions;
        AllPermission all = new AllPermission();
        PermissionCollection allPermissions = all.newPermissionCollection();
        allPermissions.add(all);
        allPermissions.setReadOnly();
        ALL_PERMISSIONS = allPermissions;
    }

    private Util() {}

    public static <T> Enumeration<T> enumeration(Iterator<T> iterator) {
        return new Enumeration<T>() {
            public boolean hasMoreElements() {
                return iterator.hasNext();
            }

            public T nextElement() {
                return iterator.next();
            }
        };
    }

    public static <T> Enumeration<T> enumeration(Iterable<T> iterable) {
        return enumeration(iterable.iterator());
    }

    public static <T> List<T> concat(List<T> a, List<T> b) {
        return switch (a.size()) {
            case 0 -> List.copyOf(b);
            case 1 -> switch (b.size()) {
                case 0 -> List.copyOf(a);
                case 1 -> List.of(a.get(0), b.get(0));
                case 2 -> List.of(a.get(0), b.get(0), b.get(1));
                case 3 -> List.of(a.get(0), b.get(0), b.get(1), b.get(2));
                default -> concatSlow(a, b);
            };
            case 2 -> switch (b.size()) {
                case 0 -> List.copyOf(a);
                case 1 -> List.of(a.get(0), a.get(1), b.get(0));
                case 2 -> List.of(a.get(0), a.get(1), b.get(0), b.get(1));
                case 3 -> List.of(a.get(0), a.get(1), b.get(0), b.get(1), b.get(2));
                default -> concatSlow(a, b);
            };
            case 3 -> switch (b.size()) {
                case 0 -> List.copyOf(a);
                case 1 -> List.of(a.get(0), a.get(1), a.get(2), b.get(0));
                case 2 -> List.of(a.get(0), a.get(1), a.get(2), b.get(0), b.get(1));
                case 3 -> List.of(a.get(0), a.get(1), a.get(2), b.get(0), b.get(1), b.get(2));
                default -> concatSlow(a, b);
            };
            default -> switch (b.size()) {
                case 0 -> List.copyOf(a);
                default -> concatSlow(a, b);
            };
        };
    }

    private static <T> List<T> concatSlow(final List<T> a, final List<T> b) {
        return Stream.concat(a.stream(), b.stream()).toList();
    }

    static final ModuleFinder EMPTY_MF = new ModuleFinder() {
        public Optional<ModuleReference> find(final String name) {
            return Optional.empty();
        }

        public Set<ModuleReference> findAll() {
            return Set.of();
        }
    };

}


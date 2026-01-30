package io.github.dmlloyd.modules.impl;

import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.security.AllPermission;
import java.security.PermissionCollection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * General utilities for the implementation.
 */
public final class Util {
    public static final PermissionCollection emptyPermissions;
    public static final PermissionCollection allPermissions;
    public static final Module myModule = Access.class.getModule();
    public static final ModuleLayer myLayer = Objects.requireNonNullElse(myModule.getLayer(), ModuleLayer.boot());
    public static final Map<String, Module> myLayerModules = myLayer.modules().stream()
        .collect(Collectors.toUnmodifiableMap(
            Module::getName,
            Function.identity()
        ));
    public static final ModuleFinder EMPTY_MF = new ModuleFinder() {
        public Optional<ModuleReference> find(final String name) {
            return Optional.empty();
        }

        public Set<ModuleReference> findAll() {
            return Set.of();
        }
    };

    static {
        // initialize permission collections
        AllPermission all = new AllPermission();
        PermissionCollection epc = all.newPermissionCollection();
        epc.setReadOnly();
        emptyPermissions = epc;
        PermissionCollection apc = all.newPermissionCollection();
        apc.add(all);
        apc.setReadOnly();
        allPermissions = apc;
    }

    public static String packageName(String binaryName) {
        int idx = binaryName.lastIndexOf('.');
        return idx == - 1 ? "" : binaryName.substring(0, idx);
    }

    public static String resourcePackageName(String resourcePath) {
        int idx = resourcePath.lastIndexOf('/');
        return idx == - 1 ? "" : resourcePath.substring(0, idx).replace('/', '.');
    }

    public static String autoModuleName(TextIter iter) {
        StringBuilder sb = new StringBuilder(iter.text().length());
        boolean dot = false;
        while (iter.hasNext()) {
            // parse each "word"; the first numeric is a version
            if (Character.isLetter(iter.peekNext())) {
                dot = false;
                do {
                    sb.appendCodePoint(iter.next());
                } while (iter.hasNext() && Character.isLetterOrDigit(iter.peekNext()));
            } else if (Character.isDigit(iter.peekNext())) {
                if (dot) {
                    // delete dot from string
                    sb.setLength(sb.length() - 1);
                }
                // version starts here
                return sb.toString();
            } else if (! dot) {
                iter.next();
                dot = true;
                sb.append('.');
            } else {
                // skip
                iter.next();
            }
        }
        // no version
        return sb.toString();
    }

    public static <K, V> Map<K, V> newHashMap(Object ignored) {
        return new HashMap<>();
    }

    public static <K, V> Collector<Map.Entry<K, V>, ?, Map<K, V>> toMap() {
        return Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    public static <E> Collector<E, ?, List<E>> toList() {
        return Collectors.toUnmodifiableList();
    }

    public static <E> Set<E> merge(Set<E> set1, Set<E> set2) {
        if (set1 == null || set1.isEmpty()) {
            return set2;
        } else if (set2 == null || set2.isEmpty()) {
            return set1;
        } else {
            return Stream.concat(set1.stream(), set2.stream()).collect(Collectors.toUnmodifiableSet());
        }
    }

    public static <E> List<E> concat(List<E> list1, List<E> list2) {
        if (list1 == null || list1.isEmpty()) {
            return list2;
        } else if (list2 == null || list2.isEmpty()) {
            return list1;
        } else if (list1.size() == 1 && list2.size() == 1) {
            return List.of(list1.get(0), list2.get(0));
        } else {
            return Stream.concat(list1.stream(), list2.stream()).collect(toList());
        }
    }

    public static <K, V> Map<K, V> merge(Map<K, V> map1, Map<K, V> map2) {
        if (map1 == null || map1.isEmpty()) {
            return map2;
        } else if (map2 == null || map2.isEmpty()) {
            return map1;
        } else {
            return Stream.concat(map1.entrySet().stream(), map2.entrySet().stream()).collect(toMap());
        }
    }

    public static <K, V> Map<K, V> merge(Map<K, V> map1, Map<K, V> map2, BinaryOperator<V> merge) {
        if (map1 == null || map1.isEmpty()) {
            return map2;
        } else if (map2 == null || map2.isEmpty()) {
            return map1;
        } else {
            return Stream.concat(map1.entrySet().stream(), map2.entrySet().stream())
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        merge
                    )
                );
        }
    }

    public static <T> List<T> listOf(Iterator<T> iter) {
        if (iter.hasNext()) {
            T t0 = iter.next();
            if (iter.hasNext()) {
                T t1 = iter.next();
                if (iter.hasNext()) {
                    // too many
                    ArrayList<T> list = new ArrayList<>();
                    list.add(t0);
                    list.add(t1);
                    iter.forEachRemaining(list::add);
                    return List.copyOf(list);
                } else {
                    return List.of(t0, t1);
                }
            } else {
                return List.of(t0);
            }
        } else {
            return List.of();
        }
    }

    public static <E, R> Iterator<R> mapped(Iterator<E> orig, Function<E, R> mapper) {
        return new Iterator<R>() {
            public boolean hasNext() {
                return orig.hasNext();
            }

            public R next() {
                return mapper.apply(orig.next());
            }
        };
    }

    public static <E> Iterator<E> filtered(Iterator<E> orig, Predicate<E> test) {
        return new Iterator<E>() {
            E next;

            public boolean hasNext() {
                while (next == null) {
                    if (! orig.hasNext()) {
                        return false;
                    }
                    E next = orig.next();
                    if (next == null) {
                        throw new NullPointerException();
                    }
                    if (test.test(next)) {
                        this.next = next;
                        return true;
                    }
                }
                return true;
            }

            public E next() {
                if (! hasNext()) throw new NoSuchElementException();
                try {
                    return next;
                } finally {
                    next = null;
                }
            }
        };
    }
}

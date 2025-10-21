package io.github.dmlloyd.modules.desc;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utilities.
 */
final class Util {
    private Util() {}

    static <E> Set<E> merge(Set<E> set1, Set<E> set2) {
        if (set1 == null || set1.isEmpty()) {
            return set2;
        } else if (set2 == null || set2.isEmpty()) {
            return set1;
        } else {
            return Stream.concat(set1.stream(), set2.stream()).collect(Collectors.toUnmodifiableSet());
        }
    }

    static <E> List<E> concat(List<E> list1, List<E> list2) {
        if (list1 == null || list1.isEmpty()) {
            return list2;
        } else if (list2 == null || list2.isEmpty()) {
            return list1;
        } else {
            return Stream.concat(list1.stream(), list2.stream()).toList();
        }
    }

    static <K, V> Map<K, V> merge(Map<K, V> map1, Map<K, V> map2) {
        if (map1 == null || map1.isEmpty()) {
            return map2;
        } else if (map2 == null || map2.isEmpty()) {
            return map1;
        } else {
            return Stream.concat(map1.entrySet().stream(), map2.entrySet().stream())
                .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue
                )
            );
        }
    }

    static <K, V> Map<K, V> merge(Map<K, V> map1, Map<K, V> map2, BinaryOperator<V> merge) {
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
}

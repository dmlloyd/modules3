package io.github.dmlloyd.modules.desc;

import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

/**
 * A set of modifiers.
 */
@SuppressWarnings("unchecked")
public final class Modifiers<M extends Enum<M> & ModifierFlag> {
    private final int flags;

    Modifiers(final int flags) {
        this.flags = flags;
    }

    private static final List<?> allValues = IntStream.range(0, 64).mapToObj(Modifiers::new).toList();

    @SuppressWarnings("unchecked")
    public static <M extends Enum<M> & ModifierFlag> Modifiers<M> of() {
        return (Modifiers<M>) allValues.get(0);
    }

    @SuppressWarnings("unchecked")
    public static <M extends Enum<M> & ModifierFlag> Modifiers<M> of(M item1) {
        return (Modifiers<M>) allValues.get(bit(item1));
    }

    @SuppressWarnings("unchecked")
    public static <M extends Enum<M> & ModifierFlag> Modifiers<M> of(M item1, M item2) {
        return (Modifiers<M>) allValues.get(bit(item1) | bit(item2));
    }

    @SuppressWarnings("unchecked")
    public static <M extends Enum<M> & ModifierFlag> Modifiers<M> of(M item1, M item2, M item3) {
        return (Modifiers<M>) allValues.get(bit(item1) | bit(item2) | bit(item3));
    }

    @SuppressWarnings("unchecked")
    public static <M extends Enum<M> & ModifierFlag> Modifiers<M> of(M item1, M item2, M item3, M item4) {
        return (Modifiers<M>) allValues.get(bit(item1) | bit(item2) | bit(item3));
    }

    @SuppressWarnings("unchecked")
    public static <M extends Enum<M> & ModifierFlag> Modifiers<M> of(M... items) {
        int val = 0;
        for (M item : items) {
            val |= bit(item);
        }
        return (Modifiers<M>) allValues.get(val);
    }

    @SuppressWarnings("unchecked")
    public static <M extends Enum<M> & ModifierFlag> Modifiers<M> of(Collection<M> items) {
        int val = 0;
        for (M item : items) {
            val |= bit(item);
        }
        return (Modifiers<M>) allValues.get(val);
    }

    public boolean contains(M item) {
        return item != null && (flags & bit(item)) != 0;
    }

    private static <M extends Enum<M> & ModifierFlag> int bit(final M item) {
        return 1 << item.ordinal();
    }

    public Modifiers<M> with(M item) {
        int newFlags = flags | bit(item);
        return newFlags == flags ? this : (Modifiers<M>) allValues.get(newFlags);
    }

    public Modifiers<M> without(M item) {
        int newFlags = flags & ~bit(item);
        return newFlags == flags ? this : (Modifiers<M>) allValues.get(newFlags);
    }
}

package io.github.dmlloyd.modules.desc;

import java.util.List;
import java.util.function.IntFunction;

/**
 * A set of modifiers.
 */
public final class Modifiers<M extends Enum<M> & ModifierFlag> {
    private final List<M> values;
    private final IntFunction<Modifiers<M>> setFn;
    private final int flags;

    Modifiers(List<M> values, IntFunction<Modifiers<M>> setFn, final int flags) {
        this.values = values;
        this.setFn = setFn;
        this.flags = flags;
    }

    public String toString() {
        int flags = this.flags;
        if (flags != 0) {
            StringBuilder sb = new StringBuilder();
            int hob = Integer.highestOneBit(flags);
            sb.append(values.get(Integer.numberOfTrailingZeros(hob)));
            flags &= ~hob;
            while (flags != 0) {
                sb.append(' ');
                hob = Integer.highestOneBit(flags);
                sb.append(values.get(Integer.numberOfTrailingZeros(hob)));
                flags &= ~hob;
            }
            return sb.toString();
        }
        return "(none)";
    }

    public boolean contains(M item) {
        return containsAny(bit(item));
    }

    public boolean containsAny(M item0, M item1) {
        return containsAny(bit(item0) | bit(item1));
    }

    public boolean containsAny(M item0, M item1, M item2) {
        return containsAny(bit(item0) | bit(item1) | bit(item2));
    }

    public boolean containsAny(M item0, M item1, M item2, M item3) {
        return containsAny(bit(item0) | bit(item1) | bit(item2) | bit(item3));
    }

    private boolean containsAny(int bits) {
        return (flags & bits) != 0;
    }

    @SafeVarargs
    public final boolean containsAll(M... items) {
        int bits = 0;
        for (M item : items) {
            bits |= bit(item);
        }
        return containsAll(bits);
    }

    public boolean containsAll(M item0, M item1) {
        return containsAll(bit(item0) | bit(item1));
    }

    public boolean containsAll(M item0, M item1, M item2) {
        return containsAll(bit(item0) | bit(item1) | bit(item2));
    }

    public boolean containsAll(M item0, M item1, M item2, M item3) {
        return containsAll(bit(item0) | bit(item1) | bit(item2) | bit(item3));
    }

    @SafeVarargs
    public final boolean containsAny(M... items) {
        int bits = 0;
        for (M item : items) {
            bits |= bit(item);
        }
        return containsAny(bits);
    }

    private boolean containsAll(int bits) {
        return (flags & bits) == bits;
    }

    private static <M extends Enum<M> & ModifierFlag> int bit(final M item) {
        return item == null ? 0 : 1 << item.ordinal();
    }

    public Modifiers<M> with(M item) {
        int newFlags = flags | bit(item);
        return newFlags == flags ? this : setFn.apply(newFlags);
    }

    public Modifiers<M> withAll(M item0, M item1) {
        int newFlags = flags | bit(item0) | bit(item1);
        return newFlags == flags ? this : setFn.apply(newFlags);
    }

    public Modifiers<M> withAll(Modifiers<M> other) {
        int newFlags = flags | other.flags;
        return newFlags == flags ? this : setFn.apply(newFlags);
    }

    public Modifiers<M> without(M item) {
        int newFlags = flags & ~bit(item);
        return newFlags == flags ? this : setFn.apply(newFlags);
    }
}

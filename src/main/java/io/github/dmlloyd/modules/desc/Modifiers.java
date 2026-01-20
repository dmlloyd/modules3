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
        return item != null && (flags & bit(item)) != 0;
    }

    private static <M extends Enum<M> & ModifierFlag> int bit(final M item) {
        return 1 << item.ordinal();
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

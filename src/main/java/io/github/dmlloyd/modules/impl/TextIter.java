package io.github.dmlloyd.modules.impl;

import java.util.function.IntPredicate;

/**
 * A simple iterator over string code points, to ease parser design.
 */
public final class TextIter {
    final String text;
    int idx;

    private TextIter(final String text) {
        this.text = text;
    }

    public static TextIter of(final String text) {
        return new TextIter(text);
    }

    public boolean hasNext() {
        return idx < text.length();
    }

    public int position() {
        return idx;
    }

    public int skipWhiteSpace() {
        int cnt = 0;
        while (hasNext() && Character.isWhitespace(peekNext())) {
            next();
            cnt ++;
        }
        return cnt;
    }

    public int skipUntil(IntPredicate predicate) {
        int cnt = 0;
        while (hasNext() && ! predicate.test(peekNext())) {
            next();
            cnt ++;
        }
        return cnt;
    }

    public int position(int newPosition) {
        int old = idx;
        idx = newPosition;
        return old;
    }

    public int peekNext() {
        return text.codePointAt(idx);
    }

    public int next() {
        int cp = text.codePointAt(idx);
        idx += Character.charCount(cp);
        return cp;
    }

    public boolean match(String exact) {
        if (text.regionMatches(idx, exact, 0, exact.length())) {
            idx += exact.length();
            return true;
        } else {
            return false;
        }
    }

    public boolean matchIgnoreCase(String exact) {
        if (text.regionMatches(true, idx, exact, 0, exact.length())) {
            idx += exact.length();
            return true;
        } else {
            return false;
        }
    }

    public String text() {
        return text;
    }

    public String substring() {
        return text.substring(0, idx);
    }

    public String substring(final int start) {
        return text.substring(start, idx);
    }

    public String substring(final int start, final int end) {
        return text.substring(start, end);
    }
}

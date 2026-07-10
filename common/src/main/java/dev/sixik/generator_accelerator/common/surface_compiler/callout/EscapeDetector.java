package dev.sixik.generator_accelerator.common.surface_compiler.callout;

import java.util.IdentityHashMap;
import java.util.Map;

public final class EscapeDetector {
    private final Map<Object, BorrowToken> borrowed = new IdentityHashMap<>();

    public void borrow(Object value, BorrowToken token) {
        if (value != null) {
            token.checkOpen();
            this.borrowed.put(value, token);
        }
    }

    public boolean escaped(Object value) {
        BorrowToken token = this.borrowed.get(value);
        return token != null && token.closed();
    }

    public void clear() {
        this.borrowed.clear();
    }
}

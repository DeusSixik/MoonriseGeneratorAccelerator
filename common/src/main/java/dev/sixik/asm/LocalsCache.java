package dev.sixik.asm;

import java.util.HashMap;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

public final class LocalsCache {

    private final Map<String, Local> map = new HashMap<>();

    /**
     * scope tracking (no map copy)
     */
    private Object[] createdKeys = new Object[64];
    private int createdSp = 0;

    private int[] scopeMarkers = new int[16];
    private int scopeDepth = 0;

    /**
     * Enter a scope. All locals/aliases created inside can be rolled back.
     */
    public void pushScope() {
        if (scopeDepth == scopeMarkers.length) {
            int[] n = new int[scopeMarkers.length * 2];
            System.arraycopy(scopeMarkers, 0, n, 0, scopeMarkers.length);
            scopeMarkers = n;
        }
        scopeMarkers[scopeDepth++] = createdSp;
    }

    /**
     * try-with-resources helper.
     */
    public Scope scope() {
        pushScope();
        return new Scope(this);
    }

    public void popScope() {
        if (scopeDepth == 0) throw new IllegalStateException("popScope() without pushScope()");
        int start = scopeMarkers[--scopeDepth];

        for (int i = createdSp - 1; i >= start; i--) {
            String key = (String) createdKeys[i];
            createdKeys[i] = null;
            map.remove(key);
        }
        createdSp = start;
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    public Local get(String key) {
        return map.get(key);
    }

    public void clear() {
        map.clear();
        createdSp = 0;
        scopeDepth = 0;
        // createdKeys можно не чистить полностью — createdSp=0 достаточно
    }

    /**
     * Load local by key (throws if absent).
     */
    public void load(AsmCtx ctx, String key) {
        Local l = map.get(key);
        if (l == null) throw new IllegalStateException("Local not found: " + key);
        ctx.mv().visitVarInsn(l.loadOpcode, l.slot);
    }

    /**
     * Get or create an int local: emitter must leave int on stack.
     */
    public int getOrCreateInt(AsmCtx ctx, String key, Runnable emitValueToStack) {
        Local l = map.get(key);
        if (l != null) return l.slot;

        int slot = ctx.newLocalInt();
        emitValueToStack.run();
        ctx.istore(slot);

        putCreated(key, new Local(slot, ILOAD, ISTORE, "I"));
        return slot;
    }

    /**
     * Get or create a double local: emitter must leave double on stack.
     */
    public int getOrCreateDouble(AsmCtx ctx, String key, Runnable emitValueToStack) {
        Local l = map.get(key);
        if (l != null) return l.slot;

        int slot = ctx.newLocalDouble();
        emitValueToStack.run();
        ctx.dstore(slot);

        putCreated(key, new Local(slot, DLOAD, DSTORE, "D"));
        return slot;
    }

    /**
     * Get or create a ref local: emitter must leave ref on stack.
     */
    public int getOrCreateRef(AsmCtx ctx, String key, String desc, Runnable emitValueToStack) {
        Local l = map.get(key);
        if (l != null) return l.slot;

        int slot = ctx.newLocalRef();
        emitValueToStack.run();
        ctx.astore(slot);

        putCreated(key, new Local(slot, ALOAD, ASTORE, desc));
        return slot;
    }

    /**
     * Create an alias key that points to the same Local (same slot).
     * Scope-safe: alias will be removed on popScope() if created inside the scope.
     */
    public void alias(String newKey, String existingKey) {
        Local l = map.get(existingKey);
        if (l == null) throw new IllegalStateException("No local: " + existingKey);

        // Если newKey уже существует — реши сам: либо молча перезаписывать, либо запрещать.
        if (map.containsKey(newKey)) {
            throw new IllegalStateException("Alias key already exists: " + newKey);
        }

        putCreated(newKey, l);
    }

    // --- internal: store in map + track for scope rollback ---
    private void putCreated(String key, Local local) {
        map.put(key, local);

        // track only if inside at least one scope
        if (scopeDepth != 0) {
            if (createdSp == createdKeys.length) {
                Object[] n = new Object[createdKeys.length * 2];
                System.arraycopy(createdKeys, 0, n, 0, createdKeys.length);
                createdKeys = n;
            }
            createdKeys[createdSp++] = key;
        }
    }

    public static final class Local {
        public final int slot;
        public final int loadOpcode;
        public final int storeOpcode;
        public final String desc;

        Local(int slot, int loadOpcode, int storeOpcode, String desc) {
            this.slot = slot;
            this.loadOpcode = loadOpcode;
            this.storeOpcode = storeOpcode;
            this.desc = desc;
        }
    }

    public static final class Scope implements AutoCloseable {
        private LocalsCache owner;
        private boolean closed;

        private Scope(LocalsCache owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            owner.popScope();
            owner = null;
        }
    }
}

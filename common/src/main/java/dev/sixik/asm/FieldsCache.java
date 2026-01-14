package dev.sixik.asm;

import org.objectweb.asm.ClassWriter;

import java.util.HashMap;
import java.util.Map;

public final class FieldsCache {
    private final Map<String, String> descByName = new HashMap<>();

    /** Declare field once (idempotent). */
    public void declareOnce(ClassWriter cw, int access, String name, String desc) {
        String prev = descByName.putIfAbsent(name, desc);
        if (prev == null) {
            cw.visitField(access, name, desc, null, null).visitEnd();
        } else if (!prev.equals(desc)) {
            throw new IllegalStateException("Field " + name + " desc mismatch: " + prev + " vs " + desc);
        }
    }

    public String desc(String name) { return descByName.get(name); }
}

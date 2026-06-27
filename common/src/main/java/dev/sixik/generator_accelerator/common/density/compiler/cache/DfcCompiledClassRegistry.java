package dev.sixik.generator_accelerator.common.density.compiler.cache;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Compile-time metadata keyed by the stable hidden-class base name
 * ({@code pkg.CompiledDF_xxx}, without the VM-added {@code /0x...} suffix).
 */
public final class DfcCompiledClassRegistry {
    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private DfcCompiledClassRegistry() {
    }

    public record Entry(String classBaseName, String sourceRootClass,
                        boolean latticeEmitted, boolean slabInnerProgramPresent,
                        boolean cellAddLatticeSpecialized,
                        boolean cellAddExternSpecialized,
                        String rootDebug) {
    }

    public static void record(String classInternalName, String sourceRootClass,
                              boolean latticeEmitted, boolean slabInnerProgramPresent,
                              boolean cellAddLatticeSpecialized, boolean cellAddExternSpecialized,
                              String rootDebug) {
        ENTRIES.putIfAbsent(normalize(classInternalName), new Entry(
                normalize(classInternalName), sourceRootClass, latticeEmitted, slabInnerProgramPresent,
                cellAddLatticeSpecialized, cellAddExternSpecialized, rootDebug));
    }

    public static Entry lookup(String runtimeClassName) {
        return ENTRIES.get(normalize(runtimeClassName));
    }

    public static void clear() {
        ENTRIES.clear();
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        int hiddenSuffix = name.indexOf("/0x");
        String base = hiddenSuffix >= 0 ? name.substring(0, hiddenSuffix) : name;
        return base.replace('/', '.');
    }
}

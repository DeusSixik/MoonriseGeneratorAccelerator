package dev.sixik.generator_accelerator.common.density.compiler.cache;

import dev.sixik.generator_accelerator.api.config.GAConfigHolder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compile-time metadata keyed by the stable hidden-class base name
 * ({@code pkg.CompiledDF_xxx}, without the VM-added {@code /0x...} suffix).
 */
public final class DfcCompiledClassRegistry {
    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Deque<String> INSERTION_ORDER = new ArrayDeque<>();

    private DfcCompiledClassRegistry() {
    }

    public record Entry(String classBaseName, String sourceRootClass,
                        boolean latticeEmitted,
                        boolean cellAddLatticeSpecialized,
                        boolean cellAddBeardifierSpecialized,
                        boolean cellAddExternSpecialized,
                        String rootDebug,
                        String splineDebug) {
    }

    public static void record(String classInternalName, String sourceRootClass,
                              boolean latticeEmitted,
                              boolean cellAddLatticeSpecialized, boolean cellAddBeardifierSpecialized,
                              boolean cellAddExternSpecialized,
                              String rootDebug, String splineDebug) {
        String normalized = normalize(classInternalName);
        Entry newEntry = new Entry(
                normalized, sourceRootClass, latticeEmitted,
                cellAddLatticeSpecialized, cellAddBeardifierSpecialized,
                cellAddExternSpecialized, rootDebug, splineDebug);
        Entry previous = ENTRIES.putIfAbsent(normalized, newEntry);
        if (previous == null) {
            synchronized (INSERTION_ORDER) {
                INSERTION_ORDER.addLast(normalized);
                trimToBudget();
            }
        }
    }

    public static Entry lookup(String runtimeClassName) {
        return ENTRIES.get(normalize(runtimeClassName));
    }

    public static List<Entry> snapshotRecent() {
        synchronized (INSERTION_ORDER) {
            ArrayList<Entry> out = new ArrayList<>(INSERTION_ORDER.size());
            for (String classBaseName : INSERTION_ORDER) {
                Entry entry = ENTRIES.get(classBaseName);
                if (entry != null) {
                    out.add(entry);
                }
            }
            return out;
        }
    }

    public static void clear() {
        ENTRIES.clear();
        synchronized (INSERTION_ORDER) {
            INSERTION_ORDER.clear();
        }
    }

    private static void trimToBudget() {
        int maxEntries = Math.max(1, GAConfigHolder.getConfig().dfc.registryMaxEntries);
        while (ENTRIES.size() > maxEntries) {
            String oldest = INSERTION_ORDER.pollFirst();
            if (oldest == null) {
                return;
            }
            ENTRIES.remove(oldest);
        }
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

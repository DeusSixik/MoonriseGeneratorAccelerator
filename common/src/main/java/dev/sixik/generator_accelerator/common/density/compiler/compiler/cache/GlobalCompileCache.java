package dev.sixik.generator_accelerator.common.density.compiler.compiler.cache;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Reuses a hidden class + pre-resolved {@link MethodHandle}s when
 * {@link CompilationFingerprint#shapeSha256} matches. Values are strong references.
 * <p>Compilation uses {@link ConcurrentHashMap#computeIfAbsent} on the shape fingerprint
 * <strong>only</strong> — never a global lock — so different router fields compile
 * in parallel, while the same graph only defines one hidden class.
 */
public final class GlobalCompileCache {
    public static final GlobalCompileCache INSTANCE = new GlobalCompileCache();

    public record FingerprintKey(byte[] sha256) {
        public FingerprintKey {
            sha256 = sha256 == null ? null : sha256.clone();
        }
        @Override
        public boolean equals(Object o) {
            return o instanceof FingerprintKey f && java.security.MessageDigest.isEqual(sha256, f.sha256);
        }
        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(sha256);
        }
    }

    public record CopiedClassBundle(
            String classInternalName,
            String sourceRootClass,
            String rootDebug,
            byte[] exactSha256,
            Class<? extends CompiledDensityFunction> cls,
            byte[] bytecode,
            MethodHandle constructorHandle,
            MethodHandle[] helperHandles,
            int helpersEmitted,
            /**
             * {@code true} when the codegen emitted a {@code lattice_y} +
             * {@code lattice_inner} pair and a {@code fillArray} override driven by
             * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.CellLatticeOption}.
             * Recorded here (rather than re-derived) so {@code /dfc stats} can report
             * the lattice rate even on global-cache hits — those don't re-run the
             * codegen so we'd otherwise undercount.
             */
            boolean latticeEmitted,
            boolean cellAddLatticeSpecialized,
            boolean cellAddExternSpecialized,
            /**
             * Native {@code SlabInnerNativeProgram} buffer machine code from
             * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.Codegen#emit}
             * when a slab batch was compiled; may be null when lattice is scalar-only or
             * native program compilation failed. Stored on the bundle so
             * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler#compileWithDetail}
             * does not re-run the lattice → slab plan → tryCompile path on every
             * link (and on global-cache hits the constructor still receives the same
             * slab program as the defining compile).
             */
            byte[] slabNativeProgram,
            /**
             * Constant table companion for {@link #slabNativeProgram}, or null
             * when the program is null/empty.
             */
            double[] slabNativeConstants) {
        public CopiedClassBundle {
            exactSha256 = exactSha256 == null ? null : exactSha256.clone();
        }
    }

    /**
     * @param reused false only when this thread ran {@code onMiss} (first
     *         successful compile of this fingerprint); true on cache get or
     *         when another thread installed the entry first.
     */
    public record LookupResult(boolean reused, CopiedClassBundle bundle) {}

    private final ConcurrentHashMap<FingerprintKey, CopiedClassBundle> bundles =
            new ConcurrentHashMap<>(64, 0.5f, 2);

    /**
     * Sum of {@code bundle.bytecode.length} across every cache hit (lifetime).
     * Each hit means the JVM did <em>not</em> re-emit that many bytes through
     * ASM and {@code defineHiddenClass} — the only meaningful unit for
     * "what did the cache save us". Surfaced via {@code /dfc stats}.
     */
    private final AtomicLong bytesSaved = new AtomicLong();
    /**
     * Count of {@code CompiledDensityFunction} instances backed by a cached
     * hidden class (vs. one freshly defined for them). Lifetime counter; one
     * "physical" hidden class can serve many shared instances when the same
     * IR fingerprint shows up in different routers (datapack reload, /reload,
     * dimension stack with shared overworld noise, etc.).
     */
    private final AtomicLong instancesShared = new AtomicLong();
    /**
     * Count of shape-cache hits where the exact, identity-bearing fingerprint did
     * not match the bundle's defining compile. This is the useful Phase-3 signal:
     * the hidden class shape was reused across distinct constructor-supplied
     * runtime bindings.
     */
    private final AtomicLong shapeHitsAcrossExactMisses = new AtomicLong();

    private GlobalCompileCache() {}

    public LookupResult getOrCompile(byte[] shapeSha256, byte[] exactSha256, Supplier<CopiedClassBundle> onMiss) {
        FingerprintKey key = new FingerprintKey(shapeSha256);
        CopiedClassBundle fast = bundles.get(key);
        if (fast != null) {
            recordHit(fast, exactSha256);
            return new LookupResult(true, fast);
        }
        var ran = new AtomicBoolean(false);
        CopiedClassBundle b = bundles.computeIfAbsent(key, k -> {
            ran.set(true);
            return onMiss.get();
        });
        boolean reused = !ran.get();
        if (reused) {
            recordHit(b, exactSha256);
        }
        return new LookupResult(reused, b);
    }

    private void recordHit(CopiedClassBundle bundle, byte[] exactSha256) {
        instancesShared.incrementAndGet();
        if (bundle.bytecode != null) {
            bytesSaved.addAndGet(bundle.bytecode.length);
        }
        if (bundle.exactSha256 != null
                && exactSha256 != null
                && !java.security.MessageDigest.isEqual(bundle.exactSha256, exactSha256)) {
            shapeHitsAcrossExactMisses.incrementAndGet();
        }
    }

    /** See {@link #bytesSaved}. */
    public long bytesSaved() {
        return bytesSaved.get();
    }

    /** See {@link #instancesShared}. */
    public long instancesShared() {
        return instancesShared.get();
    }

    public long shapeHitsAcrossExactMisses() {
        return shapeHitsAcrossExactMisses.get();
    }

    /** Test / reload support */
    public void clear() {
        bundles.clear();
        bytesSaved.set(0);
        instancesShared.set(0);
        shapeHitsAcrossExactMisses.set(0);
    }

    public int size() {
        return bundles.size();
    }

    public List<CopiedClassBundle> snapshotBundles() {
        return new ArrayList<>(bundles.values());
    }

    /**
     * Result of {@link #verifyConsistency()} — a tally of bundles that passed
     * each invariant. Surfaced through {@code /dfc cachetest} for paranoid
     * debugging when chasing a suspected cache poisoning regression.
     */
    public record ConsistencyReport(
            int bundlesChecked,
            int classNamePrefixMismatches,
            int helperHandleArrayLengthMismatches,
            int nullHelperHandles,
            int nullConstructorHandles,
            int nullOrEmptyBytecodes) {
        /** {@code true} iff every check passed for every bundle. */
        public boolean ok() {
            return classNamePrefixMismatches == 0
                    && helperHandleArrayLengthMismatches == 0
                    && nullHelperHandles == 0
                    && nullConstructorHandles == 0
                    && nullOrEmptyBytecodes == 0;
        }
    }

    /**
     * Walks every cached bundle and checks the structural invariants we rely
     * on at runtime. This is the cheap, in-memory cousin of "re-fingerprint
     * the IR and assert the SHA matches the key" — we cannot recover the IR
     * from a {@link CopiedClassBundle} (it intentionally only stores the
     * compiled bytecode + handles), but we can verify:
     *
     * <ul>
     *   <li>Bytecode is non-null and non-empty (a {@code defineHiddenClass}
     *       artifact ought to be at least a hundred bytes; we conservatively
     *       check for {@code length > 0} only, since some pathological
     *       arithmetic-only DFs end up extremely small).</li>
     *   <li>{@code classInternalName} contains the {@link
     *       CompilationFingerprint#stableClassSuffix(byte[]) stable suffix}
     *       derived from the cache key — verifies that the value's name is
     *       in lock-step with its key and we did not somehow swap two
     *       bundles around (which would be silent corruption otherwise).</li>
     *   <li>{@code helperHandles.length == helpersEmitted}.</li>
     *   <li>No null entries in {@code helperHandles}.</li>
     *   <li>{@code constructorHandle} is non-null.</li>
     * </ul>
     *
     * <p>Lifetime / staleness note: this method is read-only and consistent
     * under concurrent {@link #getOrCompile} calls (we iterate a
     * {@link ConcurrentHashMap}); a bundle inserted concurrently with
     * iteration may or may not be checked in this pass, but a bundle
     * <em>removed</em> during iteration cannot happen because the cache is
     * append-only.
     */
    public ConsistencyReport verifyConsistency() {
        int total = 0;
        int classNameMismatches = 0;
        int helperLengthMismatches = 0;
        int nullHelpers = 0;
        int nullCtors = 0;
        int badBytecode = 0;

        for (var entry : bundles.entrySet()) {
            total++;
            CopiedClassBundle b = entry.getValue();
            byte[] keyBytes = entry.getKey().sha256();
            String expectedSuffix = CompilationFingerprint.stableClassSuffix(keyBytes);
            if (b.classInternalName == null || !b.classInternalName.contains(expectedSuffix)) {
                classNameMismatches++;
            }
            if (b.bytecode == null || b.bytecode.length == 0) {
                badBytecode++;
            }
            if (b.helperHandles == null || b.helperHandles.length != b.helpersEmitted) {
                helperLengthMismatches++;
            } else {
                for (MethodHandle mh : b.helperHandles) {
                    if (mh == null) {
                        nullHelpers++;
                        break;
                    }
                }
            }
            if (b.constructorHandle == null) {
                nullCtors++;
            }
        }
        return new ConsistencyReport(total, classNameMismatches, helperLengthMismatches,
                nullHelpers, nullCtors, badBytecode);
    }
}

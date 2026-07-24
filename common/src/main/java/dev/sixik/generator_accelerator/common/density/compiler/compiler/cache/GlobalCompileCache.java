package dev.sixik.generator_accelerator.common.density.compiler.compiler.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;

import java.lang.invoke.MethodHandle;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Reuses a hidden class + pre-resolved {@link MethodHandle}s when the exact
 * {@link CompilationFingerprint#sha256} cache key matches. Bundles are held strongly
 * for the current server lifecycle and dropped explicitly on lifecycle reset.
 * Keeping the bundle itself strong is important: compiled instances hold the
 * bundle's {@link MethodHandle}s, not the bundle wrapper. With weak values the
 * wrapper could be collected while the hidden class was still alive through an
 * existing compiled router, causing a later same-shape compile to define a
 * duplicate hidden class instead of reusing the existing one.
 * <p>Compilation uses Caffeine's atomic per-key load on the cache fingerprint
 * <strong>only</strong> - never a global lock - so different router fields compile
 * in parallel, while the same graph only defines one hidden class.
 */
public final class GlobalCompileCache {
    public static final GlobalCompileCache INSTANCE = new GlobalCompileCache();

    public static final class FingerprintKey {
        private final byte[] sha256;

            public FingerprintKey(byte[] sha256) {
                sha256 = sha256 == null ? null : sha256.clone();
                this.sha256 = sha256;
            }

        @Override
            public boolean equals(Object o) {
                return o instanceof FingerprintKey f && MessageDigest.isEqual(sha256, f.sha256);
            }

        @Override
            public int hashCode() {
                return Arrays.hashCode(sha256);
            }

        public byte[] sha256() {
            return sha256;
        }

        @Override
        public String toString() {
            return "FingerprintKey[" +
                    "sha256=" + sha256 + ']';
        }

        }

    public static final class CopiedClassBundle {
        private final String classInternalName;
        private final String sourceRootClass;
        private final String rootDebug;
        private final String splineDebug;
        private final byte[] exactSha256;
        private final Class<? extends CompiledDensityFunction> cls;
        private final byte[] bytecode;
        private final MethodHandle constructorHandle;
        private final MethodHandle[] helperHandles;
        private final int helpersEmitted;
        private final boolean latticeEmitted;
        private final boolean cellAddLatticeSpecialized;
        private final boolean cellAddExternSpecialized;

            public CopiedClassBundle(String classInternalName, String sourceRootClass, String rootDebug, String splineDebug, byte[] exactSha256, Class<? extends CompiledDensityFunction> cls, byte[] bytecode, MethodHandle constructorHandle, MethodHandle[] helperHandles, int helpersEmitted, boolean latticeEmitted, boolean cellAddLatticeSpecialized, boolean cellAddExternSpecialized) {
                exactSha256 = exactSha256 == null ? null : exactSha256.clone();
                this.classInternalName = classInternalName;
                this.sourceRootClass = sourceRootClass;
                this.rootDebug = rootDebug;
                this.splineDebug = splineDebug;
                this.exactSha256 = exactSha256;
                this.cls = cls;
                this.bytecode = bytecode;
                this.constructorHandle = constructorHandle;
                this.helperHandles = helperHandles;
                this.helpersEmitted = helpersEmitted;
                this.latticeEmitted = latticeEmitted;
                this.cellAddLatticeSpecialized = cellAddLatticeSpecialized;
                this.cellAddExternSpecialized = cellAddExternSpecialized;
            }

        public String classInternalName() {
            return classInternalName;
        }

        public String sourceRootClass() {
            return sourceRootClass;
        }

        public String rootDebug() {
            return rootDebug;
        }

        public String splineDebug() {
            return splineDebug;
        }

        public byte[] exactSha256() {
            return exactSha256;
        }

        public Class<? extends CompiledDensityFunction> cls() {
            return cls;
        }

        public byte[] bytecode() {
            return bytecode;
        }

        public MethodHandle constructorHandle() {
            return constructorHandle;
        }

        public MethodHandle[] helperHandles() {
            return helperHandles;
        }

        public int helpersEmitted() {
            return helpersEmitted;
        }

        public boolean latticeEmitted() {
            return latticeEmitted;
        }

        public boolean cellAddLatticeSpecialized() {
            return cellAddLatticeSpecialized;
        }

        public boolean cellAddExternSpecialized() {
            return cellAddExternSpecialized;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (CopiedClassBundle) obj;
            return Objects.equals(this.classInternalName, that.classInternalName) &&
                    Objects.equals(this.sourceRootClass, that.sourceRootClass) &&
                    Objects.equals(this.rootDebug, that.rootDebug) &&
                    Objects.equals(this.splineDebug, that.splineDebug) &&
                    Objects.equals(this.exactSha256, that.exactSha256) &&
                    Objects.equals(this.cls, that.cls) &&
                    Objects.equals(this.bytecode, that.bytecode) &&
                    Objects.equals(this.constructorHandle, that.constructorHandle) &&
                    Objects.equals(this.helperHandles, that.helperHandles) &&
                    this.helpersEmitted == that.helpersEmitted &&
                    this.latticeEmitted == that.latticeEmitted &&
                    this.cellAddLatticeSpecialized == that.cellAddLatticeSpecialized &&
                    this.cellAddExternSpecialized == that.cellAddExternSpecialized;
        }

        @Override
        public int hashCode() {
            return Objects.hash(classInternalName, sourceRootClass, rootDebug, splineDebug, exactSha256, cls, bytecode, constructorHandle, helperHandles, helpersEmitted, latticeEmitted, cellAddLatticeSpecialized, cellAddExternSpecialized);
        }

        @Override
        public String toString() {
            return "CopiedClassBundle[" +
                    "classInternalName=" + classInternalName + ", " +
                    "sourceRootClass=" + sourceRootClass + ", " +
                    "rootDebug=" + rootDebug + ", " +
                    "splineDebug=" + splineDebug + ", " +
                    "exactSha256=" + exactSha256 + ", " +
                    "cls=" + cls + ", " +
                    "bytecode=" + bytecode + ", " +
                    "constructorHandle=" + constructorHandle + ", " +
                    "helperHandles=" + helperHandles + ", " +
                    "helpersEmitted=" + helpersEmitted + ", " +
                    "latticeEmitted=" + latticeEmitted + ", " +
                    "cellAddLatticeSpecialized=" + cellAddLatticeSpecialized + ", " +
                    "cellAddExternSpecialized=" + cellAddExternSpecialized + ']';
        }

        }

    /**
     * @param reused false only when this thread ran {@code onMiss} (first
     *         successful compile of this fingerprint); true on cache get or
     *         when another thread installed the entry first.
     */
    public record LookupResult(boolean reused, CopiedClassBundle bundle) {}

    private final Cache<FingerprintKey, CopiedClassBundle> bundles = Caffeine.newBuilder()
            .initialCapacity(64)
            .build();

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
     * Count of legacy shape-cache hits where the exact, identity-bearing fingerprint
     * did not match the bundle's defining compile. With exact-keyed reuse this stays
     * at zero; keep it for diagnostics if shape reuse is reintroduced later.
     */
    private final AtomicLong shapeHitsAcrossExactMisses = new AtomicLong();

    private GlobalCompileCache() {}

    public LookupResult getOrCompile(byte[] cacheSha256, byte[] exactSha256, Supplier<CopiedClassBundle> onMiss) {
        FingerprintKey key = new FingerprintKey(cacheSha256);
        CopiedClassBundle fast = bundles.getIfPresent(key);
        if (fast != null) {
            if (!matchesExact(fast, exactSha256)) {
                shapeHitsAcrossExactMisses.incrementAndGet();
                bundles.invalidate(key);
            } else {
                recordHit(fast, exactSha256);
                return new LookupResult(true, fast);
            }
        }
        var ran = new AtomicBoolean(false);
        CopiedClassBundle b = bundles.get(key, k -> {
            ran.set(true);
            return onMiss.get();
        });
        boolean reused = !ran.get();
        if (reused) {
            if (!matchesExact(b, exactSha256)) {
                shapeHitsAcrossExactMisses.incrementAndGet();
                bundles.asMap().remove(key, b);
                b = onMiss.get();
                bundles.asMap().put(key, b);
                reused = false;
            } else {
                recordHit(b, exactSha256);
            }
        }
        return new LookupResult(reused, b);
    }

    private static boolean matchesExact(CopiedClassBundle bundle, byte[] exactSha256) {
        return bundle.exactSha256 != null
                && exactSha256 != null
                && java.security.MessageDigest.isEqual(bundle.exactSha256, exactSha256);
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
        bundles.invalidateAll();
        bundles.cleanUp();
        bytesSaved.set(0);
        instancesShared.set(0);
        shapeHitsAcrossExactMisses.set(0);
    }

    public int size() {
        return (int) bundles.estimatedSize();
    }

    public List<CopiedClassBundle> snapshotBundles() {
        return new ArrayList<>(bundles.asMap().values());
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

        for (var entry : bundles.asMap().entrySet()) {
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

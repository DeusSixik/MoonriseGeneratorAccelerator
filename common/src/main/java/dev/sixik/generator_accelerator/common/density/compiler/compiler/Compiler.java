package dev.sixik.generator_accelerator.common.density.compiler.compiler;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.config.GAConfigHolder;
import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCompiledClassRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.backend.BytecodeCpuBackend;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.backend.DfcBackend;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.backend.DfcBackendResult;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.CompilationFingerprint;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.GlobalCompileCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.*;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuEligibility;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadParity;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadRuntimeRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.*;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.CompilingVisitor;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RouterPipeline;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.plan.CompilationPlan;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.plan.SplineSearchStats;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public facade for the JIT compiler. {@link #compile(DensityFunction)} takes any
 * {@link DensityFunction} and returns a {@link CompiledDensityFunction} that's
 * behaviourally identical (within the usual {@code ULP} bounds for floating-point
 * arithmetic), or, if compilation fails for any reason, the original function.
 *
 * <p>The compiler is intentionally fail-soft: a single broken DensityFunction (e.g. an
 * unrecognised mod-provided node we can't currently inline) must never break worldgen.
 * On failure we log a warning and fall back to the original instance, which the visitor
 * cache still memoises as the "compiled" answer so we don't keep retrying.
 */
public final class Compiler {
    private static final boolean LOG_SPLINE_SEARCH =
            GAConfigHolder.getConfig().dfc.logSplineSearch;
    private static final AtomicInteger SPLINE_LOGGED_ROOTS = new AtomicInteger();
    private static final AtomicInteger SPLINE_LOGGED_MULTIPOINTS = new AtomicInteger();
    private static final AtomicInteger SPLINE_LOGGED_BINARY_USED = new AtomicInteger();
    private static final AtomicInteger SPLINE_LOGGED_AUTO_ELIGIBLE = new AtomicInteger();
    private static final AtomicInteger SPLINE_LOGGED_MAX_POINTS = new AtomicInteger();
    private static final AtomicInteger SPLINE_LOGGED_BUCKET_LE_2 = new AtomicInteger();
    private static final AtomicInteger SPLINE_LOGGED_BUCKET_3_TO_4 = new AtomicInteger();
    private static final AtomicInteger SPLINE_LOGGED_BUCKET_5_TO_8 = new AtomicInteger();
    private static final AtomicInteger SPLINE_LOGGED_BUCKET_GE_9 = new AtomicInteger();
    private static final DfcBackend CPU_BACKEND = BytecodeCpuBackend.INSTANCE;

    private Compiler() {}

    /** Compile {@code df}, descending recursively into its children via {@link CompilingVisitor}. */
    public static DensityFunction compile(DensityFunction df) {
        Result r = compileWithDetail(df);
        return r == null ? df : r.compiled();
    }

    /**
     * Variant exposing the intermediate compilation state, used by the {@code /dfc dump}
     * command to print IR / bytecode without recompiling. Returns {@code null} on failure
     * (caller should fall back to the original DensityFunction).
     */
    public static Result compileWithDetail(DensityFunction df) {
        try {
            CompilationPlan plan = prepareCompilationPlan(df);
            DfcBackendResult backendResult = CPU_BACKEND.compile(plan);
            return linkAndRecord(
                    backendResult.bundle(), backendResult.reusedClassFromCache(),
                    plan.root(), plan.refs(), plan.pool(), plan.extracted(),
                    plan.minValue(), plan.maxValue(), plan.uniqueNodes(), plan.cseSavings(),
                    plan.optimizerRewrites(), plan.noisesSpecialized(), plan.octavesUnrolled(),
                    plan.splineStats(), plan.gpuEligibility(), plan.gpuPayload());
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.warn(
                    "Compilation failed for {} ({}): {} - falling back to vanilla evaluator",
                    df.getClass().getSimpleName(),
                    System.identityHashCode(df),
                    t.toString(), t);
            return null;
        }
    }

    private static CompilationPlan prepareCompilationPlan(DensityFunction df) {
        ConstantPool pool = new ConstantPool();
        IRBuilder builder = new IRBuilder(pool, CompilingVisitor.global());
        IRNode root = builder.build(df);

        // Peephole pass: constant folding, algebraic identities, RangeChoice
        // short-circuiting, cost-aware strength reduction. Runs before Bounds /
        // RefCount / Splitter so downstream stages see the post-rewrite DAG.
        // Every rewritten node is re-interned through the same IRBuilder, so
        // hash-consing / CSE stay consistent.
        IROptimizer.Result optResult = IROptimizer.optimize(root, builder, pool);
        root = optResult.root();
        int optimizerRewrites = optResult.rewrites();

        // Tier 3 noise inlining pass. Rewrites every Noise / ShiftedNoise /
        // ShiftA / ShiftB / Shift / WeirdScaled into InlinedNoise / WeirdRarity
        // form so the codegen can unroll their per-octave loops with baked-in
        // amplitudes / input factors (see NoiseExpander javadoc). The expander
        // exposes coordinate sub-trees as first-class IR, so we re-run the
        // optimizer to fold the newly visible (x*scale + shift)*INPUT_FACTOR
        // chains and to CSE shared coordinates.
        NoiseExpander.Result noiseResult = NoiseExpander.expand(root, builder, pool);
        root = noiseResult.root();
        int noisesSpecialized = noiseResult.noisesSpecialized();
        int octavesUnrolled = noiseResult.octavesUnrolled();
        if (noisesSpecialized > 0) {
            IROptimizer.Result postNoise = IROptimizer.optimize(root, builder, pool);
            root = postNoise.root();
            optimizerRewrites += postNoise.rewrites();
        }

        SplineSearchStats splineStats = LOG_SPLINE_SEARCH ? collectSplineSearchStats(root) : null;
        int uniqueNodes = builder.internedCount();
        int cseSavings = builder.cseSavings();
        RefCount.Result rc = RefCount.compute(root);

        double minVal;
        double maxVal;
        try {
            // One interval walk: min+max would each call interval() with a fresh memo.
            double[] iv = Bounds.interval(root, pool);
            minVal = iv[0];
            maxVal = iv[1];
        } catch (RuntimeException bx) {
            minVal = df.minValue();
            maxVal = df.maxValue();
        }

        Set<IRNode> extracted = Splitter.plan(root, rc, pool);
        byte[] exactFp = CompilationFingerprint.sha256(root, pool, minVal, maxVal);
        // Use the exact, identity-bearing fingerprint for hidden-class reuse. The
        // broader shape fingerprint can share bytecode across worlds, but it is only
        // safe if every constructor payload slot stays layout-compatible. A mismatch
        // there presents as generated bytecode reading e.g. constants[0] from a fresh
        // instance whose payload has constants.length == 0.
        byte[] cacheFp = exactFp;
        // Use _ rather than $: hidden class bytecode with `$` in its own name
        // confuses NeoForge's ModuleClassLoader (see previous comments).
        String className = "dev/sixik/generator_accelerator/common/density/compiler/compiler/codegen/CompiledDF_"
                + CompilationFingerprint.stableClassSuffix(cacheFp);
        GpuEligibility.Report gpuEligibility = GpuEligibility.analyze(root, pool);
        GpuPayloadCompiler.Result gpuPayload = GpuPayloadCompiler.compile(root, pool);

        return new CompilationPlan(
                df.getClass().getName(), root, rc, pool, extracted, minVal, maxVal, uniqueNodes, cseSavings,
                optimizerRewrites, noisesSpecialized, octavesUnrolled, splineStats,
                exactFp, cacheFp, className,
                describeRootForCellFillDebug(root), describeDominantSpline(root, pool), gpuEligibility, gpuPayload);
    }

    public static DumpResult dumpCompiledClasses() {
        Path base = GeneratorAccelerator.getGameFolder();
        if (base == null) {
            base = Paths.get(".");
        }
        Path dumpRoot = base
                .resolve(".densitycompiler")
                .toAbsolutePath()
                .normalize();
        int dumped = 0;
        int skipped = 0;
        int failed = 0;
        Set<String> seen = new HashSet<>();
        List<GlobalCompileCache.CopiedClassBundle> bundles = GlobalCompileCache.INSTANCE.snapshotBundles();
        try {
            Files.createDirectories(dumpRoot);
        } catch (Exception e) {
            DensityFunctionCompiler.LOGGER.warn("DFC: failed to create class dump directory {}", dumpRoot, e);
            return new DumpResult(dumpRoot, 0, bundles.size(), bundles.size());
        }

        for (GlobalCompileCache.CopiedClassBundle bundle : bundles) {
            String classInternalName = bundle.classInternalName();
            byte[] bytecode = bundle.bytecode();
            if (classInternalName == null || bytecode == null || !seen.add(classInternalName)) {
                skipped++;
                continue;
            }
            Path classFile = dumpRoot
                    .resolve(classInternalName + ".class")
                    .normalize();
            if (!classFile.startsWith(dumpRoot)) {
                failed++;
                DensityFunctionCompiler.LOGGER.warn(
                        "DFC: refused to dump generated class with suspicious name {}",
                        classInternalName);
                continue;
            }

            try {
                Files.createDirectories(classFile.getParent());
                Files.write(classFile, bytecode,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                dumped++;
            } catch (Exception e) {
                failed++;
                DensityFunctionCompiler.LOGGER.warn(
                        "DFC: failed to dump generated class {} to {}",
                        classInternalName, classFile, e);
            }
        }
        DensityFunctionCompiler.LOGGER.info(
                "DFC: dumped {} generated classes to {} ({} skipped, {} failed)",
                dumped, dumpRoot, skipped, failed);
        return new DumpResult(dumpRoot, dumped, skipped, failed);
    }

    private static Result linkAndRecord(
            GlobalCompileCache.CopiedClassBundle bundle,
            boolean reusedClassFromCache,
            IRNode root,
            RefCount.Result rc,
            ConstantPool pool,
            Set<IRNode> extracted,
            double minVal,
            double maxVal,
            int uniqueNodes,
            int cseSavings,
            int optimizerRewrites,
            int noisesSpecialized,
            int octavesUnrolled,
            SplineSearchStats splineStats,
            GpuEligibility.Report gpuEligibility,
            GpuPayloadCompiler.Result gpuPayload) {
        MethodHandle ctorMH = bundle.constructorHandle();
        MethodHandle[] helperHandles = bundle.helperHandles();
        CompiledDensityFunction compiled;
        try {
            compiled = (CompiledDensityFunction) ctorMH.invokeExact(
                    pool.finishConstants(),
                    pool.finishNoises(),
                    pool.finishSplines(),
                    pool.finishNoiseOctaves(),
                    pool.finishExterns(),
                    minVal, maxVal,
                    helperHandles, ctorMH);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to instantiate " + bundle.classInternalName(), t);
        }
        DfcCompiledClassRegistry.record(
                bundle.classInternalName(),
                bundle.sourceRootClass(),
                bundle.latticeEmitted(),
                bundle.cellAddLatticeSpecialized(),
                bundle.cellAddExternSpecialized(),
                bundle.rootDebug(),
                bundle.splineDebug());
        if (reusedClassFromCache) {
            RouterPipeline.recordRootFromGlobalClassCache(uniqueNodes, cseSavings);
        } else {
            RouterPipeline.recordCompiledRoot(uniqueNodes, cseSavings);
            RouterPipeline.recordHelpers(bundle.helpersEmitted());
            RouterPipeline.recordGlobalCacheCodegenMiss();
        }
        RouterPipeline.recordLatticePlan(bundle.latticeEmitted());
        RouterPipeline.recordOptimizerRewrites(optimizerRewrites);
        RouterPipeline.recordNoiseInline(noisesSpecialized, octavesUnrolled);
        RouterPipeline.recordBlendedInline(pool.blendedNoiseSpecCount(), countBlendedNonNullOctaves(pool));
        RouterPipeline.recordGpuEligibility(gpuEligibility);
        RouterPipeline.recordGpuPayload(gpuPayload);
        GpuPayloadParity.Report gpuPayloadParity = GpuPayloadParity.check(compiled, gpuPayload);
        RouterPipeline.recordGpuPayloadParity(gpuPayloadParity);
        GpuPayloadRuntimeRegistry.register(
                compiled, gpuEligibility, gpuPayload,
                describeFirstGpuPayloadUnsupported(root, pool, gpuPayload));
        logSplineSearchIfInteresting(root, bundle, reusedClassFromCache, splineStats);

        return new Result(
                compiled, root, rc, pool, bundle.bytecode(), bundle.classInternalName(),
                uniqueNodes, cseSavings, bundle.helpersEmitted(), optimizerRewrites,
                noisesSpecialized, octavesUnrolled, minVal, maxVal, gpuEligibility, gpuPayload, gpuPayloadParity);
    }

    private static long countBlendedNonNullOctaves(ConstantPool pool) {
        long t = 0;
        for (int i = 0; i < pool.blendedNoiseSpecCount(); i++) {
            BlendedNoiseSpec s = pool.blendedNoiseSpec(i);
            for (var x : s.mainOctaves()) {
                if (x != null) t++;
            }
            for (var x : s.minLimitOctaves()) {
                if (x != null) t++;
            }
            for (var x : s.maxLimitOctaves()) {
                if (x != null) t++;
            }
        }
        return t;
    }

    private static SplineSearchStats collectSplineSearchStats(IRNode root) {
        IdentityHashMap<IRNode, Boolean> seen = new IdentityHashMap<>();
        Deque<IRNode> stack = new ArrayDeque<>();
        stack.push(root);
        int multipoints = 0;
        int binaryUsed = 0;
        int autoEligible = 0;
        int maxPoints = 0;
        int bucketLe2 = 0;
        int bucket3To4 = 0;
        int bucket5To8 = 0;
        int bucketGe9 = 0;
        while (!stack.isEmpty()) {
            IRNode node = stack.pop();
            if (seen.put(node, Boolean.TRUE) != null) {
                continue;
            }
            if (node instanceof IRNode.Spline.Multipoint mp) {
                multipoints++;
                int points = mp.locations().length;
                maxPoints = Math.max(maxPoints, points);
                if (Codegen.useBinarySplineSearch(points)) {
                    binaryUsed++;
                }
                if (points > Codegen.SPLINE_LINEAR_SEARCH_MAX_POINTS) {
                    autoEligible++;
                }
                if (points <= 2) {
                    bucketLe2++;
                } else if (points <= 4) {
                    bucket3To4++;
                } else if (points <= 8) {
                    bucket5To8++;
                } else {
                    bucketGe9++;
                }
            }
            for (IRNode child : RefCount.children(node)) {
                stack.push(child);
            }
        }
        return new SplineSearchStats(
                multipoints, binaryUsed, autoEligible, maxPoints,
                bucketLe2, bucket3To4, bucket5To8, bucketGe9);
    }

    private static void logSplineSearchIfInteresting(IRNode root,
                                                     GlobalCompileCache.CopiedClassBundle bundle,
                                                     boolean reusedClassFromCache,
                                                     SplineSearchStats stats) {
        if (!LOG_SPLINE_SEARCH || stats == null || stats.multipoints() == 0) {
            return;
        }
        int sessionRoots = SPLINE_LOGGED_ROOTS.incrementAndGet();
        int sessionMultipoints = SPLINE_LOGGED_MULTIPOINTS.addAndGet(stats.multipoints());
        int sessionBinaryUsed = SPLINE_LOGGED_BINARY_USED.addAndGet(stats.binaryUsed());
        int sessionAutoEligible = SPLINE_LOGGED_AUTO_ELIGIBLE.addAndGet(stats.autoEligible());
        int sessionMaxPoints = SPLINE_LOGGED_MAX_POINTS.accumulateAndGet(stats.maxPoints(), Math::max);
        int sessionBucketLe2 = SPLINE_LOGGED_BUCKET_LE_2.addAndGet(stats.bucketLe2());
        int sessionBucket3To4 = SPLINE_LOGGED_BUCKET_3_TO_4.addAndGet(stats.bucket3To4());
        int sessionBucket5To8 = SPLINE_LOGGED_BUCKET_5_TO_8.addAndGet(stats.bucket5To8());
        int sessionBucketGe9 = SPLINE_LOGGED_BUCKET_GE_9.addAndGet(stats.bucketGe9());
        DensityFunctionCompiler.LOGGER.info(
                "DFC spline search: mode={}, linearThreshold={}, root={}, helpers={}, reusedClass={}, "
                        + "multipoints={}, binaryUsed={}, autoEligible={}, maxPoints={}, "
                        + "buckets=[<=2:{},3..4:{},5..8:{},>=9:{}], sessionRoots={}, "
                        + "sessionMultipoints={}, sessionBinaryUsed={}, sessionAutoEligible={}, "
                        + "sessionMaxPoints={}, sessionBuckets=[<=2:{},3..4:{},5..8:{},>=9:{}]",
                Codegen.splineSearchModeName(),
                Codegen.SPLINE_LINEAR_SEARCH_MAX_POINTS,
                root.getClass().getSimpleName(),
                bundle.helpersEmitted(),
                reusedClassFromCache,
                stats.multipoints(),
                stats.binaryUsed(),
                stats.autoEligible(),
                stats.maxPoints(),
                stats.bucketLe2(),
                stats.bucket3To4(),
                stats.bucket5To8(),
                stats.bucketGe9(),
                sessionRoots,
                sessionMultipoints,
                sessionBinaryUsed,
                sessionAutoEligible,
                sessionMaxPoints,
                sessionBucketLe2,
                sessionBucket3To4,
                sessionBucket5To8,
                sessionBucketGe9);
    }

    private static String describeRootForCellFillDebug(IRNode root) {
        if (root instanceof IRNode.Bin bin) {
            String leftType = bin.left().getClass().getSimpleName();
            String rightType = bin.right().getClass().getSimpleName();
            var leftPlan = CellLatticeOption.analyze(bin.left()).orElse(null);
            var rightPlan = CellLatticeOption.analyze(bin.right()).orElse(null);
            return "bin=" + bin.op()
                    + ",left=" + leftType
                    + ",right=" + rightType
                    + ",leftPlan=" + (leftPlan != null ? leftPlan.hoistAxis() + ":" + leftPlan.hoistedNodeCount() : "none")
                    + ",rightPlan=" + (rightPlan != null ? rightPlan.hoistAxis() + ":" + rightPlan.hoistedNodeCount() : "none");
        }
        return root.getClass().getSimpleName();
    }

    private static String describeDominantSpline(IRNode root, ConstantPool pool) {
        IdentityHashMap<IRNode, Boolean> seen = new IdentityHashMap<>();
        Deque<IRNode> stack = new ArrayDeque<>();
        stack.push(root);
        IRNode.Spline.Multipoint best = null;
        while (!stack.isEmpty()) {
            IRNode node = stack.pop();
            if (seen.put(node, Boolean.TRUE) != null) {
                continue;
            }
            if (node instanceof IRNode.Spline.Multipoint mp) {
                if (best == null || mp.locations().length > best.locations().length) {
                    best = mp;
                }
            }
            for (IRNode child : RefCount.children(node)) {
                stack.push(child);
            }
        }
        if (best == null) {
            return "none";
        }
        float[] locs = best.locations();
        float[] derivs = best.derivatives();
        int n = locs.length;
        return "points=" + n
                + ",coord=" + describeSplineCoordinate(best.coordinate(), pool, 2)
                + ",loc0=" + locs[0]
                + ",loc1=" + (n > 1 ? locs[1] : locs[0])
                + ",locLast=" + locs[n - 1]
                + ",d0=" + derivs[0]
                + ",dLast=" + derivs[n - 1]
                + ",v0=" + best.values().get(0).getClass().getSimpleName()
                + ",vLast=" + best.values().get(n - 1).getClass().getSimpleName();
    }

    private static String describeSplineCoordinate(IRNode node, ConstantPool pool, int depth) {
        if (node == null) {
            return "null";
        }
        if (depth <= 0) {
            return node.getClass().getSimpleName();
        }
        return switch (node) {
            case IRNode.Const c -> "Const(" + c.value() + ")";
            case IRNode.BlockX ignored -> "BlockX";
            case IRNode.BlockY ignored -> "BlockY";
            case IRNode.BlockZ ignored -> "BlockZ";
            case IRNode.Bin bin -> "Bin(" + bin.op() + ","
                    + describeSplineCoordinate(bin.left(), pool, depth - 1) + ","
                    + describeSplineCoordinate(bin.right(), pool, depth - 1) + ")";
            case IRNode.Unary unary -> "Unary(" + unary.op() + ","
                    + describeSplineCoordinate(unary.input(), pool, depth - 1) + ")";
            case IRNode.Clamp clamp -> "Clamp(" + describeSplineCoordinate(clamp.input(), pool, depth - 1) + ")";
            case IRNode.RangeChoice rc -> "RangeChoice(" + describeSplineCoordinate(rc.input(), pool, depth - 1) + ")";
            case IRNode.InlinedNoise in -> describeInlinedNoiseCoordinate(in, pool, depth - 1);
            case IRNode.InlinedBlendedNoise ignored -> "InlinedBlendedNoise";
            case IRNode.Noise ignored -> "Noise";
            case IRNode.ShiftedNoise ignored -> "ShiftedNoise";
            case IRNode.ShiftA ignored -> "ShiftA";
            case IRNode.ShiftB ignored -> "ShiftB";
            case IRNode.Shift ignored -> "Shift";
            case IRNode.Marker marker -> describeMarkerCoordinate(marker, pool);
            case IRNode.Invoke ignored -> "Invoke";
            case IRNode.Spline.Constant ignored -> "SplineConst";
            case IRNode.Spline.Multipoint ignored -> "SplineMultipoint";
            default -> node.getClass().getSimpleName();
        };
    }

    private static String describeInlinedNoiseCoordinate(IRNode.InlinedNoise in, ConstantPool pool, int depth) {
        int coordDepth = Math.max(depth, 3);
        String coords = "x=" + describeSplineCoordinate(in.coordX(), pool, coordDepth)
                + ",y=" + describeSplineCoordinate(in.coordY(), pool, coordDepth)
                + ",z=" + describeSplineCoordinate(in.coordZ(), pool, coordDepth);
        if (pool == null || in.specPoolIndex() < 0 || in.specPoolIndex() >= pool.noiseSpecCount()) {
            return "InlinedNoise(" + coords + ")";
        }
        NoiseSpec spec = pool.noiseSpec(in.specPoolIndex());
        return "InlinedNoise(octaves=" + spec.totalActiveOctaves()
                + ",valueFactor=" + spec.valueFactor()
                + ",secondScale=" + spec.second().inputCoordScale()
                + "," + coords + ")";
    }

    private static String describeMarkerCoordinate(IRNode.Marker marker, ConstantPool pool) {
        if (pool == null || marker.externIndex() < 0 || marker.externIndex() >= pool.externCount()) {
            return "Marker";
        }
        DensityFunction extern = pool.extern(marker.externIndex());
        if (extern == null) {
            return "Marker(null)";
        }
        String type = extern.getClass().getSimpleName();
        if (extern instanceof net.minecraft.world.level.levelgen.DensityFunctions.MarkerOrMarked mm) {
            return "Marker(" + mm.type() + "," + type + ")";
        }
        return "Marker(" + type + ")";
    }

    private static String describeFirstGpuPayloadUnsupported(
            IRNode root,
            ConstantPool pool,
            GpuPayloadCompiler.Result gpuPayload) {
        if (gpuPayload == null || gpuPayload.supported()) {
            return "none";
        }
        String first = gpuPayload.firstUnsupportedNode();
        String detail = gpuPayload.firstUnsupportedDetail();
        if (detail != null && !detail.isBlank() && !"none".equals(detail) && !detail.equals(first)) {
            return detail;
        }
        if (first == null || first.isBlank() || root == null) {
            return first == null || first.isBlank() ? "unknown" : first;
        }
        IRNode node = findFirstUnsupportedNode(root, first, new IdentityHashMap<>());
        if (node == null) {
            return first;
        }
        return switch (node) {
            case IRNode.Invoke invoke -> "Invoke:" + describeExternClass(pool, invoke.externIndex());
            case IRNode.Marker marker -> "Marker:" + describeExternClass(pool, marker.externIndex());
            case IRNode.Beardifier beardifier -> "Beardifier:" + describeExternClass(pool, beardifier.externIndex());
            case IRNode.EndIslands endIslands -> "EndIslands:" + describeExternClass(pool, endIslands.externIndex());
            case IRNode.InlinedNoise noise -> "InlinedNoise:spec=" + noise.specPoolIndex();
            case IRNode.InlinedBlendedNoise noise -> "InlinedBlendedNoise:spec=" + noise.blendedSpecIndex();
            case IRNode.Spline.Multipoint spline -> "Spline.Multipoint:points=" + spline.locations().length;
            case IRNode.Noise noise -> "Noise:index=" + noise.noiseIndex();
            case IRNode.ShiftedNoise noise -> "ShiftedNoise:index=" + noise.noiseIndex();
            case IRNode.ShiftA noise -> "ShiftA:index=" + noise.noiseIndex();
            case IRNode.ShiftB noise -> "ShiftB:index=" + noise.noiseIndex();
            case IRNode.Shift noise -> "Shift:index=" + noise.noiseIndex();
            case IRNode.WeirdScaled noise -> "WeirdScaled:index=" + noise.noiseIndex();
            default -> node.getClass().getSimpleName();
        };
    }

    private static IRNode findFirstUnsupportedNode(
            IRNode node,
            String unsupportedName,
            IdentityHashMap<IRNode, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null) {
            return null;
        }
        if (unsupportedName.equals(node.getClass().getSimpleName())) {
            return node;
        }
        for (IRNode child : RefCount.children(node)) {
            IRNode found = findFirstUnsupportedNode(child, unsupportedName, seen);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String describeExternClass(ConstantPool pool, int externIndex) {
        if (pool == null || externIndex < 0 || externIndex >= pool.externCount()) {
            return "extern#" + externIndex;
        }
        DensityFunction extern = pool.extern(externIndex);
        if (extern == null) {
            return "extern#" + externIndex + ":null";
        }
        String className = extern.getClass().getName();
        if (extern instanceof net.minecraft.world.level.levelgen.DensityFunctions.MarkerOrMarked marker) {
            className += ":" + marker.type();
        }
        return className;
    }

    /** Diagnostic snapshot of one compile() call. */
    public record Result(
            CompiledDensityFunction compiled,
            IRNode root,
            RefCount.Result refs,
            ConstantPool pool,
            byte[] bytecode,
            String classInternalName,
            int uniqueNodes,
            int cseSavings,
            int helpersEmitted,
            int optimizerRewrites,
            int noisesSpecialized,
            int octavesUnrolled,
            double minValue,
            double maxValue,
            GpuEligibility.Report gpuEligibility,
            GpuPayloadCompiler.Result gpuPayload,
            GpuPayloadParity.Report gpuPayloadParity) {}

    public record DumpResult(Path directory, int classesDumped, int skipped, int failed) {}
}

package dev.sixik.generator_accelerator.common.density.compiler.compiler;

import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.CompilationFingerprint;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.GlobalCompileCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.Codegen;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.HiddenClassLoader;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.Splitter;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.Bounds;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRBuilder;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IROptimizer;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.NoiseExpander;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.RefCount;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.CompilingVisitor;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RouterPipeline;
import dev.sixik.generator_accelerator.common.density.compiler.natives.NativeNoiseRegistry;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Public facade for the JIT compiler. {@link #compile(DensityFunction)} takes any
 * {@link DensityFunction} and returns a {@link CompiledDensityFunction} that's
 * behaviourally identical (within the usual {@code ULP} bounds for floating-point
 * arithmetic), or — if compilation fails for any reason — the original function.
 *
 * <p>The compiler is intentionally fail-soft: a single broken DensityFunction (e.g. an
 * unrecognised mod-provided node we can't currently inline) must never break worldgen.
 * On failure we log a warning and fall back to the original instance, which the visitor
 * cache still memoises as the "compiled" answer so we don't keep retrying.
 */
public final class Compiler {

    private Compiler() {}

    /** Compile {@code df}, descending recursively into its children via {@link CompilingVisitor}. */
    public static DensityFunction compile(DensityFunction df) {
        Result r = compileWithDetail(df);
        return r == null ? df : r.compiled();
    }

    /**
     * Variant exposing the intermediate compilation state — used by the {@code /dfc dump}
     * command to print IR / bytecode without recompiling. Returns {@code null} on failure
     * (caller should fall back to the original DensityFunction).
     */
    public static Result compileWithDetail(DensityFunction df) {
        try {
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

            // Tier 3 — noise inlining pass. Rewrites every Noise / ShiftedNoise /
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
            byte[] shapeFp = CompilationFingerprint.shapeSha256(root, pool, minVal, maxVal);
            // Use _ rather than $: hidden class bytecode with `$` in its own name
            // confuses NeoForge's ModuleClassLoader (see previous comments).
            String className = "dev/sixik/generator_accelerator/common/density/compiler/compiler/codegen/CompiledDF_"
                    + CompilationFingerprint.stableClassSuffix(shapeFp);

            // Lambdas can only close over effectively final locals — IR bounds/root must be
            // bound after the last reassignment of `root` / min / max.
            final IRNode irRoot = root;
            final RefCount.Result fr = rc;
            final Set<IRNode> fExtracted = extracted;
            final ConstantPool fPool = pool;
            final double fMin = minVal;
            final double fMax = maxVal;
            final String fClassName = className;

            GlobalCompileCache g = GlobalCompileCache.INSTANCE;
            GlobalCompileCache.LookupResult lo = g.getOrCompile(shapeFp, exactFp, () -> {
                Codegen.Result emitResult = Codegen.emit(fClassName, irRoot, fr, fExtracted, fPool, fMin, fMax);
                byte[] bytecode = emitResult.bytecode();
                int helpersEmitted = emitResult.helpersEmitted();
                boolean latticeEmitted = emitResult.latticeEmitted();

                HiddenClassLoader.DefineResult dr = HiddenClassLoader.defineWithLookup(bytecode);
                Class<? extends CompiledDensityFunction> cls = dr.cls();
                MethodHandles.Lookup lookup = dr.lookup();

                MethodHandle[] helperHandles;
                try {
                    helperHandles = new MethodHandle[helpersEmitted];
                    MethodType helperType = MethodType.methodType(
                            double.class,
                            CompiledDensityFunction.class,
                            DensityFunction.FunctionContext.class);
                    for (int i = 0; i < helpersEmitted; i++) {
                        helperHandles[i] = lookup.findStatic(cls, Codegen.helperName(i), helperType);
                    }
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new RuntimeException("Failed to resolve helper MethodHandles for "
                            + fClassName + " (" + helpersEmitted + " helpers expected)", e);
                }

                MethodHandle ctorMH;
                try {
                    MethodType ctorType = MethodType.methodType(void.class,
                            double[].class, NormalNoise[].class, Object[].class, Object[].class,
                            DensityFunction[].class,
                            double.class, double.class,
                            MethodHandle[].class, long[].class,
                            byte[].class, double[].class,
                            MethodHandle.class);
                    ctorMH = lookup.findConstructor(cls, ctorType)
                            .asType(MethodType.methodType(CompiledDensityFunction.class,
                                    double[].class, NormalNoise[].class, Object[].class, Object[].class,
                                    DensityFunction[].class,
                                    double.class, double.class,
                                    MethodHandle[].class, long[].class,
                                    byte[].class, double[].class,
                                    MethodHandle.class));
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new RuntimeException("Failed to resolve constructor MethodHandle for "
                            + fClassName, e);
                }

                return new GlobalCompileCache.CopiedClassBundle(
                        fClassName, exactFp, cls, bytecode, ctorMH, helperHandles, helpersEmitted, latticeEmitted,
                        emitResult.slabInnerProgram(), emitResult.slabInnerConsts());
            });
            return linkAndRecord(lo.bundle(), lo.reused(), root, rc, pool, extracted, minVal, maxVal, uniqueNodes,
                    cseSavings, optimizerRewrites, noisesSpecialized, octavesUnrolled);
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.warn(
                    "Compilation failed for {} ({}): {} — falling back to vanilla evaluator",
                    df.getClass().getSimpleName(),
                    System.identityHashCode(df),
                    t.toString(), t);
            return null;
        }
    }

    public static DumpResult dumpCompiledClasses() {
        Path dumpRoot = Paths.get(System.getProperty("user.dir", "."))
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
            int octavesUnrolled) {
        MethodHandle ctorMH = bundle.constructorHandle();
        MethodHandle[] helperHandles = bundle.helperHandles();
        long[] nativeHandles = NativeNoiseRegistry.buildHandles(pool.noiseSpecs(), pool.blendedNoiseSpecsList());
        byte[] slabBc = bundle.slabNativeProgram();
        double[] slabC = bundle.slabNativeConstants();
        CompiledDensityFunction compiled;
        try {
            compiled = (CompiledDensityFunction) ctorMH.invokeExact(
                    pool.finishConstants(),
                    pool.finishNoises(),
                    pool.finishSplines(),
                    pool.finishNoiseOctaves(),
                    pool.finishExterns(),
                    minVal, maxVal,
                    helperHandles, nativeHandles,
                    slabBc, slabC, ctorMH);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to instantiate " + bundle.classInternalName(), t);
        }
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

        return new Result(
                compiled, root, rc, pool, bundle.bytecode(), bundle.classInternalName(),
                uniqueNodes, cseSavings, bundle.helpersEmitted(), optimizerRewrites,
                noisesSpecialized, octavesUnrolled, minVal, maxVal);
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
            double maxValue) {}

    public record DumpResult(Path directory, int classesDumped, int skipped, int failed) {}
}

package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import dev.sixik.generator_accelerator.api.config.GAConfigHolder;
import dev.sixik.generator_accelerator.common.density.compiler.cache.*;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.*;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.vector.DfcVectorSupport;
import dev.sixik.generator_accelerator.api.patches.GA$NoiseChunk$InterpolatorSoAPath;
import dev.sixik.generator_accelerator.api.patches.GA$NoiseChunk$NoiseInterpolatorPatch;
import dev.sixik.generator_accelerator.common.noise.utils.DfcNoiseChunkSliceAccess;
import org.objectweb.asm.*;

import java.util.*;

/**
 * ASM emitter for the IR.
 *
 * <p>Layout of the generated class:
 * <pre>
 * public final class CompiledDF_N extends CompiledDensityFunction {
 *     public CompiledDF_N(double[] c, NormalNoise[] n, Object[] s, Object[] noiseOctaves,
 *                        DensityFunction[] e, double mn, double mx, MethodHandle[] hh,
 *                        MethodHandle ctorMH) {
 *         super(c, n, s, noiseOctaves, e, mn, mx, hh, ctorMH);
 *     }
 *     public double compute(FunctionContext ctx) { ... straight-line bytecode ... }
 *     private static double helper_0(CompiledDensityFunction self, FunctionContext ctx) { ... }
 *     private static double helper_1(CompiledDensityFunction self, FunctionContext ctx) { ... }
 *     ...
 * }
 * </pre>
 *
 * <p>Note the absence of any {@code rebind} override or any opcode mentioning the
 * generated class's own internal name (i.e. no {@code NEW CompiledDF_N},
 * {@code INVOKESTATIC CompiledDF_N.helper_K}, etc.). Hidden classes are forbidden
 * from referring to themselves symbolically — the JVM rejects {@code defineHiddenClass}
 * with {@code NoClassDefFoundError} when the constant pool contains a CONSTANT_Class_info
 * matching the class's own name. We work around this by:
 * <ul>
 *   <li>Helper methods using {@code CompiledDensityFunction} (the supertype) for their
 *       {@code self} parameter rather than the hidden subclass.</li>
 *   <li>Helper call sites loading a {@link java.lang.invoke.MethodHandle} from the
 *       inherited {@code helperHandles[]} field and using {@code INVOKEVIRTUAL
 *       MethodHandle.invokeExact} — signature-polymorphic so the verifier doesn't
 *       enforce arg types, and the descriptor names only the supertype.</li>
 *   <li>The MH array being populated by {@link Compiler} after {@code defineHiddenClass}
 *       returns: the post-define {@link java.lang.invoke.MethodHandles.Lookup} can
 *       resolve {@code helper_N} on the new class without any symbolic name reference.</li>
 *   <li>Skipping the {@code rebind} override entirely (a hidden class cannot emit
 *       {@code NEW SelfClass}); the supertype's {@code rebind} instead routes
 *       through a {@link java.lang.invoke.MethodHandle} bound to the subclass
 *       constructor (passed in via the trailing {@code MethodHandle} ctor arg),
 *       which lets visitor-driven extern remaps reach inner Markers — critical
 *       for the {@code NoiseChunk} cell-cache wraps that vanilla worldgen
 *       depends on for both correctness and performance.</li>
 * </ul>
 *
 * <p>The {@code compute} method follows a stack-scheduled emission discipline:
 * <ul>
 *   <li>Block coordinates are loaded into local slots once at method entry: blockX -&gt; slot 2,
 *       blockY -&gt; slot 3, blockZ -&gt; slot 4 (each int).</li>
 *   <li>Spilled IR nodes (refcount &ge; 2) are computed once into a freshly allocated double
 *       slot and reloaded with {@code DLOAD} on subsequent uses.</li>
 *   <li>Single-use IR nodes leave their result on the operand stack — no store/reload.</li>
 *   <li>Nodes in the {@link Splitter}-supplied {@code extracted} set become standalone
 *       {@code helper_N} static methods on the same class. Call sites become a single
 *       {@code MethodHandle.invokeExact} dispatch, slashing the parent method's bytecode
 *       size and letting HotSpot inline the helpers back at runtime when they're hot.</li>
 * </ul>
 */
public final class Codegen {
    enum SplineSearchMode {
        AUTO,
        LINEAR,
        BINARY
    }

    /**
     * Hard cap on the number of helper methods per generated class. The class
     * file's method count is u2, so the absolute limit is 65535; we cap much
     * lower because a class with thousands of methods is a sign that the IR
     * is pathologically large and the JIT will hate it anyway.
     */
    public static final int MAX_HELPERS = 1024;
    /**
     * Small splines stay on the current straight-line ladder because the branch depth is
     * already tiny and the bytecode is a little simpler. Larger splines switch to an exact
     * binary-search decision tree for segment selection.
     *
     * <p>The default keeps 3-point splines on the tiny linear path, but flips 4+ point
     * splines to binary search because telemetry showed the 3..4 bucket is hot and the
     * 4-point case benefits from a balanced exact search more than from the straight-line
     * ladder.
     */
    public static final int SPLINE_LINEAR_SEARCH_MAX_POINTS =
            Math.max(2, GAConfigHolder.getConfig().dfc.splineLinearSearchMaxPoints);
    static final SplineSearchMode SPLINE_SEARCH_MODE =
            parseSplineSearchMode(GAConfigHolder.getConfig().dfc.splineSearchMode);
    public static final boolean SPLINE_RUNTIME_STATS_ENABLED =
            GAConfigHolder.getConfig().dfc.splineRuntimeStatsEmit;
    /**
     * Optional exact LUT-guided segment selection for large interior splines.
     *
     * <p>The table predicts a likely segment and a tiny runtime fix-up loop walks to the
     * true segment before evaluating the existing cubic interpolation, so output stays
     * bit-for-bit equivalent to the current implementation.
     */
    public static final boolean SPLINE_SEGMENT_LUT_ENABLED =
            GAConfigHolder.getConfig().dfc.splineSegmentLut;
    public static final int SPLINE_SEGMENT_LUT_MIN_POINTS =
            Math.max(5, GAConfigHolder.getConfig().dfc.splineSegmentLutMinPoints);
    public static final int SPLINE_SEGMENT_LUT_BUCKETS =
            Math.max(8, GAConfigHolder.getConfig().dfc.splineSegmentLutBuckets);

    private static final String COMPILED_BASE_INTERNAL =
            Type.getInternalName(CompiledDensityFunction.class);
    private static final String NORMAL_NOISE_INTERNAL = "net/minecraft/world/level/levelgen/synth/NormalNoise";
    static final String IMPROVED_NOISE_INTERNAL = "net/minecraft/world/level/levelgen/synth/ImprovedNoise";
    private static final String DENSITY_FUNCTION_INTERNAL = "net/minecraft/world/level/levelgen/DensityFunction";
    private static final String DENSITY_FUNCTION_DESC = "L" + DENSITY_FUNCTION_INTERNAL + ";";
    private static final String CACHE_FAST_PATH_INTERNAL = Type.getInternalName(DfcCacheFastPath.class);
    private static final String DFC_CELL_FILL_ACCESS_INTERNAL = Type.getInternalName(DfcCellFillAccess.class);
    private static final String DFC_CELL_FILL_STATS_INTERNAL = Type.getInternalName(DfcCellFillStats.class);
    private static final String DFC_SPLINE_STATS_INTERNAL = Type.getInternalName(DfcSplineStats.class);
    private static final String DFC_SPLINE_STATS_RECORD_DETAILED_DESC = "(Ljava/lang/String;IIIJ)V";
    private static final String DFC_SPLINE_SUPPORT_INTERNAL = Type.getInternalName(DfcSplineSupport.class);
    private static final String DFC_SPLINE_SEGMENT_LUT_INTERNAL =
            Type.getInternalName(DfcSplineSupport.SegmentLut.class);
    private static final String DFC_SPLINE_SEGMENT_LUT_DESC = "L" + DFC_SPLINE_SEGMENT_LUT_INTERNAL + ";";
    private static final String DFC_SPLINE_SELECT_SEGMENT_DESC = "(" + DFC_SPLINE_SEGMENT_LUT_DESC + "F)I";
    private static final String DFC_SPLINE_SELECT_BINARY_DESC = "([FF)I";
    private static final String DFC_NOISE_CHUNK_SLICE_ACCESS_INTERNAL =
            Type.getInternalName(DfcNoiseChunkSliceAccess.class);
    private static final String NOISE_INTERPOLATOR_PATCH_INTERNAL =
            Type.getInternalName(GA$NoiseChunk$NoiseInterpolatorPatch.class);
    private static final String INTERPOLATOR_SOA_PATH_INTERNAL =
            Type.getInternalName(GA$NoiseChunk$InterpolatorSoAPath.class);
    private static final String INTERPOLATOR_SOA_PATH_DESC = "L" + INTERPOLATOR_SOA_PATH_INTERNAL + ";";
    private static final String INTERPOLATOR_FILLING_DELTA_DESC = "(IDDD)D";
    private static final String INTERPOLATOR_NOISE_ARRAY_DESC = "()[D";
    private static final String INTERPOLATOR_UPDATE_DELTA_DESC = "(D)V";
    private static final String DFC_VECTOR_SUPPORT_INTERNAL = Type.getInternalName(DfcVectorSupport.class);
    private static final String DOUBLE_VECTOR_FROM_ARRAY_DESC =
            "(L" + DfcVectorSupport.VECTOR_SPECIES_INTERNAL + ";[DI)L" + DfcVectorSupport.DOUBLE_VECTOR_INTERNAL + ";";
    private static final String DOUBLE_VECTOR_INTO_ARRAY_DESC = "([DI)V";
    private static final String FUNCTION_CONTEXT_INTERNAL =
            "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    /** {@link DfcCacheFastPath#computeWithOptionalDirectRead}. */
    private static final String CACHE_FAST_READ_DESC =
            "(" + DENSITY_FUNCTION_DESC + "L" + FUNCTION_CONTEXT_INTERNAL + ";)D";
    private static final String DFC_CELL_CACHE_ACCESS_INTERNAL = Type.getInternalName(DfcCellCacheAccess.class);
    private static final String DFC_CELL_CACHE_ACCESS_DESC = "L" + DFC_CELL_CACHE_ACCESS_INTERNAL + ";";
    private static final String DFC_CELL_CACHE_ARRAY_INDEX_ACCESS_INTERNAL =
            Type.getInternalName(DfcCellCacheArrayIndexAccess.class);
    private static final String DFC_CELL_CACHE_ARRAY_INDEX_ACCESS_DESC =
            "L" + DFC_CELL_CACHE_ARRAY_INDEX_ACCESS_INTERNAL + ";";
    private static final String CACHE_FAST_TYPED_READ_DESC =
            "(" + DENSITY_FUNCTION_DESC + DFC_CELL_CACHE_ACCESS_DESC + "L" + FUNCTION_CONTEXT_INTERNAL + ";)D";
    private static final String CACHE_FAST_ARRAY_INDEX_READ_DESC =
            "(" + DENSITY_FUNCTION_DESC + DFC_CELL_CACHE_ARRAY_INDEX_ACCESS_DESC
                    + "L" + FUNCTION_CONTEXT_INTERNAL + ";)D";
    private static final String CONTEXT_PROVIDER_INTERNAL =
            "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider";
    private static final String METHOD_HANDLE_INTERNAL = "java/lang/invoke/MethodHandle";
    private static final String METHOD_HANDLE_ARRAY_DESC = "[Ljava/lang/invoke/MethodHandle;";
    private static final String OBJECT_ARRAY_DESC = "[Ljava/lang/Object;";
    private static final String IMPROVED_NOISE_DESC = "L" + IMPROVED_NOISE_INTERNAL + ";";
    private static final String RUNTIME_INTERNAL =
            "dev/sixik/generator_accelerator/common/density/compiler/compiler/runtime/Runtime";
    private static final String MTH_INTERNAL = "net/minecraft/util/Mth";
    private static final String NOISE5_DESC = "(DDDDD)D";

    /**
     * Constructor descriptor used both by {@link #emitConstructor} and by
     * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler}
     * reflection. The trailing {@code MethodHandle} is the bound constructor
     * itself, threaded through so {@link CompiledDensityFunction#rebind} can
     * allocate fresh instances without {@code NEW SelfClass} (which hidden
     * classes are forbidden from emitting).
     *
     * <p>The {@code Object[]} after the splines array is the per-noise per-octave
     * {@link net.minecraft.world.level.levelgen.synth.ImprovedNoise} payload —
     * see {@link CompiledDensityFunction#noiseOctaves}. The generated subclass
     * unloads it into its own typed final fields in its constructor body.
     *
     */
    public static final String CTOR_DESC =
            "([D[L" + NORMAL_NOISE_INTERNAL + ";[Ljava/lang/Object;[Ljava/lang/Object;[L"
                    + DENSITY_FUNCTION_INTERNAL + ";DD" + METHOD_HANDLE_ARRAY_DESC
                    + "L" + METHOD_HANDLE_INTERNAL + ";)V";

    /**
     * Helper static methods all share this descriptor — first arg is the supertype
     * rather than the hidden class itself so the call-site descriptor never names
     * a hidden class (the JVM rejects hidden-class self-references in the constant
     * pool).
     */
    public static final String HELPER_DESC =
            "(L" + COMPILED_BASE_INTERNAL + ";L" + FUNCTION_CONTEXT_INTERNAL + ";)D";
    private static final String WRAP_AIOOBE_DESC =
            "(Ljava/lang/ArrayIndexOutOfBoundsException;L" + FUNCTION_CONTEXT_INTERNAL
                    + ";Ljava/lang/String;)Ljava/lang/ArrayIndexOutOfBoundsException;";

    /**
     * When {@code true} (default), helper call sites are emitted as
     * {@code INVOKEDYNAMIC} bound to {@link CompiledDensityFunction#bootstrapHelper};
     * when {@code false}, the legacy {@code helperHandles[idx].invokeExact} sequence
     * is emitted instead. A legacy {@code helperHandles[i].invokeExact} path remains
     * in the emitter for the unlikely case indy linkage fails at runtime.
     */
    public static final boolean INDY_HELPERS_ENABLED = true;

    /**
     * When {@code true} (default), {@link CellLatticeOption#analyze} runs and, if it
     * finds a worthwhile axis-only hoist, the codegen emits the {@code lattice_y} /
     * {@code lattice_inner} helpers plus a {@code fillArray} override that drives the
     * NoiseChunk triple loop with the precomputed Y-slab cached once per Y position.
     * The scalar {@link CompiledDensityFunction#fillArray} fallback stays available and is
     * exercised by {@code ParitySelfTest}.
     *
     * <p>The lattice path uses {@code INVOKEDYNAMIC + ConstantCallSite} for the
     * helper dispatch unconditionally — a hidden class cannot {@code INVOKESTATIC}
     * its own static methods symbolically, and we don't want to extend the
     * {@code helperHandles[]} array's contract for this. When
     * {@link #INDY_HELPERS_ENABLED} is false the existing {@code helper_<idx>} sites
     * still go through the legacy MH dispatch; only {@code lattice_y} /
     * {@code lattice_inner} ride indy.
     */
    public static final boolean CELL_LATTICE_ENABLED = true;
    /**
     * Direct residual-extern cell-fill loops inline another extern compute into the
     * add-extern fast path. They are currently opt-in because some shapes trigger ASM
     * frame-computation failures in the generated override.
     */
    public static final boolean CELL_FILL_DIRECT_EXTERN_RESIDUAL_ENABLED =
            GAConfigHolder.getConfig().dfc.cellFillDirectExternResidual;
    /**
     * Experimental add-extern cell-fill specialization.
     *
     * <p>Left disabled by default because real worldgen runs hit ASM 9.8 frame-merge
     * failures ({@code Frame.merge} / {@code ArrayIndexOutOfBoundsException}) on some
     * generated root shapes. Re-enable only when actively iterating on this path or when a
     * future rewrite simplifies the CFG enough to make frame computation stable again.
     */
    public static final boolean CELL_FILL_ADD_EXTERN_OVERRIDE_ENABLED =
            GAConfigHolder.getConfig().dfc.cellFillAddExternOverride;
    public static final boolean CELL_FILL_ADD_BEARDIFIER_OVERRIDE_ENABLED =
            GAConfigHolder.getConfig().dfc.cellFillAddBeardifierOverride;
    /**
     * Scalar-marker cell-fill override for compact interpolator-marker roots.
     *
     * <p>The override emits a loop that passes local in-cell coordinates directly to
     * the NoiseChunk SoA interpolator path. If any marker has not rebound to a
     * NoiseInterpolator, the method falls back to the inherited compiled loop.
     */
    private static boolean cellFillScalarMarkerOverrideEnabled() {
        String override = System.getProperty("dfc.codegen.cellFillScalarMarkerOverride");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        return GAConfigHolder.getConfig().dfc.cellFillScalarMarkerOverride;
    }

    private static boolean cellFillScalarMarkerLazyRangeChoiceZEnabled() {
        String override = System.getProperty("dfc.codegen.cellFillScalarMarkerLazyRangeChoiceZ");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        return GAConfigHolder.getConfig().dfc.cellFillScalarMarkerLazyRangeChoiceZ;
    }

    private static SplineSearchMode parseSplineSearchMode(String raw) {
        if (raw == null) {
            return SplineSearchMode.AUTO;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "auto" -> SplineSearchMode.AUTO;
            case "linear" -> SplineSearchMode.LINEAR;
            case "binary" -> SplineSearchMode.BINARY;
            default -> SplineSearchMode.AUTO;
        };
    }

    public static boolean useBinarySplineSearch(int pointCount) {
        if (pointCount <= 2) {
            return false;
        }
        return switch (SPLINE_SEARCH_MODE) {
            case AUTO -> pointCount > SPLINE_LINEAR_SEARCH_MAX_POINTS;
            case LINEAR -> false;
            case BINARY -> true;
        };
    }

    public static boolean useSplineSegmentLut(int pointCount, float[] locations) {
        if (!SPLINE_SEGMENT_LUT_ENABLED || !useBinarySplineSearch(pointCount)) {
            return false;
        }
        if (pointCount < SPLINE_SEGMENT_LUT_MIN_POINTS || locations == null || locations.length < 2) {
            return false;
        }
        return locations[locations.length - 1] > locations[0];
    }

    public static String splineSearchModeName() {
        return SPLINE_SEARCH_MODE.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Internal name of {@code net.minecraft.world.level.levelgen.NoiseChunk} (vanilla). */
    static final String NOISE_CHUNK_INTERNAL = "net/minecraft/world/level/levelgen/NoiseChunk";
    /** Reference desc for a {@code NoiseChunk}. */
    static final String NOISE_CHUNK_DESC = "L" + NOISE_CHUNK_INTERNAL + ";";
    private static final String CELL_FILL_DESC = "([D" + NOISE_CHUNK_DESC + ")V";
    private static final String DFC_NOISE_CHUNK_SLICE_ACCESS_DESC =
            "L" + DFC_NOISE_CHUNK_SLICE_ACCESS_INTERNAL + ";";

    /** Method name of the cell-lattice Y-only helper. */
    public static final String LATTICE_Y_NAME = "lattice_y";
    /** Method name of the cell-lattice XZ-only precompute helper. */
    public static final String LATTICE_XZ_NAME = "lattice_xz";
    /** Method name of the cell-lattice inner helper (takes precomputed Y as 3rd arg). */
    public static final String LATTICE_INNER_NAME = "lattice_inner";
    /** Inner helper when the hoisted subtree is XZ-only (3rd arg is the precomputed xz value). */
    public static final String LATTICE_INNER_XZ_NAME = "lattice_inner_xz";
    /** {@code (CompiledDensityFunction, FunctionContext, double) -> double} */
    public static final String LATTICE_INNER_DESC =
            "(L" + COMPILED_BASE_INTERNAL + ";L" + FUNCTION_CONTEXT_INTERNAL + ";D)D";

    private static final String CELL_ADD_LATTICE_XZ_NAME = "cell_add_lattice_xz";
    private static final String CELL_ADD_LATTICE_INNER_XZ_NAME = "cell_add_lattice_inner_xz";
    private static final String CELL_ADD_RESIDUAL_NAME = "cell_add_residual";
    private static final String CELL_ADD_EXTERN_LEFT_RESIDUAL_NAME = "cell_add_extern_left_residual";
    private static final String CELL_ADD_EXTERN_RIGHT_RESIDUAL_NAME = "cell_add_extern_right_residual";
    private static final String DFC_ACCUMULATE_CELL_NAME = "dfc$accumulateCell";
    private static final String CELL_FILL_TRY_ADD_EXTERN_LEFT_NAME = "dfc$tryCellFillAddExternLeft";
    private static final String CELL_FILL_TRY_ADD_EXTERN_RIGHT_NAME = "dfc$tryCellFillAddExternRight";
    private static final String CELL_ACCUMULATE_TRY_ADD_EXTERN_LEFT_NAME = "dfc$tryCellAccumulateAddExternLeft";
    private static final String CELL_ACCUMULATE_TRY_ADD_EXTERN_RIGHT_NAME = "dfc$tryCellAccumulateAddExternRight";
    private static final String CELL_FILL_TRY_DESC = "([D" + NOISE_CHUNK_DESC + ")Z";

    /** Internal name of {@link CompiledDensityFunction}, used by the indy bsm handle. */
    private static final String BOOTSTRAP_OWNER = COMPILED_BASE_INTERNAL;
    /**
     * ASM {@link Handle} pointing at {@link CompiledDensityFunction#bootstrapHelper}.
     *
     * <p>The bsm signature is the standard 3-arg shape — Lookup, invokedName,
     * invokedType — with no extra static bsm args. The helper's identity is encoded
     * entirely in the {@code invokedName} string at the call site (e.g.
     * {@code "helper_5"}), which keeps the same bsm reusable for the cell-lattice
     * helpers ({@code "lattice_y"}, {@code "lattice_inner"}) introduced by Phase 2 —
     * those use a different {@link java.lang.invoke.MethodType} but the same lookup
     * mechanism.
     */
    static final Handle HELPER_BSM = new Handle(
            Opcodes.H_INVOKESTATIC,
            BOOTSTRAP_OWNER,
            "bootstrapHelper",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false);

    private Codegen() {}

    public static Result emit(String classInternalName, IRNode root,
                              RefCount.Result rc, Set<IRNode> extracted, ConstantPool pool,
                              double minVal, double maxVal) {

        // Hidden classes lose their declared name once defined: the JVM does NOT resolve
        // symbolic INVOKESTATIC self-references (it goes through the classloader, which
        // can't find the unnamed hidden class and throws NoClassDefFoundError). We
        // sidestep this by routing helper calls through MethodHandle.invokeExact off
        // the inherited `helperMHs` field — see HelperRegistry.emitHelperCall.
        //
        // ASM's stock getCommonSuperClass calls Class.forName with the system class loader,
        // which would also choke on the in-progress class if frame computation ever
        // needed to merge a self-typed value. Override to short-circuit those merges.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                boolean isSelf1 = type1.equals(classInternalName);
                boolean isSelf2 = type2.equals(classInternalName);
                if (isSelf1 && isSelf2) return classInternalName;
                if (isSelf1) return super.getCommonSuperClass(COMPILED_BASE_INTERNAL, type2);
                if (isSelf2) return super.getCommonSuperClass(type1, COMPILED_BASE_INTERNAL);
                return super.getCommonSuperClass(type1, type2);
            }

            @Override
            protected ClassLoader getClassLoader() {
                return CompiledDensityFunction.class.getClassLoader();
            }
        };
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                classInternalName, null, COMPILED_BASE_INTERNAL, null);

        // Declare per-noise per-octave ImprovedNoise fields so the inlined emission
        // path can GETFIELD them by name rather than going through the inherited
        // Object[] noiseOctaves with AALOAD+CHECKCAST on every call.
        emitNoiseFields(cw, pool);
        // ext_i copies externs[i] for fast child dispatch (avoids aaload on nested markers).
        emitExternFields(cw, pool);
        emitConstructor(cw, classInternalName, pool);
        // Note: rebind() is implemented in the supertype using the constructor MethodHandle
        // we thread through; we deliberately do NOT emit a rebind override here because that
        // would require a `NEW classInternalName` instruction, which hidden classes cannot
        // emit (the JVM rejects defineHiddenClass when the constant pool names the hidden
        // class itself).

        HelperRegistry helpers = new HelperRegistry(cw, classInternalName, pool, rc, extracted);
        CoordinateReusePlan coordinateReuse = CoordinateReusePlan.analyze(root, rc);
        emitCompute(cw, classInternalName, root, helpers, coordinateReuse);
        helpers.drain();

        // Lattice plan (Tier B5+B6). Computed AFTER the regular helper drain so the
        // helper indices we hand out for `lattice_y` / `lattice_inner` don't fight with
        // the per-spill helper index allocator. The plan is purely a function of the IR
        // shape, so any same-fingerprint cache hit will receive an identical plan and
        // the helpers we emit now stay in lock-step with the cached bytecode.
        boolean latticeEmitted = false;
        if (CELL_LATTICE_ENABLED && !(root instanceof IRNode.Const)) {
            var planOpt = CellLatticeOption.analyze(root);
            if (planOpt.isPresent()) {
                CellLatticeOption.LatticePlan plan = planOpt.get();
                boolean yHoist = plan.hoistAxis() == CellLatticeOption.Axis.Y_ONLY;
                String preName = yHoist ? LATTICE_Y_NAME : LATTICE_XZ_NAME;
                String innerName = yHoist ? LATTICE_INNER_NAME : LATTICE_INNER_XZ_NAME;
                emitLatticePrecomputeHelper(cw, classInternalName, plan, helpers, preName);
                emitLatticeInnerHelper(cw, classInternalName, root, plan, helpers, innerName);
                emitLatticeFillArrayOverride(cw, classInternalName, plan);
                latticeEmitted = true;
            }
        }

        if (!latticeEmitted && root instanceof IRNode.Const c) {
            emitConstRootFillArrayOverride(cw, c.value());
        }
        boolean cellAddLatticeSpecialized = false;
        boolean cellAddBeardifierSpecialized = false;
        boolean cellAddExternSpecialized = false;
        boolean cellScalarMarkerSpecialized = false;
        String cellScalarMarkerReason = latticeEmitted ? "blocked-by=lattice" : "not-evaluated";
        if (!latticeEmitted) {
            cellAddLatticeSpecialized = emitCellFillAddScalarOverrideIfPossible(cw, classInternalName, root, helpers);
            if (!cellAddLatticeSpecialized && CELL_FILL_ADD_BEARDIFIER_OVERRIDE_ENABLED) {
                cellAddBeardifierSpecialized = emitCellFillAddBeardifierOverrideIfPossible(cw, classInternalName, root, helpers, pool);
            }
            if (!cellAddLatticeSpecialized && !cellAddBeardifierSpecialized && CELL_FILL_ADD_EXTERN_OVERRIDE_ENABLED) {
                cellAddExternSpecialized = emitCellFillAddExternOverrideIfPossible(cw, classInternalName, root, helpers, pool);
            }
            if (cellAddLatticeSpecialized) {
                cellScalarMarkerReason = "blocked-by=cellAddLattice";
            } else if (cellAddBeardifierSpecialized) {
                cellScalarMarkerReason = "blocked-by=cellAddBeardifier";
            } else if (cellAddExternSpecialized) {
                cellScalarMarkerReason = "blocked-by=cellAddExtern";
            }
            if (!cellAddLatticeSpecialized
                    && !cellAddBeardifierSpecialized
                    && !cellAddExternSpecialized) {
                ScalarMarkerCellFillDecision decision = scalarMarkerCellFillDecision(root, helpers);
                cellScalarMarkerReason = decision.reason();
                if (decision.enabled()) {
                    emitScalarMarkerCellFillOverride(cw, classInternalName, root, helpers);
                    cellScalarMarkerSpecialized = true;
                    cellScalarMarkerReason = scalarMarkerSpecializationReason(root);
                }
            }
        }

        cw.visitEnd();
        return new Result(cw.toByteArray(), helpers.emittedCount(), latticeEmitted,
                cellAddLatticeSpecialized, cellAddBeardifierSpecialized, cellAddExternSpecialized,
                cellScalarMarkerSpecialized, cellScalarMarkerReason);
    }

    /**
     * Bytecode + count of regular helper methods generated + whether a cell-lattice
     * fast path was emitted. {@code latticeEmitted} is purely diagnostic — it is
     * surfaced through {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RouterPipeline}
     * so {@code /dfc stats} can report "lattice plans: K / N roots".
     */
    public record Result(byte[] bytecode, int helpersEmitted, boolean latticeEmitted,
                          boolean cellAddLatticeSpecialized,
                          boolean cellAddBeardifierSpecialized,
                          boolean cellAddExternSpecialized,
                          boolean cellScalarMarkerSpecialized,
                          String cellScalarMarkerReason) {}

    /* --------------------------------------------------------------------- */
    /* Constructor                                                           */
    /* --------------------------------------------------------------------- */

    /**
     * Declare a {@code private final ImprovedNoise} field for every active octave on
     * every interned NoiseSpec. The flat naming convention is {@code noise_S_B_O}
     * where {@code S} is the spec pool index, {@code B} is {@code 0} (first branch)
     * or {@code 1} (second branch), and {@code O} is the active-octave index inside
     * that branch. The constructor's PUTFIELD stream populates them in the same order
     * the {@link ConstantPool#finishNoiseOctaves()} payload uses.
     */
    private static void emitNoiseFields(ClassWriter cw, ConstantPool pool) {
        int specCount = pool.noiseSpecCount();
        for (int s = 0; s < specCount; s++) {
            var spec = pool.noiseSpec(s);
            int firstCount = spec.first().activeOctaves().length;
            int secondCount = spec.second().activeOctaves().length;
            for (int o = 0; o < firstCount; o++) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        noiseFieldName(s, 0, o), IMPROVED_NOISE_DESC, null, null).visitEnd();
            }
            for (int o = 0; o < secondCount; o++) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        noiseFieldName(s, 1, o), IMPROVED_NOISE_DESC, null, null).visitEnd();
            }
        }
        int bCount = pool.blendedNoiseSpecCount();
        for (int b = 0; b < bCount; b++) {
            for (int o = 0; o < BlendedNoiseSpec.MAIN_OCTAVES; o++) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        blendedFieldName(b, 0, o), IMPROVED_NOISE_DESC, null, null).visitEnd();
            }
            for (int o = 0; o < BlendedNoiseSpec.LIMIT_OCTAVES; o++) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        blendedFieldName(b, 1, o), IMPROVED_NOISE_DESC, null, null).visitEnd();
            }
            for (int o = 0; o < BlendedNoiseSpec.LIMIT_OCTAVES; o++) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        blendedFieldName(b, 2, o), IMPROVED_NOISE_DESC, null, null).visitEnd();
            }
        }
    }

    /** Stable per-octave field name used by both {@link #emitNoiseFields} and the codegen. */
    static String noiseFieldName(int specIdx, int branch, int activeOctaveIdx) {
        return "noise_" + specIdx + "_" + branch + "_" + activeOctaveIdx;
    }

    /**
     * Per-octave field for {@link IRNode.InlinedBlendedNoise}:
     * section {@code 0} = main 0..7, {@code 1} = min limit 0..15, {@code 2} = max limit 0..15.
     */
    static String blendedFieldName(int blendedSpecIdx, int section, int subIndex) {
        String tag = section == 0 ? "m" : (section == 1 ? "a" : "b");
        return "blnd_" + blendedSpecIdx + "_" + tag + "_" + subIndex;
    }

    /**
     * One {@code private final} reference per {@link ConstantPool#extern(int)} index.
     * Populated in {@link #emitConstructor} from the same {@code DensityFunction[]}
     * passed to {@code super}; mirrors {@code externs[i]} and stays correct across
     * {@link CompiledDensityFunction#rebind} (fresh instance, constructor re-runs).
     */
    static String externFieldName(int index) {
        return "ext_" + index;
    }

    static String externCacheArrayFieldName(int index) {
        return "ext_" + index + "_cache_array";
    }

    static String externCacheAccessFieldName(int index) {
        return "ext_" + index + "_cache_access";
    }

    static String externInterpolatorIndexFieldName(int index) {
        return "ext_" + index + "_interpolator_index";
    }

    private static void emitExternFields(ClassWriter cw, ConstantPool pool) {
        int n = pool.externCount();
        for (int i = 0; i < n; i++) {
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                    externFieldName(i), DENSITY_FUNCTION_DESC, null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                    externInterpolatorIndexFieldName(i), "I", null, null).visitEnd();
            if (pool.externHasCacheWrapperFastPath(i)) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        externCacheArrayFieldName(i), DFC_CELL_CACHE_ARRAY_INDEX_ACCESS_DESC, null, null).visitEnd();
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        externCacheAccessFieldName(i), DFC_CELL_CACHE_ACCESS_DESC, null, null).visitEnd();
            }
        }
    }

    private static void emitConstructor(ClassWriter cw, String classInternalName, ConstantPool pool) {
        // (double[], NormalNoise[], Object[], Object[], DensityFunction[], double, double,
        //  MethodHandle[], MethodHandle)
        // Slot layout: this=0, constants=1, noises=2, splines=3, noiseOctaves=4,
        // externs=5, minValue=6/7, maxValue=8/9, helperHandles=10, constructorMH=11.
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CTOR_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.DLOAD, 6);
        mv.visitVarInsn(Opcodes.DLOAD, 8);
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitVarInsn(Opcodes.ALOAD, 11);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, "<init>", CTOR_DESC, false);

        for (int i = 0; i < pool.externCount(); i++) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            ldcIntStatic(mv, i);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitFieldInsn(Opcodes.PUTFIELD, classInternalName, externFieldName(i), DENSITY_FUNCTION_DESC);
            emitOptionalInterpolatorIndexPutfield(mv, classInternalName, i);
            if (pool.externHasCacheWrapperFastPath(i)) {
                emitOptionalExternCastPutfield(mv, classInternalName, i,
                        externCacheArrayFieldName(i), DFC_CELL_CACHE_ARRAY_INDEX_ACCESS_DESC,
                        DFC_CELL_CACHE_ARRAY_INDEX_ACCESS_INTERNAL);
                emitOptionalExternCastPutfield(mv, classInternalName, i,
                        externCacheAccessFieldName(i), DFC_CELL_CACHE_ACCESS_DESC,
                        DFC_CELL_CACHE_ACCESS_INTERNAL);
            }
        }

        // Populate per-octave fields from the noiseOctaves[] payload. Layout matches
        // ConstantPool.finishNoiseOctaves(): per-spec, first branch then second
        // branch, active octaves only.
        int cursor = 0;
        int specCount = pool.noiseSpecCount();
        for (int s = 0; s < specCount; s++) {
            var spec = pool.noiseSpec(s);
            int firstCount = spec.first().activeOctaves().length;
            int secondCount = spec.second().activeOctaves().length;
            for (int o = 0; o < firstCount; o++) {
                emitOctavePutfield(mv, classInternalName, s, 0, o, cursor++);
            }
            for (int o = 0; o < secondCount; o++) {
                emitOctavePutfield(mv, classInternalName, s, 1, o, cursor++);
            }
        }
        for (int b = 0; b < pool.blendedNoiseSpecCount(); b++) {
            for (int o = 0; o < BlendedNoiseSpec.MAIN_OCTAVES; o++) {
                emitBlendedPutfield(mv, classInternalName, b, 0, o, cursor++);
            }
            for (int o = 0; o < BlendedNoiseSpec.LIMIT_OCTAVES; o++) {
                emitBlendedPutfield(mv, classInternalName, b, 1, o, cursor++);
            }
            for (int o = 0; o < BlendedNoiseSpec.LIMIT_OCTAVES; o++) {
                emitBlendedPutfield(mv, classInternalName, b, 2, o, cursor++);
            }
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitOptionalInterpolatorIndexPutfield(MethodVisitor mv, String classInternalName,
                                                              int externIndex) {
        Label missing = new Label();
        Label done = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        ldcIntStatic(mv, externIndex);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, NOISE_INTERPOLATOR_PATCH_INTERNAL);
        mv.visitJumpInsn(Opcodes.IFEQ, missing);
        mv.visitTypeInsn(Opcodes.CHECKCAST, NOISE_INTERPOLATOR_PATCH_INTERNAL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, NOISE_INTERPOLATOR_PATCH_INTERNAL,
                "bts$getSoAIndex", "()I", true);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(missing);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitLabel(done);
        mv.visitFieldInsn(Opcodes.PUTFIELD, classInternalName,
                externInterpolatorIndexFieldName(externIndex), "I");
    }

    /**
     * Constructor helper: cache a typed view of {@code externs[i]} once so hot marker
     * reads do not repeat {@code instanceof/checkcast} for every block position.
     */
    private static void emitOptionalExternCastPutfield(MethodVisitor mv, String classInternalName,
                                                       int externIndex, String fieldName,
                                                       String fieldDesc, String targetInternalName) {
        Label missing = new Label();
        Label done = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        ldcIntStatic(mv, externIndex);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, targetInternalName);
        mv.visitJumpInsn(Opcodes.IFEQ, missing);
        mv.visitTypeInsn(Opcodes.CHECKCAST, targetInternalName);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(missing);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitLabel(done);
        mv.visitFieldInsn(Opcodes.PUTFIELD, classInternalName, fieldName, fieldDesc);
    }

    /** Single AALOAD+CHECKCAST+PUTFIELD pair for one per-octave field. */
    private static void emitOctavePutfield(MethodVisitor mv, String classInternalName,
                                           int specIdx, int branch, int activeOctaveIdx, int payloadIdx) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        // noiseOctaves is constructor-arg slot 4.
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        ldcIntStatic(mv, payloadIdx);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitTypeInsn(Opcodes.CHECKCAST, IMPROVED_NOISE_INTERNAL);
        mv.visitFieldInsn(Opcodes.PUTFIELD, classInternalName,
                noiseFieldName(specIdx, branch, activeOctaveIdx), IMPROVED_NOISE_DESC);
    }

    private static void emitBlendedPutfield(MethodVisitor mv, String classInternalName,
                                        int bIdx, int section, int sub, int payloadIdx) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        ldcIntStatic(mv, payloadIdx);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitTypeInsn(Opcodes.CHECKCAST, IMPROVED_NOISE_INTERNAL);
        mv.visitFieldInsn(Opcodes.PUTFIELD, classInternalName,
                blendedFieldName(bIdx, section, sub), IMPROVED_NOISE_DESC);
    }

    /** Static-context twin of {@code EmitState.ldcInt} for the constructor body. */
    private static void ldcIntStatic(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(Opcodes.ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, v);
        else mv.visitLdcInsn(v);
    }

    /* --------------------------------------------------------------------- */
    /* compute(FunctionContext)                                              */
    /* --------------------------------------------------------------------- */

    /**
     * Slot conventions inside compute() and every helper:
     * <pre>
     *   slot 0  this / self (object reference)
     *   slot 1  ctx (FunctionContext)
     *   slot 2  blockX (int)
     *   slot 3  blockY (int)
     *   slot 4  blockZ (int)
     *   slot 5+ rolling allocator for spilled doubles / float scratch
     * </pre>
     */
    private static void emitCompute(ClassWriter cw, String classInternalName, IRNode root,
                                    HelperRegistry helpers,
                                    CoordinateReusePlan coordinateReuse) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "compute",
                "(L" + FUNCTION_CONTEXT_INTERNAL + ";)D", null, null);
        mv.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        mv.visitTryCatchBlock(start, end, handler, "java/lang/ArrayIndexOutOfBoundsException");
        mv.visitLabel(start);
        emitCoordPrologue(mv, CoordinateSlotUse.analyze(root, helpers.extracted, false));

        EmitState st = new EmitState(mv, classInternalName, helpers, false, coordinateReuse);
        st.emit(root);

        mv.visitInsn(Opcodes.DRETURN);
        mv.visitLabel(end);
        mv.visitLabel(handler);
        mv.visitVarInsn(Opcodes.ASTORE, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn("compute");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COMPILED_BASE_INTERNAL,
                "dfc$wrapArrayIndexOutOfBounds", WRAP_AIOOBE_DESC, false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Constant root: override {@code fillArray} with {@link java.util.Arrays#fill} only, so
     * non-const compiled DFs keep the default single-call fill path (no per-fill overhead).
     */
    private static void emitConstRootFillArrayOverride(ClassWriter cw, double constValue) {
        String desc = "([DL" + CONTEXT_PROVIDER_INTERNAL + ";)V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "fillArray", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label hasBuf = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, hasBuf);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(hasBuf);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        Label nonEmpty = new Label();
        mv.visitJumpInsn(Opcodes.IFGT, nonEmpty);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(nonEmpty);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn(constValue);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "fill", "([DD)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /* --------------------------------------------------------------------- */
    /* Cell-lattice fast path (Tier B5+B6)                                   */
    /* --------------------------------------------------------------------- */

    /**
     * Emit the {@code lattice_y} static helper — exactly one method body that
     * computes the {@link CellLatticeOption.LatticePlan#hoistedSubtree() hoisted
     * Y-only subtree} given the same {@code (self, ctx)} signature as a regular
     * helper. Called by the {@code fillArray} override once per Y-position to
     * cache the per-Y value before the (x, z) inner loops.
     *
     * <p>Same {@link HelperRegistry} instance is reused so the lattice helper's
     * per-helper child extractions thread back through the same pool that the
     * regular helpers use — no double emission of the same extracted subtree.
     */
    private static void emitLatticePrecomputeHelper(ClassWriter cw, String classInternalName,
                                                    CellLatticeOption.LatticePlan plan,
                                                    HelperRegistry helpers,
                                                    String methodName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                methodName, HELPER_DESC, null, null);
        mv.visitCode();
        emitCoordPrologue(mv, CoordinateSlotUse.analyze(plan.hoistedSubtree(), helpers.extracted, false));
        EmitState st = new EmitState(mv, classInternalName, helpers, /* castSelfForSubclassNoiseFields */ true);
        st.emit(plan.hoistedSubtree());
        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emit the {@code lattice_inner} static helper — same body as {@code compute},
     * but the hoisted Y-only subtree is replaced everywhere with the precomputed
     * value passed in as the third method parameter. The resulting body is the
     * "inner expression" reused {@code cellWidth × cellWidth} times per Y-slab in
     * the {@link #emitLatticeFillArrayOverride fillArray override}.
     *
     * <p>Slot layout:
     * <ul>
     *   <li>0 — {@code self} (CompiledDensityFunction)</li>
     *   <li>1 — {@code ctx}  (FunctionContext)</li>
     *   <li>2-3 — {@code yPrecomputed} (double; the third method parameter)</li>
     * </ul>
     *
     * <p>The shared {@link #emitCoordPrologue} writes int blockX/Y/Z to slots
     * 2/3/4, which would clobber the high half of {@code yPrecomputed}. We
     * therefore copy {@code yPrecomputed} into slots 5/6 first, then run the
     * prologue, then preinstall a spill mapping {@code hoistedSubtree → 5} on
     * the {@link EmitState} so every {@code emit()} call that encounters the
     * hoisted node loads the cached double instead of recomputing it. This is
     * the precompute-cache contract we built {@code preinstallSpill} for.
     */
    private static void emitLatticeInnerHelper(ClassWriter cw, String classInternalName,
                                               IRNode root,
                                               CellLatticeOption.LatticePlan plan,
                                               HelperRegistry helpers,
                                               String innerMethodName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                innerMethodName, LATTICE_INNER_DESC, null, null);
        mv.visitCode();

        // Copy yPrecomputed (slots 2/3) into a "safe" double slot before the
        // coord prologue overwrites slot 2 with int blockX. Slot 5/6 is the first
        // free slot pair past the coord-prologue slots (2, 3, 4).
        final int yPrecomputedSlot = 5;
        mv.visitVarInsn(Opcodes.DLOAD, 2);
        mv.visitVarInsn(Opcodes.DSTORE, yPrecomputedSlot);

        emitCoordPrologue(mv, CoordinateSlotUse.analyze(root, helpers.extracted, false,
                CoordinateSlotUse.singletonIdentitySet(plan.hoistedSubtree())));

        EmitState st = new EmitState(mv, classInternalName, helpers, /* castSelfForSubclassNoiseFields */ true);
        st.preinstallSpill(plan.hoistedSubtree(), yPrecomputedSlot);
        st.emit(root);

        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emit a {@code fillArray(double[], ContextProvider)} override that drives
     * vanilla's (y, x, z) cell triple loop directly, calling {@link #LATTICE_Y_NAME}
     * once per Y-position and {@link #LATTICE_INNER_NAME} for every (y, x, z) cell.
     * The override only fires when the provider is a {@code NoiseChunk}; any other
     * provider falls through to the supertype's
     * {@link CompiledDensityFunction#fillArray scalar path} (which is what
     * {@code NoiseChunk.sliceFillingContextProvider} ends up using anyway, since
     * that provider is not the {@code NoiseChunk} itself).
     *
     * <h2>Equivalent Java</h2>
     * <pre>
     * public void fillArray(double[] values, ContextProvider provider) {
     *     if (provider instanceof NoiseChunk nc) {
     *         nc.arrayIndex = 0;
     *         for (int yi = nc.cellHeight - 1; yi &gt;= 0; yi--) {
     *             nc.inCellY = yi;
     *             double yPre = lattice_y(this, nc);
     *             for (int xi = 0; xi &lt; nc.cellWidth; xi++) {
     *                 nc.inCellX = xi;
     *                 for (int zi = 0; zi &lt; nc.cellWidth; zi++) {
     *                     nc.inCellZ = zi;
     *                     values[nc.arrayIndex++] = lattice_inner(this, nc, yPre);
     *                 }
     *             }
     *         }
     *         return;
     *     }
     *     super.fillArray(values, provider);
     * }
     * </pre>
     *
     * <h2>Why not just override the inner Marker / FlatCache</h2>
     *
     * <p>NoiseChunk's wrap-then-iterate model already expects each
     * {@link DensityFunction} child to drive its own {@code fillArray} via the
     * provider — that's the existing {@code provider.fillAllDirectly(values, this)}
     * fallback we sit on top of. The vanilla path runs the (y, x, z) triple loop in
     * {@code NoiseChunk.fillAllDirectly}, calling {@code compute(this)} per cell
     * — recomputing the Y-only subtree {@code cellWidth × cellWidth} times per Y.
     * Overriding {@code fillArray} here lets us keep the same iteration order
     * vanilla uses (so the order of side-effecting noise samples remains identical
     * — important for parity) but lift the per-Y precompute out of the inner two
     * loops.
     *
     * <h2>Correctness boundary</h2>
     *
     * <p>The provider check is an exact {@code INSTANCEOF NoiseChunk}, not a
     * structural match. {@code DebugCellProvider} (a hypothetical subclass of
     * {@code NoiseChunk} we don't ship) would still trigger the fast path; that's
     * fine because the inner loop's only assumption is that {@code blockX/Y/Z} on
     * the FunctionContext are derived from {@code cellStartBlockX + inCellX}, etc.
     * — which is part of NoiseChunk's public contract.
     *
     * <p>Subclasses of NoiseChunk that override {@code fillAllDirectly} would
     * normally see their override called by the supertype's {@link
     * CompiledDensityFunction#fillArray} fallback; the lattice override skips
     * that delegation. If a subclass needs the override-based hook, it should
     * also override {@code fillArray} on its own DensityFunctions. None of the
     * vanilla subclasses do.
     *
     */
    private static void emitLatticeFillArrayOverride(ClassWriter cw, String classInternalName,
                                                     CellLatticeOption.LatticePlan latticePlan) {
        emitLatticeFillArrayScalarOnly(cw, classInternalName, latticePlan);
    }

    private static void emitMarkerSlotCompute(MethodVisitor mv, String classInternalName,
                                              ConstantPool pool, int externIndex, int contextLocal) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
        mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(externIndex), DENSITY_FUNCTION_DESC);
        mv.visitVarInsn(Opcodes.ALOAD, contextLocal);
        if (pool.externHasCacheWrapperFastPath(externIndex)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CACHE_FAST_PATH_INTERNAL, "computeWithOptionalDirectRead",
                    CACHE_FAST_READ_DESC, false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DENSITY_FUNCTION_INTERNAL,
                    "compute", "(L" + FUNCTION_CONTEXT_INTERNAL + ";)D", true);
        }
    }

    private static void emitLatticeFillArrayScalarOnly(ClassWriter cw, String classInternalName,
                                                       CellLatticeOption.LatticePlan latticePlan) {
        String desc = "([DL" + CONTEXT_PROVIDER_INTERNAL + ";)V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "fillArray", desc, null, null);
        mv.visitCode();

        Label sliceCheck = new Label();
        Label fallback = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, NOISE_CHUNK_INTERNAL);
        mv.visitJumpInsn(Opcodes.IFEQ, sliceCheck);

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, NOISE_CHUNK_INTERNAL);
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellWidth", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 5);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        if (latticePlan.hoistAxis() == CellLatticeOption.Axis.XZ_ONLY) {
            emitLatticeFillArrayScalarXZHoistLoops(mv, LATTICE_INNER_XZ_NAME);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
            mv.visitInsn(Opcodes.RETURN);
        }

        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        Label yLoopHead = new Label();
        Label yLoopExit = new Label();
        mv.visitLabel(yLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitJumpInsn(Opcodes.IFLT, yLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInvokeDynamicInsn(LATTICE_Y_NAME, HELPER_DESC, HELPER_BSM);
        mv.visitVarInsn(Opcodes.DSTORE, 11);

        emitLatticeFillArrayInnerScalarXZ(mv, LATTICE_INNER_NAME);

        mv.visitIincInsn(6, -1);
        mv.visitJumpInsn(Opcodes.GOTO, yLoopHead);
        mv.visitLabel(yLoopExit);

        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(sliceCheck);
        emitLatticeFillArraySliceFastPath(mv, latticePlan.hoistAxis(), fallback);

        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, "fillArray", desc, false);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();

        emitLatticeFillCellScalarOnly(cw, latticePlan);
    }

    private static void emitLatticeFillCellScalarOnly(ClassWriter cw, CellLatticeOption.LatticePlan latticePlan) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "dfc$fillCell", CELL_FILL_DESC, null, null);
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellWidth", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        if (latticePlan.hoistAxis() == CellLatticeOption.Axis.XZ_ONLY) {
            emitLatticeFillArrayScalarXZHoistLoops(mv, LATTICE_INNER_XZ_NAME);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return;
        }

        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        Label yLoopHead = new Label();
        Label yLoopExit = new Label();
        mv.visitLabel(yLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitJumpInsn(Opcodes.IFLT, yLoopExit);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInvokeDynamicInsn(LATTICE_Y_NAME, HELPER_DESC, HELPER_BSM);
        mv.visitVarInsn(Opcodes.DSTORE, 11);
        emitLatticeFillArrayInnerScalarXZ(mv, LATTICE_INNER_NAME);
        mv.visitIincInsn(6, -1);
        mv.visitJumpInsn(Opcodes.GOTO, yLoopHead);
        mv.visitLabel(yLoopExit);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor acc = cw.visitMethod(Opcodes.ACC_PUBLIC, DFC_ACCUMULATE_CELL_NAME, CELL_FILL_DESC, null, null);
        acc.visitCode();
        if (latticePlan.hoistAxis() != CellLatticeOption.Axis.XZ_ONLY) {
            acc.visitVarInsn(Opcodes.ALOAD, 0);
            acc.visitVarInsn(Opcodes.ALOAD, 1);
            acc.visitVarInsn(Opcodes.ALOAD, 2);
            acc.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, DFC_ACCUMULATE_CELL_NAME, CELL_FILL_DESC, false);
            acc.visitInsn(Opcodes.RETURN);
            acc.visitMaxs(0, 0);
            acc.visitEnd();
            return;
        }

        acc.visitVarInsn(Opcodes.ALOAD, 2);
        acc.visitVarInsn(Opcodes.ASTORE, 3);
        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellWidth", "I");
        acc.visitVarInsn(Opcodes.ISTORE, 4);
        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        acc.visitVarInsn(Opcodes.ISTORE, 5);
        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitInsn(Opcodes.ICONST_0);
        acc.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        acc.visitInsn(Opcodes.ICONST_0);
        acc.visitVarInsn(Opcodes.ISTORE, 7);
        Label xLoopHead = new Label();
        Label xLoopExit = new Label();
        acc.visitLabel(xLoopHead);
        acc.visitVarInsn(Opcodes.ILOAD, 7);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitJumpInsn(Opcodes.IF_ICMPGE, xLoopExit);

        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitVarInsn(Opcodes.ILOAD, 7);
        acc.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");

        acc.visitInsn(Opcodes.ICONST_0);
        acc.visitVarInsn(Opcodes.ISTORE, 8);
        Label zLoopHead = new Label();
        Label zLoopExit = new Label();
        acc.visitLabel(zLoopHead);
        acc.visitVarInsn(Opcodes.ILOAD, 8);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitJumpInsn(Opcodes.IF_ICMPGE, zLoopExit);

        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitVarInsn(Opcodes.ILOAD, 8);
        acc.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");

        acc.visitVarInsn(Opcodes.ALOAD, 0);
        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitInvokeDynamicInsn(LATTICE_XZ_NAME, HELPER_DESC, HELPER_BSM);
        acc.visitVarInsn(Opcodes.DSTORE, 11);

        acc.visitVarInsn(Opcodes.ILOAD, 5);
        acc.visitInsn(Opcodes.ICONST_1);
        acc.visitInsn(Opcodes.ISUB);
        acc.visitVarInsn(Opcodes.ISTORE, 6);
        Label yLoopHead2 = new Label();
        Label yLoopExit2 = new Label();
        acc.visitLabel(yLoopHead2);
        acc.visitVarInsn(Opcodes.ILOAD, 6);
        acc.visitJumpInsn(Opcodes.IFLT, yLoopExit2);

        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitVarInsn(Opcodes.ILOAD, 6);
        acc.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");

        acc.visitVarInsn(Opcodes.ILOAD, 5);
        acc.visitInsn(Opcodes.ICONST_1);
        acc.visitInsn(Opcodes.ISUB);
        acc.visitVarInsn(Opcodes.ILOAD, 6);
        acc.visitInsn(Opcodes.ISUB);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitInsn(Opcodes.IMUL);
        acc.visitInsn(Opcodes.IMUL);
        acc.visitVarInsn(Opcodes.ILOAD, 7);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitInsn(Opcodes.IMUL);
        acc.visitInsn(Opcodes.IADD);
        acc.visitVarInsn(Opcodes.ILOAD, 8);
        acc.visitInsn(Opcodes.IADD);
        acc.visitVarInsn(Opcodes.ISTORE, 10);

        acc.visitVarInsn(Opcodes.ALOAD, 0);
        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitVarInsn(Opcodes.DLOAD, 11);
        acc.visitInvokeDynamicInsn(LATTICE_INNER_XZ_NAME, LATTICE_INNER_DESC, HELPER_BSM);
        acc.visitVarInsn(Opcodes.DSTORE, 13);

        emitArrayAccumulateFromTemp(acc, 13, 10);

        acc.visitIincInsn(6, -1);
        acc.visitJumpInsn(Opcodes.GOTO, yLoopHead2);
        acc.visitLabel(yLoopExit2);

        acc.visitIincInsn(8, 1);
        acc.visitJumpInsn(Opcodes.GOTO, zLoopHead);
        acc.visitLabel(zLoopExit);

        acc.visitIincInsn(7, 1);
        acc.visitJumpInsn(Opcodes.GOTO, xLoopHead);
        acc.visitLabel(xLoopExit);

        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitInsn(Opcodes.IMUL);
        acc.visitVarInsn(Opcodes.ILOAD, 5);
        acc.visitInsn(Opcodes.IMUL);
        acc.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
        acc.visitInsn(Opcodes.RETURN);
        acc.visitMaxs(0, 0);
        acc.visitEnd();
    }

    private static void emitCellFillComputeHelper(ClassWriter cw, String classInternalName,
                                                  IRNode root, HelperRegistry helpers,
                                                  String methodName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                methodName, HELPER_DESC, null, null);
        mv.visitCode();
        emitCoordPrologue(mv, CoordinateSlotUse.analyze(root, helpers.extracted, false));
        EmitState st = new EmitState(mv, classInternalName, helpers, true);
        st.emit(root);
        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private record ScalarMarkerCellFillDecision(boolean enabled, String reason) {}
    private record ScalarMarkerSqueezeMulPlan(int markerExternIndex, double scale) {}
    private record ScalarMarkerLazyRangeChoiceZPlan(IRNode.BinOp op,
                                                    ScalarMarkerSqueezeMulPlan squeezePlan,
                                                    IRNode.RangeChoice rangeChoice,
                                                    boolean squeezeOnLeft,
                                                    int[] eagerZMarkerIndexes,
                                                    int[] lazyOutZMarkerIndexes) {}

    private static ScalarMarkerCellFillDecision scalarMarkerCellFillDecision(IRNode root, HelperRegistry helpers) {
        if (!cellFillScalarMarkerOverrideEnabled()) {
            return new ScalarMarkerCellFillDecision(false, "disabled");
        }
        if (root instanceof IRNode.Const) {
            return new ScalarMarkerCellFillDecision(false, "const-root");
        }
        if (!helpers.extracted.isEmpty()) {
            return new ScalarMarkerCellFillDecision(false, "helpers=" + helpers.extracted.size());
        }
        final boolean[] hasMarker = {false};
        final String[] unsupported = {null};
        IrTreeSupport.visitUnique(root, node -> {
            switch (node) {
                case IRNode.Const ignored -> {}
                case IRNode.Bin ignored -> {}
                case IRNode.Unary ignored -> {}
                case IRNode.Clamp ignored -> {}
                case IRNode.RangeChoice ignored -> {}
                case IRNode.Marker ignored -> hasMarker[0] = true;
                default -> {
                    if (unsupported[0] == null) {
                        unsupported[0] = scalarMarkerUnsupportedName(node);
                    }
                }
            }
        });
        if (!hasMarker[0]) {
            return new ScalarMarkerCellFillDecision(false, "marker-missing");
        }
        if (unsupported[0] != null) {
            return new ScalarMarkerCellFillDecision(false, "unsupported=" + unsupported[0]);
        }
        CoordinateSlotUse coords = CoordinateSlotUse.analyze(root, Collections.emptySet(), true);
        if (coords.blockX() || coords.blockY() || coords.blockZ()) {
            return new ScalarMarkerCellFillDecision(false, "coordinate-dep=" + coordinateUseName(coords));
        }
        int estimatedSize = new SizeEstimator(helpers.pool).size(root, helpers.extracted);
        if (estimatedSize > 1024) {
            return new ScalarMarkerCellFillDecision(false, "size=" + estimatedSize);
        }
        return new ScalarMarkerCellFillDecision(true, "eligible:size=" + estimatedSize);
    }

    private static String scalarMarkerUnsupportedName(IRNode node) {
        return switch (node) {
            case IRNode.BlockX ignored -> "BlockX";
            case IRNode.BlockY ignored -> "BlockY";
            case IRNode.BlockZ ignored -> "BlockZ";
            case IRNode.YClampedGradient ignored -> "YClampedGradient";
            case IRNode.Noise ignored -> "Noise";
            case IRNode.ShiftedNoise ignored -> "ShiftedNoise";
            case IRNode.ShiftA ignored -> "ShiftA";
            case IRNode.ShiftB ignored -> "ShiftB";
            case IRNode.Shift ignored -> "Shift";
            case IRNode.WeirdScaled ignored -> "WeirdScaled";
            case IRNode.InlinedNoise ignored -> "InlinedNoise";
            case IRNode.InlinedBlendedNoise ignored -> "InlinedBlendedNoise";
            case IRNode.WeirdRarity ignored -> "WeirdRarity";
            case IRNode.EndIslands ignored -> "EndIslands";
            case IRNode.Beardifier ignored -> "Beardifier";
            case IRNode.Spline.Constant ignored -> "SplineConstant";
            case IRNode.Spline.Multipoint ignored -> "SplineMultipoint";
            case IRNode.Invoke ignored -> "Invoke";
            case IRNode.BlendDensity ignored -> "BlendDensity";
            case IRNode.Const ignored -> "Const";
            case IRNode.Bin ignored -> "Bin";
            case IRNode.Unary ignored -> "Unary";
            case IRNode.Clamp ignored -> "Clamp";
            case IRNode.RangeChoice ignored -> "RangeChoice";
            case IRNode.Marker ignored -> "Marker";
        };
    }

    private static String coordinateUseName(CoordinateSlotUse coords) {
        StringBuilder out = new StringBuilder(3);
        if (coords.blockX()) out.append('x');
        if (coords.blockY()) out.append('y');
        if (coords.blockZ()) out.append('z');
        return out.isEmpty() ? "none" : out.toString();
    }

    private static int[] scalarMarkerIndexes(IRNode root) {
        TreeSet<Integer> indexes = new TreeSet<>();
        IrTreeSupport.visitUnique(root, node -> {
            if (node instanceof IRNode.Marker marker) {
                indexes.add(marker.externIndex());
            }
        });
        int[] out = new int[indexes.size()];
        int i = 0;
        for (int index : indexes) {
            out[i++] = index;
        }
        return out;
    }

    private static int[] toIntArray(TreeSet<Integer> indexes) {
        int[] out = new int[indexes.size()];
        int i = 0;
        for (int index : indexes) {
            out[i++] = index;
        }
        return out;
    }

    private static ScalarMarkerSqueezeMulPlan scalarMarkerSqueezeMulPlan(IRNode root) {
        if (!(root instanceof IRNode.Unary unary) || unary.op() != IRNode.UnaryOp.SQUEEZE) {
            return null;
        }
        if (!(unary.input() instanceof IRNode.Bin bin) || bin.op() != IRNode.BinOp.MUL) {
            return null;
        }
        return scalarMarkerMulPlan(bin.left(), bin.right());
    }

    private static ScalarMarkerSqueezeMulPlan scalarMarkerMulPlan(IRNode left, IRNode right) {
        if (left instanceof IRNode.Const constant && right instanceof IRNode.Marker marker) {
            return scalarMarkerSqueezeMulPlan(marker, constant.value());
        }
        if (left instanceof IRNode.Marker marker && right instanceof IRNode.Const constant) {
            return scalarMarkerSqueezeMulPlan(marker, constant.value());
        }
        return null;
    }

    private static ScalarMarkerSqueezeMulPlan scalarMarkerSqueezeMulPlan(IRNode.Marker marker, double scale) {
        if (!Double.isFinite(scale)) {
            return null;
        }
        return new ScalarMarkerSqueezeMulPlan(marker.externIndex(), scale);
    }

    private static ScalarMarkerLazyRangeChoiceZPlan scalarMarkerLazyRangeChoiceZPlan(IRNode root) {
        if (!cellFillScalarMarkerLazyRangeChoiceZEnabled()) {
            return null;
        }
        if (!(root instanceof IRNode.Bin bin) || (bin.op() != IRNode.BinOp.MIN && bin.op() != IRNode.BinOp.MAX)) {
            return null;
        }
        ScalarMarkerSqueezeMulPlan leftPlan = scalarMarkerSqueezeMulPlan(bin.left());
        ScalarMarkerSqueezeMulPlan rightPlan = scalarMarkerSqueezeMulPlan(bin.right());
        if (leftPlan != null && bin.right() instanceof IRNode.RangeChoice rangeChoice) {
            return scalarMarkerLazyRangeChoiceZPlan(bin.op(), leftPlan, rangeChoice, true);
        }
        if (rightPlan != null && bin.left() instanceof IRNode.RangeChoice rangeChoice) {
            return scalarMarkerLazyRangeChoiceZPlan(bin.op(), rightPlan, rangeChoice, false);
        }
        return null;
    }

    private static ScalarMarkerLazyRangeChoiceZPlan scalarMarkerLazyRangeChoiceZPlan(IRNode.BinOp op,
                                                                                     ScalarMarkerSqueezeMulPlan squeezePlan,
                                                                                     IRNode.RangeChoice rangeChoice,
                                                                                     boolean squeezeOnLeft) {
        if (!(rangeChoice.whenInRange() instanceof IRNode.Const)) {
            return null;
        }
        int[] outMarkers = scalarMarkerIndexes(rangeChoice.whenOutOfRange());
        if (outMarkers.length == 0) {
            return null;
        }
        TreeSet<Integer> eager = new TreeSet<>();
        eager.add(squeezePlan.markerExternIndex());
        for (int markerIndex : scalarMarkerIndexes(rangeChoice.input())) {
            eager.add(markerIndex);
        }
        for (int markerIndex : scalarMarkerIndexes(rangeChoice.whenInRange())) {
            eager.add(markerIndex);
        }
        return new ScalarMarkerLazyRangeChoiceZPlan(op, squeezePlan, rangeChoice, squeezeOnLeft,
                toIntArray(eager), outMarkers);
    }

    private static String scalarMarkerSpecializationReason(IRNode root) {
        ScalarMarkerLazyRangeChoiceZPlan lazyRangeChoiceZPlan = scalarMarkerLazyRangeChoiceZPlan(root);
        if (lazyRangeChoiceZPlan != null) {
            String opName = lazyRangeChoiceZPlan.op().name().toLowerCase(Locale.ROOT);
            return "emitted:" + opName + "-squeeze-mul-lazy-z";
        }
        if (scalarMarkerSqueezeMulPlan(root) != null) {
            return "emitted:squeeze-mul";
        }
        if (root instanceof IRNode.Bin bin
                && (bin.op() == IRNode.BinOp.MIN || bin.op() == IRNode.BinOp.MAX)) {
            if (scalarMarkerSqueezeMulPlan(bin.left()) != null
                    || scalarMarkerSqueezeMulPlan(bin.right()) != null) {
                return "emitted:" + bin.op().name().toLowerCase(Locale.ROOT) + "-squeeze-mul";
            }
        }
        return "emitted";
    }

    private static void emitScalarMarkerCellFillOverride(ClassWriter cw, String classInternalName,
                                                         IRNode root, HelperRegistry helpers) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "dfc$fillCell", CELL_FILL_DESC, null, null);
        mv.visitCode();
        Label fallback = new Label();
        emitScalarMarkerInterpolatorGuard(mv, classInternalName, root, helpers, fallback, 9);
        emitScalarMarkerInterpolatorCellFillLoop(mv, classInternalName, root, helpers, false, 9);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, "dfc$fillCell", CELL_FILL_DESC, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor acc = cw.visitMethod(Opcodes.ACC_PUBLIC, DFC_ACCUMULATE_CELL_NAME, CELL_FILL_DESC, null, null);
        acc.visitCode();
        Label accFallback = new Label();
        emitScalarMarkerInterpolatorGuard(acc, classInternalName, root, helpers, accFallback, 9);
        emitScalarMarkerInterpolatorCellFillLoop(acc, classInternalName, root, helpers, true, 9);
        acc.visitInsn(Opcodes.RETURN);
        acc.visitLabel(accFallback);
        acc.visitVarInsn(Opcodes.ALOAD, 0);
        acc.visitVarInsn(Opcodes.ALOAD, 1);
        acc.visitVarInsn(Opcodes.ALOAD, 2);
        acc.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, DFC_ACCUMULATE_CELL_NAME, CELL_FILL_DESC, false);
        acc.visitInsn(Opcodes.RETURN);
        acc.visitMaxs(0, 0);
        acc.visitEnd();
    }

    private static void emitScalarMarkerInterpolatorGuard(MethodVisitor mv, String classInternalName,
                                                          IRNode root, HelperRegistry helpers,
                                                          Label fallback, int soaLocal) {
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, INTERPOLATOR_SOA_PATH_INTERNAL);
        mv.visitJumpInsn(Opcodes.IFEQ, fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, INTERPOLATOR_SOA_PATH_INTERNAL);
        mv.visitVarInsn(Opcodes.ASTORE, soaLocal);

        HashSet<Integer> seen = new HashSet<>();
        IrTreeSupport.visitUnique(root, node -> {
            if (node instanceof IRNode.Marker marker && seen.add(marker.externIndex())) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName,
                        externInterpolatorIndexFieldName(marker.externIndex()), "I");
            mv.visitJumpInsn(Opcodes.IFLT, fallback);
            }
        });
    }

    private static void emitScalarMarkerInterpolatorCellFillLoop(MethodVisitor mv, String classInternalName,
                                                                 IRNode root, HelperRegistry helpers,
                                                                 boolean accumulate, int soaLocal) {
        int[] markerIndexes = scalarMarkerIndexes(root);
        ScalarMarkerLazyRangeChoiceZPlan lazyRangeChoiceZPlan = scalarMarkerLazyRangeChoiceZPlan(root);
        int[] eagerZMarkerIndexes = lazyRangeChoiceZPlan == null
                ? markerIndexes : lazyRangeChoiceZPlan.eagerZMarkerIndexes();
        // Locals: 0=this, 1=out, 2=chunk, 3=idx, 4=cellW, 5=cellH,
        // 6=inCellY, 7=inCellX, 8=inCellZ, 9=SoA path,
        // 10/12=inverse cell W/H, 14/16/18=delta X/Y/Z,
        // 20+ = SoA staged/noise arrays used by subset marker interpolation.
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 3);

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellWidth", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 5);

        mv.visitVarInsn(Opcodes.ALOAD, soaLocal);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, INTERPOLATOR_SOA_PATH_INTERNAL,
                "bts$getInverseCellWidth", "()D", true);
        mv.visitVarInsn(Opcodes.DSTORE, 10);
        mv.visitVarInsn(Opcodes.ALOAD, soaLocal);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, INTERPOLATOR_SOA_PATH_INTERNAL,
                "bts$getInverseCellHeight", "()D", true);
        mv.visitVarInsn(Opcodes.DSTORE, 12);

        final int directInterpolatorValueArraySlot = 20;
        int arraySlot = directInterpolatorValueArraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getValueArray", arraySlot);
        int valueXZ00Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getValueXZ00Array", arraySlot);
        int valueXZ10Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getValueXZ10Array", arraySlot);
        int valueXZ01Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getValueXZ01Array", arraySlot);
        int valueXZ11Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getValueXZ11Array", arraySlot);
        int valueZ0Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getValueZ0Array", arraySlot);
        int valueZ1Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getValueZ1Array", arraySlot);
        int noise000Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getNoise000Array", arraySlot);
        int noise100Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getNoise100Array", arraySlot);
        int noise010Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getNoise010Array", arraySlot);
        int noise110Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getNoise110Array", arraySlot);
        int noise001Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getNoise001Array", arraySlot);
        int noise101Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getNoise101Array", arraySlot);
        int noise011Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getNoise011Array", arraySlot);
        int noise111Slot = arraySlot;
        arraySlot = emitSoAArrayLocal(mv, soaLocal, "bts$getNoise111Array", arraySlot);
        int lerpLowTempSlot = arraySlot;
        arraySlot += 2;

        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        Label yLoopHead = new Label();
        Label yLoopExit = new Label();
        mv.visitLabel(yLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitJumpInsn(Opcodes.IFLT, yLoopExit);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitInsn(Opcodes.I2D);
        mv.visitVarInsn(Opcodes.DLOAD, 12);
        mv.visitInsn(Opcodes.DMUL);
        mv.visitVarInsn(Opcodes.DSTORE, 16);
        emitScalarMarkerUpdateYSubset(mv, markerIndexes, 16, lerpLowTempSlot,
                noise000Slot, noise100Slot, noise001Slot, noise101Slot,
                noise010Slot, noise110Slot, noise011Slot, noise111Slot,
                valueXZ00Slot, valueXZ10Slot, valueXZ01Slot, valueXZ11Slot);

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 7);
        Label xLoopHead = new Label();
        Label xLoopExit = new Label();
        mv.visitLabel(xLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, xLoopExit);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitInsn(Opcodes.I2D);
        mv.visitVarInsn(Opcodes.DLOAD, 10);
        mv.visitInsn(Opcodes.DMUL);
        mv.visitVarInsn(Opcodes.DSTORE, 14);
        emitScalarMarkerUpdateXSubset(mv, markerIndexes, 14, lerpLowTempSlot,
                valueXZ00Slot, valueXZ10Slot, valueXZ01Slot, valueXZ11Slot,
                valueZ0Slot, valueZ1Slot);

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 8);
        Label zLoopHead = new Label();
        Label zLoopExit = new Label();
        mv.visitLabel(zLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, zLoopExit);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitInsn(Opcodes.I2D);
        mv.visitVarInsn(Opcodes.DLOAD, 10);
        mv.visitInsn(Opcodes.DMUL);
        mv.visitVarInsn(Opcodes.DSTORE, 18);
        emitScalarMarkerUpdateZSubset(mv, eagerZMarkerIndexes, 18, lerpLowTempSlot,
                valueZ0Slot, valueZ1Slot, directInterpolatorValueArraySlot);

        if (accumulate) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.DALOAD);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
        }
        if (lazyRangeChoiceZPlan != null) {
            emitScalarMarkerLazyRangeChoiceZExpression(mv, classInternalName, lazyRangeChoiceZPlan, helpers,
                    soaLocal, directInterpolatorValueArraySlot, valueZ0Slot, valueZ1Slot,
                    18, lerpLowTempSlot, arraySlot);
        } else if (!emitScalarMarkerSpecializedExpression(mv, classInternalName, root, helpers,
                soaLocal, directInterpolatorValueArraySlot, arraySlot)) {
            emitScalarMarkerGenericExpression(mv, classInternalName, root, helpers,
                    soaLocal, directInterpolatorValueArraySlot, arraySlot);
        }
        if (accumulate) {
            mv.visitInsn(Opcodes.DADD);
        }
        mv.visitInsn(Opcodes.DASTORE);

        mv.visitIincInsn(3, 1);
        mv.visitIincInsn(8, 1);
        mv.visitJumpInsn(Opcodes.GOTO, zLoopHead);
        mv.visitLabel(zLoopExit);

        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(Opcodes.GOTO, xLoopHead);
        mv.visitLabel(xLoopExit);

        mv.visitIincInsn(6, -1);
        mv.visitJumpInsn(Opcodes.GOTO, yLoopHead);
        mv.visitLabel(yLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");
    }

    private static boolean emitScalarMarkerSpecializedExpression(MethodVisitor mv, String classInternalName,
                                                                 IRNode root, HelperRegistry helpers,
                                                                 int soaLocal, int valueArraySlot,
                                                                 int localFloor) {
        ScalarMarkerSqueezeMulPlan directPlan = scalarMarkerSqueezeMulPlan(root);
        if (directPlan != null) {
            emitScalarMarkerSqueezeMul(mv, classInternalName, directPlan, valueArraySlot, localFloor);
            return true;
        }
        if (!(root instanceof IRNode.Bin bin)
                || (bin.op() != IRNode.BinOp.MIN && bin.op() != IRNode.BinOp.MAX)) {
            return false;
        }

        ScalarMarkerSqueezeMulPlan leftPlan = scalarMarkerSqueezeMulPlan(bin.left());
        ScalarMarkerSqueezeMulPlan rightPlan = scalarMarkerSqueezeMulPlan(bin.right());
        int genericLocalFloor = localFloor + 4;
        if (leftPlan != null) {
            emitScalarMarkerSqueezeMul(mv, classInternalName, leftPlan, valueArraySlot, localFloor);
            emitScalarMarkerGenericExpression(mv, classInternalName, bin.right(), helpers,
                    soaLocal, valueArraySlot, genericLocalFloor);
        } else if (rightPlan != null) {
            emitScalarMarkerGenericExpression(mv, classInternalName, bin.left(), helpers,
                    soaLocal, valueArraySlot, genericLocalFloor);
            emitScalarMarkerSqueezeMul(mv, classInternalName, rightPlan, valueArraySlot, localFloor);
        } else {
            return false;
        }

        String methodName = bin.op() == IRNode.BinOp.MIN ? "min" : "max";
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", methodName, "(DD)D", false);
        return true;
    }

    private static void emitScalarMarkerLazyRangeChoiceZExpression(MethodVisitor mv, String classInternalName,
                                                                   ScalarMarkerLazyRangeChoiceZPlan plan,
                                                                   HelperRegistry helpers,
                                                                   int soaLocal, int valueArraySlot,
                                                                   int valueZ0Slot, int valueZ1Slot,
                                                                   int deltaZSlot, int lerpLowTempSlot,
                                                                   int localFloor) {
        if (plan.squeezeOnLeft()) {
            emitScalarMarkerSqueezeMul(mv, classInternalName, plan.squeezePlan(), valueArraySlot, localFloor);
            emitScalarMarkerLazyRangeChoiceZValue(mv, classInternalName, plan.rangeChoice(), helpers,
                    soaLocal, valueArraySlot, valueZ0Slot, valueZ1Slot, deltaZSlot,
                    lerpLowTempSlot, localFloor, plan.lazyOutZMarkerIndexes());
        } else {
            emitScalarMarkerLazyRangeChoiceZValue(mv, classInternalName, plan.rangeChoice(), helpers,
                    soaLocal, valueArraySlot, valueZ0Slot, valueZ1Slot, deltaZSlot,
                    lerpLowTempSlot, localFloor, plan.lazyOutZMarkerIndexes());
            emitScalarMarkerSqueezeMul(mv, classInternalName, plan.squeezePlan(), valueArraySlot, localFloor);
        }
        String methodName = plan.op() == IRNode.BinOp.MIN ? "min" : "max";
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", methodName, "(DD)D", false);
    }

    private static void emitScalarMarkerLazyRangeChoiceZValue(MethodVisitor mv, String classInternalName,
                                                              IRNode.RangeChoice rangeChoice,
                                                              HelperRegistry helpers,
                                                              int soaLocal, int valueArraySlot,
                                                              int valueZ0Slot, int valueZ1Slot,
                                                              int deltaZSlot, int lerpLowTempSlot,
                                                              int localFloor, int[] lazyOutZMarkerIndexes) {
        int compareSlot = localFloor + 4;
        int branchLocalFloor = localFloor + 6;
        emitScalarMarkerGenericExpression(mv, classInternalName, rangeChoice.input(), helpers,
                soaLocal, valueArraySlot, branchLocalFloor);
        mv.visitVarInsn(Opcodes.DSTORE, compareSlot);

        mv.visitVarInsn(Opcodes.DLOAD, compareSlot);
        mv.visitLdcInsn(rangeChoice.min());
        mv.visitInsn(Opcodes.DCMPG);
        Label outOfRange = new Label();
        Label end = new Label();
        mv.visitJumpInsn(Opcodes.IFLT, outOfRange);

        mv.visitVarInsn(Opcodes.DLOAD, compareSlot);
        mv.visitLdcInsn(rangeChoice.max());
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFGE, outOfRange);

        emitScalarMarkerGenericExpression(mv, classInternalName, rangeChoice.whenInRange(), helpers,
                soaLocal, valueArraySlot, branchLocalFloor);
        mv.visitJumpInsn(Opcodes.GOTO, end);

        mv.visitLabel(outOfRange);
        emitScalarMarkerLazyRangeChoiceOutValue(mv, classInternalName, rangeChoice, helpers,
                soaLocal, valueArraySlot, valueZ0Slot, valueZ1Slot, deltaZSlot,
                lerpLowTempSlot, branchLocalFloor, lazyOutZMarkerIndexes);

        mv.visitLabel(end);
    }

    private static void emitScalarMarkerLazyRangeChoiceOutValue(MethodVisitor mv, String classInternalName,
                                                                IRNode.RangeChoice rangeChoice,
                                                                HelperRegistry helpers,
                                                                int soaLocal, int valueArraySlot,
                                                                int valueZ0Slot, int valueZ1Slot,
                                                                int deltaZSlot, int lerpLowTempSlot,
                                                                int localFloor, int[] lazyOutZMarkerIndexes) {
        emitScalarMarkerUpdateZSubset(mv, lazyOutZMarkerIndexes, deltaZSlot, lerpLowTempSlot,
                valueZ0Slot, valueZ1Slot, valueArraySlot);
        emitScalarMarkerGenericExpression(mv, classInternalName, rangeChoice.whenOutOfRange(), helpers,
                soaLocal, valueArraySlot, localFloor);
    }

    private static void emitScalarMarkerValueLoad(MethodVisitor mv, String classInternalName,
                                                  int markerExternIndex, int valueArraySlot) {
        mv.visitVarInsn(Opcodes.ALOAD, valueArraySlot);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName,
                externInterpolatorIndexFieldName(markerExternIndex), "I");
        mv.visitInsn(Opcodes.DALOAD);
    }

    private static void emitScalarMarkerGenericExpression(MethodVisitor mv, String classInternalName,
                                                          IRNode root, HelperRegistry helpers,
                                                          int soaLocal, int valueArraySlot,
                                                          int localFloor) {
        EmitState st = new EmitState(mv, classInternalName, helpers, false,
                CoordinateReusePlan.EMPTY, 2, true, soaLocal, 14, 16, 18, valueArraySlot);
        st.reserveLocalsFrom(localFloor);
        st.emit(root);
    }

    private static void emitScalarMarkerSqueezeMul(MethodVisitor mv, String classInternalName,
                                                   ScalarMarkerSqueezeMulPlan plan,
                                                   int valueArraySlot, int tempSlot) {
        emitScalarMarkerValueLoad(mv, classInternalName, plan.markerExternIndex(), valueArraySlot);
        if (plan.scale() != 1.0D) {
            mv.visitLdcInsn(plan.scale());
            mv.visitInsn(Opcodes.DMUL);
        }
        emitSqueezeFromStack(mv, tempSlot, tempSlot + 2);
    }

    private static void emitSqueezeFromStack(MethodVisitor mv, int inputSlot, int clampedSlot) {
        mv.visitVarInsn(Opcodes.DSTORE, inputSlot);

        Label notBelowMin = new Label();
        Label notAboveMax = new Label();
        Label clamped = new Label();

        mv.visitVarInsn(Opcodes.DLOAD, inputSlot);
        mv.visitLdcInsn(-1.0D);
        mv.visitInsn(Opcodes.DCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, notBelowMin);
        mv.visitLdcInsn(-1.0D);
        mv.visitJumpInsn(Opcodes.GOTO, clamped);

        mv.visitLabel(notBelowMin);
        mv.visitVarInsn(Opcodes.DLOAD, inputSlot);
        mv.visitInsn(Opcodes.DCONST_1);
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFLE, notAboveMax);
        mv.visitInsn(Opcodes.DCONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, clamped);

        mv.visitLabel(notAboveMax);
        mv.visitVarInsn(Opcodes.DLOAD, inputSlot);

        mv.visitLabel(clamped);
        mv.visitVarInsn(Opcodes.DSTORE, clampedSlot);

        mv.visitVarInsn(Opcodes.DLOAD, clampedSlot);
        mv.visitLdcInsn(2.0D);
        mv.visitInsn(Opcodes.DDIV);
        mv.visitVarInsn(Opcodes.DLOAD, clampedSlot);
        mv.visitVarInsn(Opcodes.DLOAD, clampedSlot);
        mv.visitInsn(Opcodes.DMUL);
        mv.visitVarInsn(Opcodes.DLOAD, clampedSlot);
        mv.visitInsn(Opcodes.DMUL);
        mv.visitLdcInsn(24.0D);
        mv.visitInsn(Opcodes.DDIV);
        mv.visitInsn(Opcodes.DSUB);
    }

    private static int emitSoAArrayLocal(MethodVisitor mv, int soaLocal, String methodName, int local) {
        mv.visitVarInsn(Opcodes.ALOAD, soaLocal);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, INTERPOLATOR_SOA_PATH_INTERNAL,
                methodName, INTERPOLATOR_NOISE_ARRAY_DESC, true);
        mv.visitVarInsn(Opcodes.ASTORE, local);
        return local + 1;
    }

    private static void emitScalarMarkerUpdateYSubset(MethodVisitor mv, int[] markerIndexes,
                                                      int deltaYSlot, int lowTempSlot,
                                                      int noise000Slot, int noise100Slot,
                                                      int noise001Slot, int noise101Slot,
                                                      int noise010Slot, int noise110Slot,
                                                      int noise011Slot, int noise111Slot,
                                                      int valueXZ00Slot, int valueXZ10Slot,
                                                      int valueXZ01Slot, int valueXZ11Slot) {
        for (int markerIndex : markerIndexes) {
            emitLerpArrayStore(mv, valueXZ00Slot, markerIndex, noise000Slot, noise010Slot, deltaYSlot, lowTempSlot);
            emitLerpArrayStore(mv, valueXZ10Slot, markerIndex, noise100Slot, noise110Slot, deltaYSlot, lowTempSlot);
            emitLerpArrayStore(mv, valueXZ01Slot, markerIndex, noise001Slot, noise011Slot, deltaYSlot, lowTempSlot);
            emitLerpArrayStore(mv, valueXZ11Slot, markerIndex, noise101Slot, noise111Slot, deltaYSlot, lowTempSlot);
        }
    }

    private static void emitScalarMarkerUpdateXSubset(MethodVisitor mv, int[] markerIndexes,
                                                      int deltaXSlot, int lowTempSlot,
                                                      int valueXZ00Slot, int valueXZ10Slot,
                                                      int valueXZ01Slot, int valueXZ11Slot,
                                                      int valueZ0Slot, int valueZ1Slot) {
        for (int markerIndex : markerIndexes) {
            emitLerpArrayStore(mv, valueZ0Slot, markerIndex, valueXZ00Slot, valueXZ10Slot, deltaXSlot, lowTempSlot);
            emitLerpArrayStore(mv, valueZ1Slot, markerIndex, valueXZ01Slot, valueXZ11Slot, deltaXSlot, lowTempSlot);
        }
    }

    private static void emitScalarMarkerUpdateZSubset(MethodVisitor mv, int[] markerIndexes,
                                                      int deltaZSlot, int lowTempSlot,
                                                      int valueZ0Slot, int valueZ1Slot,
                                                      int valueSlot) {
        for (int markerIndex : markerIndexes) {
            emitLerpArrayStore(mv, valueSlot, markerIndex, valueZ0Slot, valueZ1Slot, deltaZSlot, lowTempSlot);
        }
    }

    private static void emitLerpArrayStore(MethodVisitor mv, int dstSlot, int index,
                                           int lowSlot, int highSlot, int deltaSlot, int lowTempSlot) {
        mv.visitVarInsn(Opcodes.ALOAD, dstSlot);
        ldcIntStatic(mv, index);
        emitLerpValue(mv, index, lowSlot, highSlot, deltaSlot, lowTempSlot);
        mv.visitInsn(Opcodes.DASTORE);
    }

    private static void emitLerpValue(MethodVisitor mv, int index,
                                      int lowSlot, int highSlot, int deltaSlot, int lowTempSlot) {
        mv.visitVarInsn(Opcodes.ALOAD, lowSlot);
        ldcIntStatic(mv, index);
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitVarInsn(Opcodes.DSTORE, lowTempSlot);

        mv.visitVarInsn(Opcodes.DLOAD, lowTempSlot);
        mv.visitVarInsn(Opcodes.DLOAD, deltaSlot);
        mv.visitVarInsn(Opcodes.ALOAD, highSlot);
        ldcIntStatic(mv, index);
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitVarInsn(Opcodes.DLOAD, lowTempSlot);
        mv.visitInsn(Opcodes.DSUB);
        mv.visitInsn(Opcodes.DMUL);
        mv.visitInsn(Opcodes.DADD);
    }

    private static boolean emitCellFillAddScalarOverrideIfPossible(ClassWriter cw, String classInternalName,
                                                                   IRNode root, HelperRegistry helpers) {
        CellFillAddLatticePlan plan = analyzeCellFillAddLattice(root).orElse(null);
        if (plan == null) {
            return false;
        }

        emitLatticePrecomputeHelper(cw, classInternalName, plan.latticePlan(), helpers, CELL_ADD_LATTICE_XZ_NAME);
        emitLatticeInnerHelper(cw, classInternalName, plan.latticeRoot(), plan.latticePlan(), helpers,
                CELL_ADD_LATTICE_INNER_XZ_NAME);
        emitCellFillComputeHelper(cw, classInternalName, plan.residualRoot(), helpers, CELL_ADD_RESIDUAL_NAME);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "dfc$fillCell", CELL_FILL_DESC, null, null);
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellWidth", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 7);
        Label xLoopHead = new Label();
        Label xLoopExit = new Label();
        mv.visitLabel(xLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, xLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 8);
        Label zLoopHead = new Label();
        Label zLoopExit = new Label();
        mv.visitLabel(zLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, zLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInvokeDynamicInsn(CELL_ADD_LATTICE_XZ_NAME, HELPER_DESC, HELPER_BSM);
        mv.visitVarInsn(Opcodes.DSTORE, 11);

        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        Label yLoopHead = new Label();
        Label yLoopExit = new Label();
        mv.visitLabel(yLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitJumpInsn(Opcodes.IFLT, yLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");

        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 10);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 10);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.DLOAD, 11);
        mv.visitInvokeDynamicInsn(CELL_ADD_LATTICE_INNER_XZ_NAME, LATTICE_INNER_DESC, HELPER_BSM);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInvokeDynamicInsn(CELL_ADD_RESIDUAL_NAME, HELPER_DESC, HELPER_BSM);
        mv.visitInsn(Opcodes.DADD);
        mv.visitInsn(Opcodes.DASTORE);

        mv.visitIincInsn(6, -1);
        mv.visitJumpInsn(Opcodes.GOTO, yLoopHead);
        mv.visitLabel(yLoopExit);

        mv.visitIincInsn(8, 1);
        mv.visitJumpInsn(Opcodes.GOTO, zLoopHead);
        mv.visitLabel(zLoopExit);

        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(Opcodes.GOTO, xLoopHead);
        mv.visitLabel(xLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor acc = cw.visitMethod(Opcodes.ACC_PUBLIC, DFC_ACCUMULATE_CELL_NAME, CELL_FILL_DESC, null, null);
        acc.visitCode();

        acc.visitVarInsn(Opcodes.ALOAD, 2);
        acc.visitVarInsn(Opcodes.ASTORE, 3);
        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellWidth", "I");
        acc.visitVarInsn(Opcodes.ISTORE, 4);
        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        acc.visitVarInsn(Opcodes.ISTORE, 5);
        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitInsn(Opcodes.ICONST_0);
        acc.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        acc.visitInsn(Opcodes.ICONST_0);
        acc.visitVarInsn(Opcodes.ISTORE, 7);
        Label accXLoopHead = new Label();
        Label accXLoopExit = new Label();
        acc.visitLabel(accXLoopHead);
        acc.visitVarInsn(Opcodes.ILOAD, 7);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitJumpInsn(Opcodes.IF_ICMPGE, accXLoopExit);

        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitVarInsn(Opcodes.ILOAD, 7);
        acc.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");

        acc.visitInsn(Opcodes.ICONST_0);
        acc.visitVarInsn(Opcodes.ISTORE, 8);
        Label accZLoopHead = new Label();
        Label accZLoopExit = new Label();
        acc.visitLabel(accZLoopHead);
        acc.visitVarInsn(Opcodes.ILOAD, 8);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitJumpInsn(Opcodes.IF_ICMPGE, accZLoopExit);

        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitVarInsn(Opcodes.ILOAD, 8);
        acc.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");

        acc.visitVarInsn(Opcodes.ALOAD, 0);
        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitInvokeDynamicInsn(CELL_ADD_LATTICE_XZ_NAME, HELPER_DESC, HELPER_BSM);
        acc.visitVarInsn(Opcodes.DSTORE, 11);

        acc.visitVarInsn(Opcodes.ILOAD, 5);
        acc.visitInsn(Opcodes.ICONST_1);
        acc.visitInsn(Opcodes.ISUB);
        acc.visitVarInsn(Opcodes.ISTORE, 6);
        Label accYLoopHead = new Label();
        Label accYLoopExit = new Label();
        acc.visitLabel(accYLoopHead);
        acc.visitVarInsn(Opcodes.ILOAD, 6);
        acc.visitJumpInsn(Opcodes.IFLT, accYLoopExit);

        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitVarInsn(Opcodes.ILOAD, 6);
        acc.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");

        acc.visitVarInsn(Opcodes.ILOAD, 5);
        acc.visitInsn(Opcodes.ICONST_1);
        acc.visitInsn(Opcodes.ISUB);
        acc.visitVarInsn(Opcodes.ILOAD, 6);
        acc.visitInsn(Opcodes.ISUB);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitInsn(Opcodes.IMUL);
        acc.visitInsn(Opcodes.IMUL);
        acc.visitVarInsn(Opcodes.ILOAD, 7);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitInsn(Opcodes.IMUL);
        acc.visitInsn(Opcodes.IADD);
        acc.visitVarInsn(Opcodes.ILOAD, 8);
        acc.visitInsn(Opcodes.IADD);
        acc.visitVarInsn(Opcodes.ISTORE, 10);

        acc.visitVarInsn(Opcodes.ALOAD, 0);
        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitVarInsn(Opcodes.DLOAD, 11);
        acc.visitInvokeDynamicInsn(CELL_ADD_LATTICE_INNER_XZ_NAME, LATTICE_INNER_DESC, HELPER_BSM);
        acc.visitVarInsn(Opcodes.ALOAD, 0);
        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitInvokeDynamicInsn(CELL_ADD_RESIDUAL_NAME, HELPER_DESC, HELPER_BSM);
        acc.visitInsn(Opcodes.DADD);
        acc.visitVarInsn(Opcodes.DSTORE, 13);

        emitArrayAccumulateFromTemp(acc, 13, 10);

        acc.visitIincInsn(6, -1);
        acc.visitJumpInsn(Opcodes.GOTO, accYLoopHead);
        acc.visitLabel(accYLoopExit);

        acc.visitIincInsn(8, 1);
        acc.visitJumpInsn(Opcodes.GOTO, accZLoopHead);
        acc.visitLabel(accZLoopExit);

        acc.visitIincInsn(7, 1);
        acc.visitJumpInsn(Opcodes.GOTO, accXLoopHead);
        acc.visitLabel(accXLoopExit);

        acc.visitVarInsn(Opcodes.ALOAD, 3);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitVarInsn(Opcodes.ILOAD, 4);
        acc.visitInsn(Opcodes.IMUL);
        acc.visitVarInsn(Opcodes.ILOAD, 5);
        acc.visitInsn(Opcodes.IMUL);
        acc.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
        acc.visitInsn(Opcodes.RETURN);
        acc.visitMaxs(0, 0);
        acc.visitEnd();
        return true;
    }

    private static boolean emitCellFillAddExternOverrideIfPossible(ClassWriter cw, String classInternalName,
                                                                   IRNode root, HelperRegistry helpers,
                                                                   ConstantPool pool) {
        if (!(root instanceof IRNode.Bin bin) || bin.op() != IRNode.BinOp.ADD) {
            return false;
        }

        CellFillAddExternPlan leftPlan = analyzeCellFillAddExternSide(
                bin.left(), bin.right(), CELL_ADD_EXTERN_RIGHT_RESIDUAL_NAME).orElse(null);
        CellFillAddExternPlan rightPlan = analyzeCellFillAddExternSide(
                bin.right(), bin.left(), CELL_ADD_EXTERN_LEFT_RESIDUAL_NAME).orElse(null);
        if (leftPlan == null && rightPlan == null) {
            return false;
        }

        if (leftPlan != null && leftPlan.residualHelperName() != null) {
            emitCellFillComputeHelper(cw, classInternalName, leftPlan.residualRoot(), helpers, leftPlan.residualHelperName());
        }
        if (rightPlan != null && rightPlan.residualHelperName() != null) {
            emitCellFillComputeHelper(cw, classInternalName, rightPlan.residualRoot(), helpers, rightPlan.residualHelperName());
        }

        if (leftPlan != null) {
            emitCellFillAddExternHelperMethod(cw, classInternalName, leftPlan, pool,
                    CELL_FILL_TRY_ADD_EXTERN_LEFT_NAME, false);
            emitCellFillAddExternHelperMethod(cw, classInternalName, leftPlan, pool,
                    CELL_ACCUMULATE_TRY_ADD_EXTERN_LEFT_NAME, true);
        }
        if (rightPlan != null) {
            emitCellFillAddExternHelperMethod(cw, classInternalName, rightPlan, pool,
                    CELL_FILL_TRY_ADD_EXTERN_RIGHT_NAME, false);
            emitCellFillAddExternHelperMethod(cw, classInternalName, rightPlan, pool,
                    CELL_ACCUMULATE_TRY_ADD_EXTERN_RIGHT_NAME, true);
        }

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "dfc$fillCell", CELL_FILL_DESC, null, null);
        mv.visitCode();
        if (leftPlan != null) {
            Label next = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, classInternalName,
                    CELL_FILL_TRY_ADD_EXTERN_LEFT_NAME, CELL_FILL_TRY_DESC, false);
            mv.visitJumpInsn(Opcodes.IFEQ, next);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(next);
        }
        if (rightPlan != null) {
            Label next = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, classInternalName,
                    CELL_FILL_TRY_ADD_EXTERN_RIGHT_NAME, CELL_FILL_TRY_DESC, false);
            mv.visitJumpInsn(Opcodes.IFEQ, next);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(next);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, "dfc$fillCell", CELL_FILL_DESC, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor acc = cw.visitMethod(Opcodes.ACC_PUBLIC, DFC_ACCUMULATE_CELL_NAME, CELL_FILL_DESC, null, null);
        acc.visitCode();
        if (leftPlan != null) {
            Label next = new Label();
            acc.visitVarInsn(Opcodes.ALOAD, 0);
            acc.visitVarInsn(Opcodes.ALOAD, 1);
            acc.visitVarInsn(Opcodes.ALOAD, 2);
            acc.visitMethodInsn(Opcodes.INVOKESPECIAL, classInternalName,
                    CELL_ACCUMULATE_TRY_ADD_EXTERN_LEFT_NAME, CELL_FILL_TRY_DESC, false);
            acc.visitJumpInsn(Opcodes.IFEQ, next);
            acc.visitInsn(Opcodes.RETURN);
            acc.visitLabel(next);
        }
        if (rightPlan != null) {
            Label next = new Label();
            acc.visitVarInsn(Opcodes.ALOAD, 0);
            acc.visitVarInsn(Opcodes.ALOAD, 1);
            acc.visitVarInsn(Opcodes.ALOAD, 2);
            acc.visitMethodInsn(Opcodes.INVOKESPECIAL, classInternalName,
                    CELL_ACCUMULATE_TRY_ADD_EXTERN_RIGHT_NAME, CELL_FILL_TRY_DESC, false);
            acc.visitJumpInsn(Opcodes.IFEQ, next);
            acc.visitInsn(Opcodes.RETURN);
            acc.visitLabel(next);
        }
        acc.visitVarInsn(Opcodes.ALOAD, 0);
        acc.visitVarInsn(Opcodes.ALOAD, 1);
        acc.visitVarInsn(Opcodes.ALOAD, 2);
        acc.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, DFC_ACCUMULATE_CELL_NAME, CELL_FILL_DESC, false);
        acc.visitInsn(Opcodes.RETURN);
        acc.visitMaxs(0, 0);
        acc.visitEnd();
        return true;
    }

    private static boolean emitCellFillAddBeardifierOverrideIfPossible(ClassWriter cw, String classInternalName,
                                                                       IRNode root, HelperRegistry helpers,
                                                                       ConstantPool pool) {
        if (!(root instanceof IRNode.Bin bin) || bin.op() != IRNode.BinOp.ADD) {
            return false;
        }

        CellFillAddExternPlan leftPlan = analyzeCellFillAddBeardifierSide(
                bin.left(), bin.right(), CELL_ADD_EXTERN_RIGHT_RESIDUAL_NAME).orElse(null);
        CellFillAddExternPlan rightPlan = analyzeCellFillAddBeardifierSide(
                bin.right(), bin.left(), CELL_ADD_EXTERN_LEFT_RESIDUAL_NAME).orElse(null);
        CellFillAddExternPlan plan = leftPlan != null ? leftPlan : rightPlan;
        if (plan == null) {
            return false;
        }

        emitCellFillComputeHelper(cw, classInternalName, plan.residualRoot(), helpers, plan.residualHelperName());

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "dfc$fillCell", CELL_FILL_DESC, null, null);
        mv.visitCode();
        Label fallback = new Label();
        emitCellFillAddExternCase(mv, classInternalName, plan, fallback, false, pool);
        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, "dfc$fillCell", CELL_FILL_DESC, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor acc = cw.visitMethod(Opcodes.ACC_PUBLIC, DFC_ACCUMULATE_CELL_NAME, CELL_FILL_DESC, null, null);
        acc.visitCode();
        Label accFallback = new Label();
        emitCellFillAddExternCase(acc, classInternalName, plan, accFallback, true, pool);
        acc.visitLabel(accFallback);
        acc.visitVarInsn(Opcodes.ALOAD, 0);
        acc.visitVarInsn(Opcodes.ALOAD, 1);
        acc.visitVarInsn(Opcodes.ALOAD, 2);
        acc.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, DFC_ACCUMULATE_CELL_NAME, CELL_FILL_DESC, false);
        acc.visitInsn(Opcodes.RETURN);
        acc.visitMaxs(0, 0);
        acc.visitEnd();
        return true;
    }

    private static Optional<CellFillAddExternPlan> analyzeCellFillAddBeardifierSide(IRNode beardifierRoot,
                                                                                    IRNode residualRoot,
                                                                                    String residualHelperName) {
        if (!(beardifierRoot instanceof IRNode.Beardifier)) {
            return Optional.empty();
        }
        return analyzeCellFillAddExternSide(beardifierRoot, residualRoot, residualHelperName);
    }

    private static void emitCellFillAddExternHelperMethod(ClassWriter cw, String classInternalName,
                                                          CellFillAddExternPlan plan, ConstantPool pool,
                                                          String methodName, boolean accumulatePrimary) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, methodName, CELL_FILL_TRY_DESC, null, null);
        mv.visitCode();

        Label noPrimaryFastPath = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(plan.externIndex()), DENSITY_FUNCTION_DESC);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, DFC_CELL_FILL_ACCESS_INTERNAL);
        mv.visitJumpInsn(Opcodes.IFEQ, noPrimaryFastPath);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(plan.externIndex()), DENSITY_FUNCTION_DESC);
        mv.visitTypeInsn(Opcodes.CHECKCAST, DFC_CELL_FILL_ACCESS_INTERNAL);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DFC_CELL_FILL_ACCESS_INTERNAL,
                accumulatePrimary ? DFC_ACCUMULATE_CELL_NAME : "dfc$fillCell",
                CELL_FILL_DESC, true);

        if (plan.residualExternIndex() >= 0) {
            Label scalarResidual = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(plan.residualExternIndex()), DENSITY_FUNCTION_DESC);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, DFC_CELL_FILL_ACCESS_INTERNAL);
            mv.visitJumpInsn(Opcodes.IFEQ, scalarResidual);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(plan.residualExternIndex()), DENSITY_FUNCTION_DESC);
            mv.visitTypeInsn(Opcodes.CHECKCAST, DFC_CELL_FILL_ACCESS_INTERNAL);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_CELL_FILL_STATS_INTERNAL, "recordCellExternAccumulate", "()V", false);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DFC_CELL_FILL_ACCESS_INTERNAL, DFC_ACCUMULATE_CELL_NAME,
                    CELL_FILL_DESC, true);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);

            mv.visitLabel(scalarResidual);
            if (DfcCellFillStats.RESIDUAL_CLASS_DEBUG_ENABLED) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(plan.residualExternIndex()), DENSITY_FUNCTION_DESC);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_CELL_FILL_STATS_INTERNAL,
                        "recordCellExternScalarResidualClass", "(Ljava/lang/Object;)V", false);
            }
        }

        if (plan.residualDirectExtern() != null) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_CELL_FILL_STATS_INTERNAL, "recordCellExternScalarResidual", "()V", false);
            emitCellFillAddDirectExternResidualLoop(mv, classInternalName, pool, plan.residualDirectExtern());
        } else if (plan.residualHelperName() != null) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_CELL_FILL_STATS_INTERNAL, "recordCellExternScalarResidual", "()V", false);
            emitCellFillAddResidualLoop(mv, plan.residualHelperName());
        }
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(noPrimaryFastPath);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitCellFillAddExternCase(MethodVisitor mv, String classInternalName,
                                                  CellFillAddExternPlan plan, Label fallback,
                                                  boolean accumulatePrimary,
                                                  ConstantPool pool) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (!COMPILED_BASE_INTERNAL.equals(classInternalName)) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
        }
        mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(plan.externIndex()), DENSITY_FUNCTION_DESC);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, DFC_CELL_FILL_ACCESS_INTERNAL);
        mv.visitJumpInsn(Opcodes.IFEQ, fallback);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (!COMPILED_BASE_INTERNAL.equals(classInternalName)) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
        }
        mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(plan.externIndex()), DENSITY_FUNCTION_DESC);
        mv.visitTypeInsn(Opcodes.CHECKCAST, DFC_CELL_FILL_ACCESS_INTERNAL);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DFC_CELL_FILL_ACCESS_INTERNAL,
                accumulatePrimary ? DFC_ACCUMULATE_CELL_NAME : "dfc$fillCell",
                CELL_FILL_DESC, true);

        if (plan.residualExternIndex() >= 0) {
            Label scalarResidual = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            if (!COMPILED_BASE_INTERNAL.equals(classInternalName)) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
            }
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(plan.residualExternIndex()), DENSITY_FUNCTION_DESC);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, DFC_CELL_FILL_ACCESS_INTERNAL);
            mv.visitJumpInsn(Opcodes.IFEQ, scalarResidual);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            if (!COMPILED_BASE_INTERNAL.equals(classInternalName)) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
            }
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(plan.residualExternIndex()), DENSITY_FUNCTION_DESC);
            mv.visitTypeInsn(Opcodes.CHECKCAST, DFC_CELL_FILL_ACCESS_INTERNAL);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_CELL_FILL_STATS_INTERNAL, "recordCellExternAccumulate", "()V", false);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DFC_CELL_FILL_ACCESS_INTERNAL, DFC_ACCUMULATE_CELL_NAME,
                    CELL_FILL_DESC, true);
            mv.visitInsn(Opcodes.RETURN);

            mv.visitLabel(scalarResidual);
            if (DfcCellFillStats.RESIDUAL_CLASS_DEBUG_ENABLED) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                if (!COMPILED_BASE_INTERNAL.equals(classInternalName)) {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
                }
                mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(plan.residualExternIndex()), DENSITY_FUNCTION_DESC);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_CELL_FILL_STATS_INTERNAL,
                        "recordCellExternScalarResidualClass", "(Ljava/lang/Object;)V", false);
            }
        }

        if (plan.residualDirectExtern() != null) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_CELL_FILL_STATS_INTERNAL, "recordCellExternScalarResidual", "()V", false);
            emitCellFillAddDirectExternResidualLoop(mv, classInternalName, pool, plan.residualDirectExtern());
        } else if (plan.residualHelperName() != null) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_CELL_FILL_STATS_INTERNAL, "recordCellExternScalarResidual", "()V", false);
            emitCellFillAddResidualLoop(mv, plan.residualHelperName());
        }
        mv.visitInsn(Opcodes.RETURN);
    }

    private static void emitCellFillAddResidualLoop(MethodVisitor mv, String residualHelperName) {
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellWidth", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 7);
        Label xLoopHead = new Label();
        Label xLoopExit = new Label();
        mv.visitLabel(xLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, xLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 8);
        Label zLoopHead = new Label();
        Label zLoopExit = new Label();
        mv.visitLabel(zLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, zLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");

        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 9);
        Label yLoopHead = new Label();
        Label yLoopExit = new Label();
        mv.visitLabel(yLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitJumpInsn(Opcodes.IFLT, yLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");

        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 10);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 10);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 10);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 10);
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInvokeDynamicInsn(residualHelperName, HELPER_DESC, HELPER_BSM);
        mv.visitInsn(Opcodes.DADD);
        mv.visitInsn(Opcodes.DASTORE);

        mv.visitIincInsn(9, -1);
        mv.visitJumpInsn(Opcodes.GOTO, yLoopHead);
        mv.visitLabel(yLoopExit);

        mv.visitIincInsn(8, 1);
        mv.visitJumpInsn(Opcodes.GOTO, zLoopHead);
        mv.visitLabel(zLoopExit);

        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(Opcodes.GOTO, xLoopHead);
        mv.visitLabel(xLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
    }

    private static void emitCellFillAddDirectExternResidualLoop(MethodVisitor mv, String classInternalName,
                                                                ConstantPool pool, DirectExternResidual residual) {
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellWidth", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 7);
        Label xLoopHead = new Label();
        Label xLoopExit = new Label();
        mv.visitLabel(xLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, xLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 8);
        Label zLoopHead = new Label();
        Label zLoopExit = new Label();
        mv.visitLabel(zLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, zLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");

        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 9);
        Label yLoopHead = new Label();
        Label yLoopExit = new Label();
        mv.visitLabel(yLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitJumpInsn(Opcodes.IFLT, yLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");

        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 10);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 10);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        emitDirectExternCompute(mv, classInternalName, pool, residual, 3);
        mv.visitVarInsn(Opcodes.DSTORE, 13);
        emitArrayAccumulateFromTemp(mv, 13, 10);

        mv.visitIincInsn(9, -1);
        mv.visitJumpInsn(Opcodes.GOTO, yLoopHead);
        mv.visitLabel(yLoopExit);

        mv.visitIincInsn(8, 1);
        mv.visitJumpInsn(Opcodes.GOTO, zLoopHead);
        mv.visitLabel(zLoopExit);

        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(Opcodes.GOTO, xLoopHead);
        mv.visitLabel(xLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
    }

    private static void emitDirectExternCompute(MethodVisitor mv, String classInternalName,
                                                ConstantPool pool, DirectExternResidual residual,
                                                int contextLocal) {
        if (residual.marker()) {
            emitMarkerSlotCompute(mv, classInternalName, pool, residual.externIndex(), contextLocal);
            return;
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (!COMPILED_BASE_INTERNAL.equals(classInternalName)) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
        }
        mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(residual.externIndex()), DENSITY_FUNCTION_DESC);
        mv.visitVarInsn(Opcodes.ALOAD, contextLocal);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DENSITY_FUNCTION_INTERNAL,
                "compute", "(L" + FUNCTION_CONTEXT_INTERNAL + ";)D", true);
    }

    private static void emitExtractedSlotHelperCompute(MethodVisitor mv, String classInternalName,
                                                       HelperRegistry helpers, IRNode node) {
        int idx = helpers.indexOf(node);
        if (INDY_HELPERS_ENABLED) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitInvokeDynamicInsn(helperName(idx), HELPER_DESC, HELPER_BSM);
            return;
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL,
                "helperHandles", METHOD_HANDLE_ARRAY_DESC);
        ldcIntStatic(mv, idx);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_INTERNAL,
                "invokeExact", HELPER_DESC, false);
    }

    private static void emitArrayAccumulateFromTemp(MethodVisitor mv, int tempLocal, int indexLocal) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, indexLocal);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, indexLocal);
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitVarInsn(Opcodes.DLOAD, tempLocal);
        mv.visitInsn(Opcodes.DADD);
        mv.visitInsn(Opcodes.DASTORE);
    }

    private record CellFillAddLatticePlan(IRNode latticeRoot, IRNode residualRoot,
                                          CellLatticeOption.LatticePlan latticePlan) {
    }

    private record CellFillAddExternPlan(int externIndex, IRNode residualRoot,
                                         int residualExternIndex, DirectExternResidual residualDirectExtern,
                                         String residualHelperName) {
    }

    private record DirectExternResidual(int externIndex, boolean marker) {
    }

    private static Optional<CellFillAddLatticePlan> analyzeCellFillAddLattice(IRNode root) {
        if (!(root instanceof IRNode.Bin bin) || bin.op() != IRNode.BinOp.ADD) {
            return Optional.empty();
        }
        CellFillAddLatticePlan left = analyzeCellFillAddLatticeSide(bin.left(), bin.right()).orElse(null);
        CellFillAddLatticePlan right = analyzeCellFillAddLatticeSide(bin.right(), bin.left()).orElse(null);
        if (left == null) return Optional.ofNullable(right);
        if (right == null) return Optional.of(left);
        return Optional.of(left.latticePlan().hoistedNodeCount() >= right.latticePlan().hoistedNodeCount()
                ? left : right);
    }

    private static Optional<CellFillAddLatticePlan> analyzeCellFillAddLatticeSide(IRNode latticeRoot,
                                                                                  IRNode residualRoot) {
        CellLatticeOption.LatticePlan plan = CellLatticeOption.analyze(latticeRoot).orElse(null);
        if (plan == null || plan.hoistAxis() != CellLatticeOption.Axis.XZ_ONLY) {
            return Optional.empty();
        }
        return Optional.of(new CellFillAddLatticePlan(latticeRoot, residualRoot, plan));
    }

    private static Optional<CellFillAddExternPlan> analyzeCellFillAddExternSide(IRNode externRoot,
                                                                                IRNode residualRoot,
                                                                                String residualHelperName) {
        int externIndex = cellFillExternIndex(externRoot);
        if (externIndex < 0) {
            return Optional.empty();
        }
        int residualExternIndex = cellFillExternIndex(residualRoot);
        DirectExternResidual residualDirectExtern = CELL_FILL_DIRECT_EXTERN_RESIDUAL_ENABLED
                ? directExternResidual(residualRoot)
                : null;
        return Optional.of(new CellFillAddExternPlan(
                externIndex,
                residualRoot,
                residualExternIndex,
                residualDirectExtern,
                residualHelperName));
    }

    private static int cellFillExternIndex(IRNode node) {
        return switch (node) {
            case IRNode.Invoke iv -> iv.externIndex();
            case IRNode.Marker m -> m.externIndex();
            case IRNode.Beardifier b -> b.externIndex();
            case IRNode.EndIslands e -> e.externIndex();
            default -> -1;
        };
    }

    private static DirectExternResidual directExternResidual(IRNode node) {
        return switch (node) {
            case IRNode.Invoke iv -> new DirectExternResidual(iv.externIndex(), false);
            case IRNode.Marker m -> new DirectExternResidual(m.externIndex(), true);
            case IRNode.Beardifier b -> new DirectExternResidual(b.externIndex(), false);
            case IRNode.EndIslands e -> new DirectExternResidual(e.externIndex(), false);
            default -> null;
        };
    }

    /**
     * Fast path for {@link dev.sixik.generator_accelerator.common.noise.NoiseChunkSliceProvider}.
     * The provider is a fixed X/Z lattice column used by {@code NoiseChunk.fillSlice};
     * output index is the noise-cell Y index, not an in-cell block index.
     */
    private static void emitLatticeFillArraySliceFastPath(MethodVisitor mv,
                                                          CellLatticeOption.Axis hoistAxis,
                                                          Label fallback) {
        Label notSlice = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, DFC_NOISE_CHUNK_SLICE_ACCESS_INTERNAL);
        mv.visitJumpInsn(Opcodes.IFEQ, notSlice);

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, DFC_NOISE_CHUNK_SLICE_ACCESS_INTERNAL);
        mv.visitVarInsn(Opcodes.ASTORE, 20);

        mv.visitVarInsn(Opcodes.ALOAD, 20);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DFC_NOISE_CHUNK_SLICE_ACCESS_INTERNAL,
                "noiseChunk", "()" + NOISE_CHUNK_DESC, true);
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        mv.visitVarInsn(Opcodes.ALOAD, 20);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DFC_NOISE_CHUNK_SLICE_ACCESS_INTERNAL,
                "sliceSizeY", "()I", true);
        mv.visitVarInsn(Opcodes.ISTORE, 4);

        boolean xzHoist = hoistAxis == CellLatticeOption.Axis.XZ_ONLY;
        String innerName = xzHoist ? LATTICE_INNER_XZ_NAME : LATTICE_INNER_NAME;

        if (xzHoist) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitInvokeDynamicInsn(LATTICE_XZ_NAME, HELPER_DESC, HELPER_BSM);
            mv.visitVarInsn(Opcodes.DSTORE, 11);
        }

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        Label loopHead = new Label();
        Label loopExit = new Label();
        mv.visitLabel(loopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellNoiseMinY", "I");
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        mv.visitInsn(Opcodes.IMUL);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "cellStartBlockY", "I");

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "interpolationCounter", "J");
        mv.visitInsn(Opcodes.LCONST_1);
        mv.visitInsn(Opcodes.LADD);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "interpolationCounter", "J");

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        if (!xzHoist) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitInvokeDynamicInsn(LATTICE_Y_NAME, HELPER_DESC, HELPER_BSM);
            mv.visitVarInsn(Opcodes.DSTORE, 11);
        }

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.DLOAD, 11);
        mv.visitInvokeDynamicInsn(innerName, LATTICE_INNER_DESC, HELPER_BSM);
        mv.visitInsn(Opcodes.DASTORE);

        mv.visitIincInsn(5, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopHead);
        mv.visitLabel(loopExit);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(notSlice);
        mv.visitJumpInsn(Opcodes.GOTO, fallback);
    }

    private static void emitSliceRowContext(MethodVisitor mv, int rowLocal) {
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, rowLocal);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellNoiseMinY", "I");
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        mv.visitInsn(Opcodes.IMUL);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "cellStartBlockY", "I");

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "interpolationCounter", "J");
        mv.visitInsn(Opcodes.LCONST_1);
        mv.visitInsn(Opcodes.LADD);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "interpolationCounter", "J");

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, rowLocal);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
    }

    /**
     * XZ-hoist scalar fill: (x,z) outer, {@link #LATTICE_XZ_NAME} per cell, y inner with vanilla index order.
     * Locals: 3=nc,4=cellW,5=cellH,7=xi,8=zi,6=yi,10=idx,11=xzPre (double uses locals 11-12),1=values,0=self.
     */
    private static void emitLatticeFillArrayScalarXZHoistLoops(MethodVisitor mv, String innerIndyName) {
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 7);
        Label xLoopHead = new Label();
        Label xLoopExit = new Label();
        mv.visitLabel(xLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, xLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 8);
        Label zLoopHead = new Label();
        Label zLoopExit = new Label();
        mv.visitLabel(zLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, zLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInvokeDynamicInsn(LATTICE_XZ_NAME, HELPER_DESC, HELPER_BSM);
        mv.visitVarInsn(Opcodes.DSTORE, 11);

        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        Label yLoopHead = new Label();
        Label yLoopExit = new Label();
        mv.visitLabel(yLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitJumpInsn(Opcodes.IFLT, yLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");

        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 10);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 10);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.DLOAD, 11);
        mv.visitInvokeDynamicInsn(innerIndyName, LATTICE_INNER_DESC, HELPER_BSM);
        mv.visitInsn(Opcodes.DASTORE);

        mv.visitIincInsn(6, -1);
        mv.visitJumpInsn(Opcodes.GOTO, yLoopHead);
        mv.visitLabel(yLoopExit);

        mv.visitIincInsn(8, 1);
        mv.visitJumpInsn(Opcodes.GOTO, zLoopHead);
        mv.visitLabel(zLoopExit);

        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(Opcodes.GOTO, xLoopHead);
        mv.visitLabel(xLoopExit);
    }

    /** Scalar (x,z) loops: locals 3=nc,4=cellW,7=xi,8=zi,11=yPre (double uses locals 11-12),1=values. */
    private static void emitLatticeFillArrayInnerScalarXZ(MethodVisitor mv, String innerIndyName) {
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 7);
        Label xLoopHead = new Label();
        Label xLoopExit = new Label();
        mv.visitLabel(xLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, xLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 8);
        Label zLoopHead = new Label();
        Label zLoopExit = new Label();
        mv.visitLabel(zLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, zLoopExit);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
        mv.visitInsn(Opcodes.DUP_X1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.DLOAD, 11);
        mv.visitInvokeDynamicInsn(innerIndyName, LATTICE_INNER_DESC, HELPER_BSM);
        mv.visitInsn(Opcodes.DASTORE);

        mv.visitIincInsn(8, 1);
        mv.visitJumpInsn(Opcodes.GOTO, zLoopHead);
        mv.visitLabel(zLoopExit);

        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(Opcodes.GOTO, xLoopHead);
        mv.visitLabel(xLoopExit);
    }

    /** Selected {@code blockX/Y/Z()} ctx accessors -> fixed slots 2/3/4 (int). */
    private static void emitCoordPrologue(MethodVisitor mv, CoordinateSlotUse use) {
        if (use.blockX()) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT_INTERNAL, "blockX", "()I", true);
            mv.visitVarInsn(Opcodes.ISTORE, 2);
        }
        if (use.blockY()) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT_INTERNAL, "blockY", "()I", true);
            mv.visitVarInsn(Opcodes.ISTORE, 3);
        }
        if (use.blockZ()) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT_INTERNAL, "blockZ", "()I", true);
            mv.visitVarInsn(Opcodes.ISTORE, 4);
        }
    }

    /**
     * Conservative per-method analysis of which fixed coordinate locals the bytecode emitter will load.
     * Helper calls and opaque extern invocations receive ctx but do not read local slots 2/3/4 here.
     */
    private record CoordinateSlotUse(boolean blockX, boolean blockY, boolean blockZ) {
        static final CoordinateSlotUse NONE = new CoordinateSlotUse(false, false, false);
        static final CoordinateSlotUse ALL = new CoordinateSlotUse(true, true, true);

        static CoordinateSlotUse analyze(IRNode root, Set<IRNode> extracted, boolean forceInlineRoot) {
            return analyze(root, extracted, forceInlineRoot, Collections.emptySet());
        }

        static CoordinateSlotUse analyze(IRNode root, Set<IRNode> extracted, boolean forceInlineRoot,
                                         Set<IRNode> preinstalledSpills) {
            if (root == null) {
                return ALL;
            }
            Analyzer analyzer = new Analyzer(extracted, preinstalledSpills);
            return analyzer.node(root, forceInlineRoot);
        }

        static CoordinateSlotUse analyzeCoordinates(Set<IRNode> extracted, IRNode x, IRNode y, IRNode z) {
            Analyzer analyzer = new Analyzer(extracted, Collections.emptySet());
            return analyzer.node(x, false).plus(analyzer.node(y, false)).plus(analyzer.node(z, false));
        }

        static Set<IRNode> singletonIdentitySet(IRNode node) {
            Set<IRNode> out = Collections.newSetFromMap(new IdentityHashMap<>());
            out.add(node);
            return out;
        }

        CoordinateSlotUse plus(CoordinateSlotUse other) {
            return new CoordinateSlotUse(blockX || other.blockX, blockY || other.blockY, blockZ || other.blockZ);
        }

        private static final class Analyzer {
            private final Set<IRNode> extracted;
            private final Set<IRNode> preinstalledSpills;
            private final IdentityHashMap<IRNode, CoordinateSlotUse> normalMemo = new IdentityHashMap<>();
            private final IdentityHashMap<IRNode, CoordinateSlotUse> forcedMemo = new IdentityHashMap<>();

            Analyzer(Set<IRNode> extracted, Set<IRNode> preinstalledSpills) {
                this.extracted = extracted == null ? Collections.emptySet() : extracted;
                this.preinstalledSpills = preinstalledSpills == null ? Collections.emptySet() : preinstalledSpills;
            }

            CoordinateSlotUse node(IRNode n, boolean forceInline) {
                if (n == null) {
                    return ALL;
                }
                if (preinstalledSpills.contains(n)) {
                    return NONE;
                }
                if (!forceInline && extracted.contains(n)) {
                    return NONE;
                }

                IdentityHashMap<IRNode, CoordinateSlotUse> memo = forceInline ? forcedMemo : normalMemo;
                CoordinateSlotUse cached = memo.get(n);
                if (cached != null) {
                    return cached;
                }

                CoordinateSlotUse use = switch (n) {
                    case IRNode.Const c -> NONE;
                    case IRNode.BlockX bx -> new CoordinateSlotUse(true, false, false);
                    case IRNode.BlockY by -> new CoordinateSlotUse(false, true, false);
                    case IRNode.BlockZ bz -> new CoordinateSlotUse(false, false, true);
                    case IRNode.Bin bin -> node(bin.left(), false).plus(node(bin.right(), false));
                    case IRNode.Unary u -> node(u.input(), false);
                    case IRNode.Clamp cl -> node(cl.input(), false);
                    case IRNode.RangeChoice rc -> node(rc.input(), false)
                            .plus(node(rc.whenInRange(), false))
                            .plus(node(rc.whenOutOfRange(), false));
                    case IRNode.YClampedGradient g -> new CoordinateSlotUse(false, true, false);
                    case IRNode.Noise noise -> ALL;
                    case IRNode.ShiftedNoise sn -> ALL
                            .plus(node(sn.shiftX(), false))
                            .plus(node(sn.shiftY(), false))
                            .plus(node(sn.shiftZ(), false));
                    case IRNode.ShiftA sa -> new CoordinateSlotUse(true, false, true);
                    case IRNode.ShiftB sb -> new CoordinateSlotUse(true, false, true);
                    case IRNode.Shift sh -> ALL;
                    case IRNode.WeirdScaled w -> ALL.plus(node(w.input(), false));
                    case IRNode.InlinedNoise in -> node(in.coordX(), false).plus(node(in.coordY(), false)).plus(node(in.coordZ(), false));
                    case IRNode.InlinedBlendedNoise ibn -> ALL;
                    case IRNode.WeirdRarity wr -> node(wr.input(), false);
                    case IRNode.EndIslands e -> NONE;
                    case IRNode.Beardifier b -> NONE;
                    case IRNode.Spline.Constant sc -> NONE;
                    case IRNode.Spline.Multipoint mp -> spline(mp);
                    case IRNode.Marker m -> NONE;
                    case IRNode.Invoke iv -> NONE;
                    case IRNode.BlendDensity bd -> node(bd.input(), false);
                };
                memo.put(n, use);
                return use;
            }

            private CoordinateSlotUse spline(IRNode.Spline.Multipoint mp) {
                CoordinateSlotUse use = node(mp.coordinate(), false);
                for (IRNode.Spline value : mp.values()) {
                    use = use.plus(node(value, false));
                }
                return use;
            }
        }
    }

    /* --------------------------------------------------------------------- */
    /* Helper registry: tracks which extracted nodes have been assigned indices */
    /* --------------------------------------------------------------------- */

    static final class HelperRegistry {
        final ClassWriter cw;
        final String classInternalName;
        final ConstantPool pool;
        final RefCount.Result rc;
        final Set<IRNode> extracted;
        private final IdentityHashMap<IRNode, Integer> index = new IdentityHashMap<>();
        private final Deque<IRNode> pending = new ArrayDeque<>();
        private int nextIndex = 0;
        private int emitted = 0;

        HelperRegistry(ClassWriter cw, String classInternalName, ConstantPool pool,
                       RefCount.Result rc, Set<IRNode> extracted) {
            this.cw = cw;
            this.classInternalName = classInternalName;
            this.pool = pool;
            this.rc = rc;
            this.extracted = extracted;
        }

        /** Allocate (or reuse) a helper index for {@code node}; queue it for emission. */
        int indexOf(IRNode node) {
            Integer existing = index.get(node);
            if (existing != null) return existing;
            int idx = nextIndex++;
            if (idx >= MAX_HELPERS) {
                throw new BytecodeTooLargeException(
                        "Generated DF needs more than " + MAX_HELPERS + " helper methods");
            }
            index.put(node, idx);
            pending.addLast(node);
            return idx;
        }

        /** Emit every queued helper, including any helpers transitively discovered during emission. */
        void drain() {
            while (!pending.isEmpty()) {
                IRNode node = pending.pollFirst();
                int idx = index.get(node);
                emitHelper(idx, node);
                emitted++;
            }
        }

        int emittedCount() { return emitted; }

        private void emitHelper(int idx, IRNode node) {
            // Helper signature uses CompiledDensityFunction (the supertype) for `self`,
            // not the hidden class itself. This keeps the call-site MethodType free of
            // hidden-class types. Subclass-only fields (e.g. per-octave ImprovedNoise)
            // need a CHECKCAST in the emitter (see castSelfForSubclassNoiseFields).
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    helperName(idx), HELPER_DESC, null, null);
            mv.visitCode();
            emitCoordPrologue(mv, CoordinateSlotUse.analyze(node, extracted, true));

            // `self` is (CompiledDensityFunction) in the descriptor; per-octave noise fields
            // live on the hidden subclass, so emitOctaveContribution must CHECKCAST before GETFIELD.
            CoordinateReusePlan coordinateReuse = CoordinateReusePlan.analyze(node, RefCount.compute(node));
            EmitState st = new EmitState(mv, classInternalName, this, true, coordinateReuse);
            // Inline the body of `node` (the helper root) directly. Children that are themselves
            // extracted will route through MH.invokeExact inside emit().
            st.emitInline(node);

            mv.visitInsn(Opcodes.DRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    /** Stable naming for helper methods. Avoid `$` to keep stack traces readable. */
    public static String helperName(int idx) {
        return "helper_" + idx;
    }

    /* --------------------------------------------------------------------- */
    /* The recursive emitter                                                 */
    /* --------------------------------------------------------------------- */

    private static final class EmitState {
        private final MethodVisitor mv;
        private final String classInternalName;
        private final HelperRegistry helpers;
        private final ConstantPool pool;
        private final IdentityHashMap<IRNode, Integer> spillSlots = new IdentityHashMap<>();
        private final CoordinateReusePlan coordinateReuse;
        private final IdentityHashMap<IRNode, Integer> coordinateSlots = new IdentityHashMap<>();
        private int nextLocal = 5; // slots 0..4 are reserved (this/ctx/x/y/z)
        private final int contextLocal;
        private final boolean directInterpolatorMarkers;
        private final int directInterpolatorSoALocal;
        private final int directInterpolatorXSlot;
        private final int directInterpolatorYSlot;
        private final int directInterpolatorZSlot;
        private final int directInterpolatorValueArraySlot;
        /**
         * True for static {@code helper_N} methods: local 0 is typed as
         * {@link CompiledDensityFunction} in the method descriptor, but
         * {@link #emitOctaveContribution} reads subclass-only {@code noise_*} fields.
         * A {@code CHECKCAST} to the generated class is required for verification.
         * {@code compute()} passes false — {@code this} is already the precise subclass.
         */
        private final boolean castSelfForSubclassNoiseFields;

        EmitState(MethodVisitor mv, String classInternalName, HelperRegistry helpers,
                  boolean castSelfForSubclassNoiseFields) {
            this(mv, classInternalName, helpers, castSelfForSubclassNoiseFields, CoordinateReusePlan.EMPTY, 1);
        }

        EmitState(MethodVisitor mv, String classInternalName, HelperRegistry helpers,
                  boolean castSelfForSubclassNoiseFields,
                  CoordinateReusePlan coordinateReuse) {
            this(mv, classInternalName, helpers, castSelfForSubclassNoiseFields, coordinateReuse, 1);
        }

        EmitState(MethodVisitor mv, String classInternalName, HelperRegistry helpers,
                  boolean castSelfForSubclassNoiseFields,
                  CoordinateReusePlan coordinateReuse,
                  int contextLocal) {
            this(mv, classInternalName, helpers, castSelfForSubclassNoiseFields, coordinateReuse,
                    contextLocal, false, -1, -1, -1, -1, -1);
        }

        EmitState(MethodVisitor mv, String classInternalName, HelperRegistry helpers,
                  boolean castSelfForSubclassNoiseFields,
                  CoordinateReusePlan coordinateReuse,
                  int contextLocal,
                  boolean directInterpolatorMarkers,
                  int directInterpolatorSoALocal,
                  int directInterpolatorXSlot,
                  int directInterpolatorYSlot,
                  int directInterpolatorZSlot,
                  int directInterpolatorValueArraySlot) {
            this.mv = mv;
            this.classInternalName = classInternalName;
            this.helpers = helpers;
            this.pool = helpers.pool;
            this.coordinateReuse = coordinateReuse == null ? CoordinateReusePlan.EMPTY : coordinateReuse;
            this.castSelfForSubclassNoiseFields = castSelfForSubclassNoiseFields;
            this.contextLocal = contextLocal;
            this.directInterpolatorMarkers = directInterpolatorMarkers;
            this.directInterpolatorSoALocal = directInterpolatorSoALocal;
            this.directInterpolatorXSlot = directInterpolatorXSlot;
            this.directInterpolatorYSlot = directInterpolatorYSlot;
            this.directInterpolatorZSlot = directInterpolatorZSlot;
            this.directInterpolatorValueArraySlot = directInterpolatorValueArraySlot;
        }

        private int allocDoubleSlot() {
            int slot = nextLocal;
            nextLocal += 2;
            return slot;
        }

        private int allocRefSlot() {
            return nextLocal++;
        }

        private int allocIntSlot() {
            return nextLocal++;
        }

        private int allocLongSlot() {
            int slot = nextLocal;
            nextLocal += 2;
            return slot;
        }

        private int coordinateSlot(IRNode coordinate) {
            Integer existing = coordinateSlots.get(coordinate);
            if (existing != null) {
                return existing;
            }

            emit(coordinate);
            int slot = allocDoubleSlot();
            mv.visitVarInsn(Opcodes.DSTORE, slot);
            if (coordinateReuse.contains(coordinate)) {
                coordinateSlots.put(coordinate, slot);
            }
            return slot;
        }

        /**
         * Pre-install a spill mapping for {@code node} → {@code doubleSlot} so the next
         * {@link #emit(IRNode)} call that encounters {@code node} short-circuits into
         * a single {@code DLOAD doubleSlot} instead of re-emitting its body. Used by
         * the lattice {@code lattice_inner} helper to substitute the precomputed
         * Y-slab value (passed in as a method parameter) for the hoisted Y-only
         * subtree everywhere it appears in the root expression.
         *
         * <p>The {@code nextLocal} cursor is bumped past the end of the supplied slot
         * range so subsequent {@link #allocDoubleSlot} calls don't collide with it.
         */
        void preinstallSpill(IRNode node, int doubleSlot) {
            spillSlots.put(node, doubleSlot);
            int after = doubleSlot + 2;
            if (after > nextLocal) {
                nextLocal = after;
            }
        }

        /** Bump {@link #nextLocal} so fixed locals (e.g. batched {@code double[][]} slab) are not reused by spills. */
        void reserveLocalsFrom(int minNextLocal) {
            if (minNextLocal > nextLocal) {
                nextLocal = minNextLocal;
            }
        }

        /**
         * Captures the current spill table and local-variable cursor so a conditional
         * branch can be emitted without leaking its private spills into sibling branches
         * or the post-branch merge frame.
         *
         * <p>The bug we guard against: each {@code IRNode}-keyed entry in {@link #spillSlots}
         * tells {@link #emit(IRNode)} that the value is already live in some local slot, so
         * the next emission becomes a single {@code DLOAD slot}. If branch A emits {@code X}
         * for the first time and stores it into slot 9, then branch B (reached via a
         * different jump) inherits the same map and tries to {@code DLOAD 9} — but on B's
         * incoming path slot 9 was never written, so the verifier rejects the class with
         * <em>"get long/double overflows locals"</em>. Restoring the snapshot before each
         * branch arm makes those spills strictly local: the branch can still spill its own
         * shared subexpressions, but the entries are forgotten as soon as the branch ends.
         * Restoring {@link #nextLocal} also lets sibling branches reuse the same slot
         * indices instead of monotonically growing the frame.</p>
         */
        private record BranchScope(IdentityHashMap<IRNode, Integer> spills,
                                   IdentityHashMap<IRNode, Integer> coordinates,
                                   int nextLocal) {}

        private BranchScope snapshotBranch() {
            return new BranchScope(new IdentityHashMap<>(spillSlots),
                    new IdentityHashMap<>(coordinateSlots),
                    nextLocal);
        }

        private void restoreBranch(BranchScope snap) {
            spillSlots.clear();
            spillSlots.putAll(snap.spills);
            coordinateSlots.clear();
            coordinateSlots.putAll(snap.coordinates);
            nextLocal = snap.nextLocal;
        }

        void emit(IRNode node) {
            // Already spilled in this method — just reload.
            Integer slot = spillSlots.get(node);
            if (slot != null) {
                mv.visitVarInsn(Opcodes.DLOAD, slot);
                return;
            }
            // Routed to a helper: emit a single static call.
            if (helpers.extracted.contains(node)) {
                emitHelperCall(node);
                if (isSpillCandidate(node)) {
                    int s = allocDoubleSlot();
                    duplicateTopDoubleThenStoreTo(s);
                    spillSlots.put(node, s);
                }
                return;
            }
            boolean shouldSpill = isSpillCandidate(node);
            emitInline(node);
            if (shouldSpill) {
                int s = allocDoubleSlot();
                duplicateTopDoubleThenStoreTo(s);
                spillSlots.put(node, s);
            }
        }

        /**
         * Duplicates the wide value on the operand stack and stores one copy
         * into a double local (category-2). Single site for the canonical
         * DUP2/DSTORE pattern used for refcount ≥ 2 scheduling.
         */
        private void duplicateTopDoubleThenStoreTo(int doubleSlot) {
            mv.visitInsn(Opcodes.DUP2);
            mv.visitVarInsn(Opcodes.DSTORE, doubleSlot);
        }

        private void emitHelperCall(IRNode node) {
            int idx = helpers.indexOf(node);
            if (INDY_HELPERS_ENABLED) {
                // INVOKEDYNAMIC path — single bytecode, ConstantCallSite resolved on
                // first hit. The bsm uses invokedName ("helper_5" etc.) to locate
                // the static helper on this hidden class and returns a CCS bound to
                // it; subsequent calls go through a constant-target invokestatic that
                // the JIT can fully inline (no array load, no field load, no
                // MH.invokeExact dispatch through the signature-polymorphic adapter).
                mv.visitVarInsn(Opcodes.ALOAD, 0);          // self
                mv.visitVarInsn(Opcodes.ALOAD, contextLocal);          // ctx
                mv.visitInvokeDynamicInsn(
                        helperName(idx),
                        HELPER_DESC,
                        HELPER_BSM);
                return;
            }
            // Legacy path (kept as insurance against an unforeseen LinkageError during indy bsm linkage).
            // Load the helper's MethodHandle from the inherited helperHandles[] field.
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL,
                    "helperHandles", METHOD_HANDLE_ARRAY_DESC);
            ldcInt(idx);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, contextLocal);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_INTERNAL,
                    "invokeExact", HELPER_DESC, false);
        }

        private boolean isSpillCandidate(IRNode node) {
            Integer rcv = helpers.rc.refs().get(node);
            if (rcv == null || rcv < 2) return false;
            if (node instanceof IRNode.Const) return false;
            if (node instanceof IRNode.BlockX || node instanceof IRNode.BlockY || node instanceof IRNode.BlockZ) return false;
            return true;
        }

        /* ---------------- node-specific emission ---------------- */

        void emitInline(IRNode node) {
            switch (node) {
                case IRNode.Const c -> emitConst(c.value());
                case IRNode.BlockX bx -> { mv.visitVarInsn(Opcodes.ILOAD, 2); mv.visitInsn(Opcodes.I2D); }
                case IRNode.BlockY by -> { mv.visitVarInsn(Opcodes.ILOAD, 3); mv.visitInsn(Opcodes.I2D); }
                case IRNode.BlockZ bz -> { mv.visitVarInsn(Opcodes.ILOAD, 4); mv.visitInsn(Opcodes.I2D); }

                case IRNode.Bin bin -> emitBin(bin);
                case IRNode.Unary u -> emitUnary(u);
                case IRNode.Clamp cl -> emitClamp(cl);
                case IRNode.RangeChoice rc -> emitRangeChoice(rc);
                case IRNode.YClampedGradient g -> emitYClampedGradient(g);

                case IRNode.Noise n -> emitNoise(n);
                case IRNode.ShiftedNoise sn -> emitShiftedNoise(sn);
                case IRNode.ShiftA sa -> emitShiftA(sa);
                case IRNode.ShiftB sb -> emitShiftB(sb);
                case IRNode.Shift s -> emitShift(s);
                case IRNode.WeirdScaled w -> emitWeirdScaled(w);
                case IRNode.InlinedNoise n -> emitInlinedNoise(n);
                case IRNode.InlinedBlendedNoise b -> emitInlinedBlendedNoise(b);
                case IRNode.WeirdRarity wr -> emitWeirdRarity(wr);

                case IRNode.Spline.Constant sc -> emitConst(sc.value());
                case IRNode.Spline.Multipoint mp -> emitMultipointSpline(mp);

                case IRNode.Marker m -> emitMarkerInvoke(m.externIndex());
                case IRNode.Invoke iv -> emitInvoke(iv.externIndex());
                case IRNode.Beardifier b -> emitInvoke(b.externIndex());
                case IRNode.EndIslands e -> emitInvoke(e.externIndex());
                case IRNode.BlendDensity bd -> emitBlendDensity(bd);
            }
        }

        /* ---------------- primitive helpers ---------------- */

        private void emitConst(double v) {
            if (v == 0.0d) {
                mv.visitInsn(Opcodes.DCONST_0);
            } else if (v == 1.0d) {
                mv.visitInsn(Opcodes.DCONST_1);
            } else {
                mv.visitLdcInsn(v);
            }
        }

        private void emitBin(IRNode.Bin bin) {
            switch (bin.op()) {
                case ADD -> { emit(bin.left()); emit(bin.right()); mv.visitInsn(Opcodes.DADD); }
                case SUB -> { emit(bin.left()); emit(bin.right()); mv.visitInsn(Opcodes.DSUB); }
                case MUL -> { emit(bin.left()); emit(bin.right()); mv.visitInsn(Opcodes.DMUL); }
                case DIV -> { emit(bin.left()); emit(bin.right()); mv.visitInsn(Opcodes.DDIV); }
                case MIN -> {
                    emit(bin.left());
                    emit(bin.right());
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
                }
                case MAX -> {
                    emit(bin.left());
                    emit(bin.right());
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
                }
            }
        }

        private void emitUnary(IRNode.Unary u) {
            emit(u.input());
            switch (u.op()) {
                case ABS -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
                case NEG -> mv.visitInsn(Opcodes.DNEG);
                case SQUARE -> {
                    mv.visitInsn(Opcodes.DUP2);
                    mv.visitInsn(Opcodes.DMUL);
                }
                case CUBE -> {
                    mv.visitInsn(Opcodes.DUP2);
                    mv.visitInsn(Opcodes.DUP2);
                    mv.visitInsn(Opcodes.DMUL);
                    mv.visitInsn(Opcodes.DMUL);
                }
                case HALF_NEGATIVE -> emitConditionalScale(0.5);
                case QUARTER_NEGATIVE -> emitConditionalScale(0.25);
                case SQUEEZE -> emitSqueeze();
            }
        }

        // x > 0 ? x : x * factor  with x already on the stack (top).
        private void emitConditionalScale(double factor) {
            mv.visitInsn(Opcodes.DUP2);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DCMPL);
            Label keep = new Label();
            Label end = new Label();
            mv.visitJumpInsn(Opcodes.IFGT, keep);
            mv.visitLdcInsn(factor);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitJumpInsn(Opcodes.GOTO, end);
            mv.visitLabel(keep);
            mv.visitLabel(end);
        }

        private void emitSqueeze() {
            int inputSlot = allocDoubleSlot();
            int clampedSlot = allocDoubleSlot();
            mv.visitVarInsn(Opcodes.DSTORE, inputSlot);

            Label notBelowMin = new Label();
            Label notAboveMax = new Label();
            Label clamped = new Label();

            mv.visitVarInsn(Opcodes.DLOAD, inputSlot);
            mv.visitLdcInsn(-1.0D);
            mv.visitInsn(Opcodes.DCMPG);
            mv.visitJumpInsn(Opcodes.IFGE, notBelowMin);
            mv.visitLdcInsn(-1.0D);
            mv.visitJumpInsn(Opcodes.GOTO, clamped);

            mv.visitLabel(notBelowMin);
            mv.visitVarInsn(Opcodes.DLOAD, inputSlot);
            mv.visitInsn(Opcodes.DCONST_1);
            mv.visitInsn(Opcodes.DCMPL);
            mv.visitJumpInsn(Opcodes.IFLE, notAboveMax);
            mv.visitInsn(Opcodes.DCONST_1);
            mv.visitJumpInsn(Opcodes.GOTO, clamped);

            mv.visitLabel(notAboveMax);
            mv.visitVarInsn(Opcodes.DLOAD, inputSlot);

            mv.visitLabel(clamped);
            mv.visitVarInsn(Opcodes.DSTORE, clampedSlot);

            mv.visitVarInsn(Opcodes.DLOAD, clampedSlot);
            mv.visitLdcInsn(2.0D);
            mv.visitInsn(Opcodes.DDIV);
            mv.visitVarInsn(Opcodes.DLOAD, clampedSlot);
            mv.visitVarInsn(Opcodes.DLOAD, clampedSlot);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.DLOAD, clampedSlot);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitLdcInsn(24.0D);
            mv.visitInsn(Opcodes.DDIV);
            mv.visitInsn(Opcodes.DSUB);
        }

        private void emitClamp(IRNode.Clamp c) {
            emit(c.input());
            mv.visitLdcInsn(c.max());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            mv.visitLdcInsn(c.min());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        }

        /**
         * {@link IRNode.RangeChoice} — covers vanilla {@code DensityFunctions.rangeChoice}
         * and Generator Accelerator's {@code FastRangeChoice} (same IR after unwrap).
         *
         * <p><strong>Local types / verifier:</strong> the compared input is spilled with
         * {@link #allocDoubleSlot()} (a {@code double} occupies two local slots). Each arm
         * uses {@link BranchScope}: spills and {@link #nextLocal} must not leak across
         * branches or the JVM sees {@code double} / reference slots that are only
         * initialized on one path (classic {@code VerifyError} after merge). Nested
         * {@code RangeChoice} nodes each take their own snapshot; restoring the outer
         * snapshot after an arm clears inner temporaries before the outer join label.
         *
         * <p><strong>Not related:</strong> {@code NoiseChunk}-field reads on local&nbsp;1
         * live in dedicated cell-fill overrides, outside this scalar expression emitter.
         */
        private void emitRangeChoice(IRNode.RangeChoice rc) {
            emit(rc.input());
            int slot = allocDoubleSlot();
            mv.visitVarInsn(Opcodes.DSTORE, slot);
            mv.visitVarInsn(Opcodes.DLOAD, slot);
            mv.visitLdcInsn(rc.min());
            mv.visitInsn(Opcodes.DCMPG);
            Label outOfRange = new Label();
            Label end = new Label();
            mv.visitJumpInsn(Opcodes.IFLT, outOfRange);

            mv.visitVarInsn(Opcodes.DLOAD, slot);
            mv.visitLdcInsn(rc.max());
            mv.visitInsn(Opcodes.DCMPL);
            mv.visitJumpInsn(Opcodes.IFGE, outOfRange);

            // Each branch must NOT see spills the other branch made — those slots are
            // uninitialized on its incoming path. Snapshot before emitting each arm and
            // restore afterwards (also clears the post-merge state so code after `end`
            // never tries to reload a slot that's only typed on one arm).
            BranchScope snap = snapshotBranch();
            emit(rc.whenInRange());
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(outOfRange);
            emit(rc.whenOutOfRange());
            restoreBranch(snap);

            mv.visitLabel(end);
        }

        private void emitYClampedGradient(IRNode.YClampedGradient g) {
            int fromY = g.fromY();
            int toY = g.toY();
            double fromValue = g.fromValue();
            double toValue = g.toValue();

            if (fromY == toY || !Double.isFinite(fromValue) || !Double.isFinite(toValue)) {
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                mv.visitInsn(Opcodes.I2D);
                mv.visitLdcInsn((double) fromY);
                mv.visitLdcInsn((double) toY);
                mv.visitLdcInsn(fromValue);
                mv.visitLdcInsn(toValue);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/util/Mth", "clampedMap",
                        "(DDDDD)D", false);
                return;
            }

            double slope = (toValue - fromValue) / (double) (toY - fromY);
            Label interior = new Label();
            Label end = new Label();

            if (fromY < toY) {
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                ldcInt(fromY);
                mv.visitJumpInsn(Opcodes.IF_ICMPGT, interior);
                mv.visitLdcInsn(fromValue);
                mv.visitJumpInsn(Opcodes.GOTO, end);

                mv.visitLabel(interior);
                Label upper = new Label();
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                ldcInt(toY);
                mv.visitJumpInsn(Opcodes.IF_ICMPLT, upper);
                mv.visitLdcInsn(toValue);
                mv.visitJumpInsn(Opcodes.GOTO, end);

                mv.visitLabel(upper);
            } else {
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                ldcInt(fromY);
                mv.visitJumpInsn(Opcodes.IF_ICMPLT, interior);
                mv.visitLdcInsn(fromValue);
                mv.visitJumpInsn(Opcodes.GOTO, end);

                mv.visitLabel(interior);
                Label lower = new Label();
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                ldcInt(toY);
                mv.visitJumpInsn(Opcodes.IF_ICMPGT, lower);
                mv.visitLdcInsn(toValue);
                mv.visitJumpInsn(Opcodes.GOTO, end);

                mv.visitLabel(lower);
            }

            mv.visitVarInsn(Opcodes.ILOAD, 3);
            ldcInt(fromY);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(slope);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitLdcInsn(fromValue);
            mv.visitInsn(Opcodes.DADD);
            mv.visitLabel(end);
        }

        /* ---------------- noise samples ---------------- */

        private void loadNoise(int idx) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL, "noises",
                    "[L" + NORMAL_NOISE_INTERNAL + ";");
            ldcInt(idx);
            mv.visitInsn(Opcodes.AALOAD);
        }

        private void emitNoise(IRNode.Noise n) {
            loadNoise(n.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(n.xzScale());
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(n.yScale());
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(n.xzScale());
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
        }

        private void emitShiftedNoise(IRNode.ShiftedNoise sn) {
            loadNoise(sn.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(sn.xzScale());
            mv.visitInsn(Opcodes.DMUL);
            emit(sn.shiftX());
            mv.visitInsn(Opcodes.DADD);

            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(sn.yScale());
            mv.visitInsn(Opcodes.DMUL);
            emit(sn.shiftY());
            mv.visitInsn(Opcodes.DADD);

            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(sn.xzScale());
            mv.visitInsn(Opcodes.DMUL);
            emit(sn.shiftZ());
            mv.visitInsn(Opcodes.DADD);

            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
        }

        private void emitShift(IRNode.Shift s) {
            loadNoise(s.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
            mv.visitLdcInsn(4.0);
            mv.visitInsn(Opcodes.DMUL);
        }

        private void emitShiftA(IRNode.ShiftA s) {
            loadNoise(s.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
            mv.visitLdcInsn(4.0);
            mv.visitInsn(Opcodes.DMUL);
        }

        private void emitShiftB(IRNode.ShiftB s) {
            loadNoise(s.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
            mv.visitLdcInsn(4.0);
            mv.visitInsn(Opcodes.DMUL);
        }

        private record InlinedNoiseAxisPlan(boolean constZero, int blockIntSlot, double blockScale, int coordSlot) {
            static InlinedNoiseAxisPlan zeroAxis() {
                return new InlinedNoiseAxisPlan(true, 0, 0.0D, -1);
            }

            static InlinedNoiseAxisPlan block(int blockIntSlot, double blockScale) {
                return new InlinedNoiseAxisPlan(false, blockIntSlot, blockScale, -1);
            }

            static InlinedNoiseAxisPlan slot(int coordSlot) {
                return new InlinedNoiseAxisPlan(false, 0, 0.0D, coordSlot);
            }
        }

        private InlinedNoiseAxisPlan analyzeInlinedNoiseAxis(IRNode axis) {
            if (axis instanceof IRNode.Const c && c.value() == 0.0D) {
                return InlinedNoiseAxisPlan.zeroAxis();
            }
            if (axis instanceof IRNode.BlockX) {
                return InlinedNoiseAxisPlan.block(2, 1.0D);
            }
            if (axis instanceof IRNode.BlockY) {
                return InlinedNoiseAxisPlan.block(3, 1.0D);
            }
            if (axis instanceof IRNode.BlockZ) {
                return InlinedNoiseAxisPlan.block(4, 1.0D);
            }
            if (axis instanceof IRNode.Bin bin && bin.op() == IRNode.BinOp.MUL) {
                InlinedNoiseAxisPlan mulPlan = analyzeScaledBlockAxis(bin.left(), bin.right());
                if (mulPlan != null) {
                    return mulPlan;
                }
            }
            return InlinedNoiseAxisPlan.slot(coordinateSlot(axis));
        }

        private InlinedNoiseAxisPlan analyzeScaledBlockAxis(IRNode left, IRNode right) {
            if (left instanceof IRNode.Const c) {
                Integer slot = blockIntSlot(right);
                if (slot != null) {
                    return InlinedNoiseAxisPlan.block(slot, c.value());
                }
            }
            if (right instanceof IRNode.Const c) {
                Integer slot = blockIntSlot(left);
                if (slot != null) {
                    return InlinedNoiseAxisPlan.block(slot, c.value());
                }
            }
            return null;
        }

        private Integer blockIntSlot(IRNode axis) {
            if (axis instanceof IRNode.BlockX) {
                return 2;
            }
            if (axis instanceof IRNode.BlockY) {
                return 3;
            }
            if (axis instanceof IRNode.BlockZ) {
                return 4;
            }
            return null;
        }

        private InlinedNoiseAxisPlan scaleAxisPlanForSecond(InlinedNoiseAxisPlan axis, double secondScale) {
            if (axis.constZero() || Double.compare(secondScale, 1.0D) == 0) {
                return axis;
            }
            if (axis.blockIntSlot() != 0) {
                return InlinedNoiseAxisPlan.block(axis.blockIntSlot(), axis.blockScale() * secondScale);
            }
            int scaledSlot = allocDoubleSlot();
            mv.visitVarInsn(Opcodes.DLOAD, axis.coordSlot());
            mv.visitLdcInsn(secondScale);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.DSTORE, scaledSlot);
            return InlinedNoiseAxisPlan.slot(scaledSlot);
        }

        private void emitAxisCoordinateValue(InlinedNoiseAxisPlan axis) {
            if (axis.constZero()) {
                mv.visitInsn(Opcodes.DCONST_0);
                return;
            }
            if (axis.blockIntSlot() != 0) {
                mv.visitVarInsn(Opcodes.ILOAD, axis.blockIntSlot());
                mv.visitInsn(Opcodes.I2D);
                if (Double.compare(axis.blockScale(), 1.0D) != 0) {
                    mv.visitLdcInsn(axis.blockScale());
                    mv.visitInsn(Opcodes.DMUL);
                }
                return;
            }
            mv.visitVarInsn(Opcodes.DLOAD, axis.coordSlot());
        }

        /* ---------------- Tier-3 inlined noise emission ---------------- */

        /**
         * Emit the fully unrolled per-octave loop for a single
         * {@link IRNode.InlinedNoise}. The shape of the bytecode is:
         * <pre>
         *   // Coordinate prep — emit each coord IR sub-tree once into a fresh slot
         *   emit(coordX); DSTORE cxSlot
         *   emit(coordY); DSTORE cySlot
         *   emit(coordZ); DSTORE czSlot
         *
         *   // First branch (inputCoordScale = 1.0): octave-by-octave
         *   //   contribution_i = ampValueFactor_i *
         *   //                    noise_i.noise(wrap(cx*freq_i), wrap(cy*freq_i), wrap(cz*freq_i))
         *   //   sum += contribution_i
         *
         *   // Second branch (inputCoordScale = NormalNoise.INPUT_FACTOR):
         *   //   pre-scale cx/cy/cz once, then same per-octave loop
         *
         *   // Final: sum *= valueFactor
         * </pre>
         *
         * <p>The {@code wrap} call inlines through {@link
         * dev.sixik.generator_accelerator.common.density.compiler.compiler.runtime.Runtime#wrapAxis}
         * and HotSpot will collapse it into the call site once the surrounding
         * {@code compute} method gets hot.
         */
        private void emitInlinedNoise(IRNode.InlinedNoise n) {
            var spec = pool.noiseSpec(n.specPoolIndex());
            InlinedNoiseAxisPlan cxPlan = analyzeInlinedNoiseAxis(n.coordX());
            InlinedNoiseAxisPlan cyPlan = analyzeInlinedNoiseAxis(n.coordY());
            InlinedNoiseAxisPlan czPlan = analyzeInlinedNoiseAxis(n.coordZ());

            emitInlinedNoiseJavaTail(n, spec, cxPlan, cyPlan, czPlan);
        }

        private void emitInlinedNoiseJavaTail(IRNode.InlinedNoise n, dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec spec,
                                              InlinedNoiseAxisPlan cxPlan,
                                              InlinedNoiseAxisPlan cyPlan,
                                              InlinedNoiseAxisPlan czPlan) {
            emitBranchSum(spec.first(), n.specPoolIndex(), 0, cxPlan, cyPlan, czPlan);
            var second = spec.second();
            InlinedNoiseAxisPlan sCx = scaleAxisPlanForSecond(cxPlan, second.inputCoordScale());
            InlinedNoiseAxisPlan sCy = scaleAxisPlanForSecond(cyPlan, second.inputCoordScale());
            InlinedNoiseAxisPlan sCz = scaleAxisPlanForSecond(czPlan, second.inputCoordScale());
            int secondCount = second.activeOctaves().length;
            for (int i = 0; i < secondCount; i++) {
                emitOctaveContribution(n.specPoolIndex(), 1, i,
                        second.inputFactors()[i], second.ampValueFactors()[i],
                        sCx, sCy, sCz);
                mv.visitInsn(Opcodes.DADD);
            }
            mv.visitLdcInsn(spec.valueFactor());
            mv.visitInsn(Opcodes.DMUL);
        }

        /**
         * Emit per-octave contributions for one PerlinNoise branch and leave the sum
         * on top of the operand stack. When the branch has no active octaves the
         * stack ends with {@code DCONST_0} so the second-branch loop's DADD chain
         * has something to accumulate into.
         */
        private void emitBranchSum(dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec.PerlinSpec branch,
                                   int specIdx, int branchIdx,
                                   InlinedNoiseAxisPlan cxPlan,
                                   InlinedNoiseAxisPlan cyPlan,
                                   InlinedNoiseAxisPlan czPlan) {
            int count = branch.activeOctaves().length;
            if (count == 0) {
                mv.visitInsn(Opcodes.DCONST_0);
                return;
            }
            // first contribution leaves a double on the stack; subsequent ones DADD.
            for (int i = 0; i < count; i++) {
                emitOctaveContribution(specIdx, branchIdx, i,
                        branch.inputFactors()[i], branch.ampValueFactors()[i],
                        cxPlan, cyPlan, czPlan);
                if (i > 0) mv.visitInsn(Opcodes.DADD);
            }
        }

        private void emitWrappedAxis(InlinedNoiseAxisPlan axis, double inputFactor) {
            if (axis.constZero()) {
                mv.visitInsn(Opcodes.DCONST_0);
                return;
            }
            if (axis.blockIntSlot() != 0) {
                mv.visitVarInsn(Opcodes.ILOAD, axis.blockIntSlot());
                mv.visitInsn(Opcodes.I2D);
                mv.visitLdcInsn(axis.blockScale() * inputFactor);
                mv.visitInsn(Opcodes.DMUL);
            } else {
                mv.visitVarInsn(Opcodes.DLOAD, axis.coordSlot());
                mv.visitLdcInsn(inputFactor);
                mv.visitInsn(Opcodes.DMUL);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL,
                    "wrapAxis", "(D)D", false);
        }

        /**
         * Emit one octave's contribution: {@code ampValueFactor_i *
         * noise_i.noise(wrap(cx*freq_i), wrap(cy*freq_i), wrap(cz*freq_i))}, leaving
         * a single double on top of the operand stack.
         *
         * <p>Field load order matters: pushing the {@code ampValueFactor_i} constant
         * first lets the {@code DMUL} after the {@code INVOKEVIRTUAL} stay clean —
         * we never have to swap a long/double from below an object reference.
         */
        private void emitOctaveContribution(int specIdx, int branchIdx, int activeOctaveIdx,
                                            double inputFactor, double ampValueFactor,
                                            InlinedNoiseAxisPlan cxPlan,
                                            InlinedNoiseAxisPlan cyPlan,
                                            InlinedNoiseAxisPlan czPlan) {
            mv.visitLdcInsn(ampValueFactor);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            if (castSelfForSubclassNoiseFields) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
            }
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName,
                    noiseFieldName(specIdx, branchIdx, activeOctaveIdx), IMPROVED_NOISE_DESC);

            emitWrappedAxis(cxPlan, inputFactor);
            emitWrappedAxis(cyPlan, inputFactor);
            emitWrappedAxis(czPlan, inputFactor);

            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IMPROVED_NOISE_INTERNAL,
                    "noise", "(DDD)D", false);
            mv.visitInsn(Opcodes.DMUL);
        }

        /**
         * Standalone {@link IRNode.WeirdRarity} emission: just delegates to the same
         * static helper {@link #emitWeirdScaled} previously used inline. Surfaced as
         * its own node so {@link RefCount}
         * can spill the result to a slot for the {@code abs(noise(x/r,y/r,z/r)) * r}
         * fan-out (4 uses).
         */
        private void emitWeirdRarity(IRNode.WeirdRarity wr) {
            emit(wr.input());
            ldcInt(wr.rarityValueMapperOrdinal());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL,
                    "weirdRarity", "(DI)D", false);
        }

        private void emitWeirdScaled(IRNode.WeirdScaled w) {
            emit(w.input());
            ldcInt(w.rarityValueMapperOrdinal());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "dev/sixik/generator_accelerator/common/density/compiler/compiler/runtime/Runtime",
                    "weirdRarity", "(DI)D", false);
            int dSlot = allocDoubleSlot();
            mv.visitInsn(Opcodes.DUP2);
            mv.visitVarInsn(Opcodes.DSTORE, dSlot);
            loadNoise(w.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitVarInsn(Opcodes.DLOAD, dSlot);
            mv.visitInsn(Opcodes.DDIV);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.I2D);
            mv.visitVarInsn(Opcodes.DLOAD, dSlot);
            mv.visitInsn(Opcodes.DDIV);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitVarInsn(Opcodes.DLOAD, dSlot);
            mv.visitInsn(Opcodes.DDIV);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
            mv.visitInsn(Opcodes.DMUL);
        }

        /* ---------------- spline ---------------- */

        private void emitMultipointSpline(IRNode.Spline.Multipoint mp) {
            float[] locs = mp.locations();
            int n = locs.length;
            Label end = new Label();
            boolean binarySearch = useBinarySplineSearch(n);
            boolean lutSearch = useSplineSegmentLut(n, locs);
            int searchMode = lutSearch ? DfcSplineStats.SEARCH_LUT
                    : (binarySearch ? DfcSplineStats.SEARCH_BINARY : DfcSplineStats.SEARCH_LINEAR);
            boolean point4RightConstantFast = n == 4
                    && !lutSearch
                    && mp.derivatives()[n - 1] == 0.0F
                    && mp.values().get(n - 1) instanceof IRNode.Spline.Constant;
            // Compute coordinate once. For the hot point4/right-extrapolation case we first
            // test the right boundary in double precision and only materialize the float local
            // when we know we need the interior/left dispatcher.
            emit(mp.coordinate());
            int coordSlot = allocDoubleSlot();
            mv.visitVarInsn(Opcodes.DSTORE, coordSlot);
            int fSlot = allocFloatSlot();

            int splineTimingStartSlot = -1;
            int splineTimingResultSlot = -1;
            if (SPLINE_RUNTIME_STATS_ENABLED) {
                splineTimingStartSlot = allocLongSlot();
                splineTimingResultSlot = allocFloatSlot();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_SPLINE_STATS_INTERNAL, "sampleStart", "()J", false);
                mv.visitVarInsn(Opcodes.LSTORE, splineTimingStartSlot);
            }

            // Snapshot taken AFTER fSlot is allocated so fSlot is treated as
            // outside-the-branch state and remains valid across all segment arms.
            // Each segment / extrapolation arm gets a clean view: it can spill its
            // own subexpressions, but those spills are invalidated before the next
            // sibling arm runs (slot indices and IRNode→slot bindings are reset).
            BranchScope snap = snapshotBranch();

            if (point4RightConstantFast) {
                Label rightFast = new Label();
                mv.visitVarInsn(Opcodes.DLOAD, coordSlot);
                mv.visitLdcInsn((double) locs[n - 1]);
                mv.visitInsn(Opcodes.DCMPL);
                mv.visitJumpInsn(Opcodes.IFGE, rightFast);

                mv.visitVarInsn(Opcodes.DLOAD, coordSlot);
                mv.visitInsn(Opcodes.D2F);
                mv.visitVarInsn(Opcodes.FSTORE, fSlot);
                emitFourPointSpline(fSlot, mp, snap, end, searchMode,
                        splineTimingStartSlot, splineTimingResultSlot, true);

                mv.visitLabel(rightFast);
                restoreBranch(snap);
                emitSplineAsFloat(mp.values().get(n - 1));
                emitSplineRuntimeRecord(4, searchMode, DfcSplineStats.EXIT_RIGHT_EXTRAPOLATION,
                        splineTimingStartSlot, splineTimingResultSlot);
                restoreBranch(snap);
                mv.visitJumpInsn(Opcodes.GOTO, end);

                mv.visitLabel(end);
                mv.visitInsn(Opcodes.F2D);
                return;
            }

            mv.visitVarInsn(Opcodes.DLOAD, coordSlot);
            mv.visitInsn(Opcodes.D2F);
            mv.visitVarInsn(Opcodes.FSTORE, fSlot);

            if (n == 1) {
                emitLinearExtend(fSlot, locs, mp.derivatives(), 0, mp.values().get(0));
                emitSplineRuntimeRecord(n, DfcSplineStats.SEARCH_LINEAR, DfcSplineStats.EXIT_INTERIOR,
                        splineTimingStartSlot, splineTimingResultSlot);
                mv.visitInsn(Opcodes.F2D);
                mv.visitJumpInsn(Opcodes.GOTO, end);
                restoreBranch(snap);
                mv.visitLabel(end);
                return;
            }

            if (n == 3 && !binarySearch && !lutSearch) {
                emitThreePointSpline(fSlot, mp, snap, end, splineTimingStartSlot, splineTimingResultSlot);
                mv.visitLabel(end);
                mv.visitInsn(Opcodes.F2D);
                return;
            }

            if (n == 4 && !lutSearch) {
                emitFourPointSpline(fSlot, mp, snap, end, searchMode,
                        splineTimingStartSlot, splineTimingResultSlot, false);
                mv.visitLabel(end);
                mv.visitInsn(Opcodes.F2D);
                return;
            }

            Label leftExt = new Label();
            Label rightExt = new Label();

            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[n - 1]);
            mv.visitInsn(Opcodes.FCMPL);
            mv.visitJumpInsn(Opcodes.IFGE, rightExt);

            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[0]);
            mv.visitInsn(Opcodes.FCMPG);
            mv.visitJumpInsn(Opcodes.IFLT, leftExt);

            if (lutSearch) {
                emitLutSplineSegments(fSlot, mp, snap, end, splineTimingStartSlot, splineTimingResultSlot);
            } else if (binarySearch) {
                emitBinarySplineSegments(fSlot, mp, snap, end, 0, n - 2,
                        splineTimingStartSlot, splineTimingResultSlot);
            } else {
                emitLinearSplineSegments(fSlot, mp, snap, end,
                        splineTimingStartSlot, splineTimingResultSlot);
            }

            mv.visitJumpInsn(Opcodes.GOTO, rightExt);

            mv.visitLabel(leftExt);
            restoreBranch(snap);
            emitLinearExtend(fSlot, locs, mp.derivatives(), 0, mp.values().get(0));
            emitSplineRuntimeRecord(n,
                    lutSearch ? DfcSplineStats.SEARCH_LUT
                            : (binarySearch ? DfcSplineStats.SEARCH_BINARY : DfcSplineStats.SEARCH_LINEAR),
                    DfcSplineStats.EXIT_LEFT_EXTRAPOLATION,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(rightExt);
            restoreBranch(snap);
            emitLinearExtend(fSlot, locs, mp.derivatives(), n - 1, mp.values().get(n - 1));
            emitSplineRuntimeRecord(n,
                    lutSearch ? DfcSplineStats.SEARCH_LUT
                            : (binarySearch ? DfcSplineStats.SEARCH_BINARY : DfcSplineStats.SEARCH_LINEAR),
                    DfcSplineStats.EXIT_RIGHT_EXTRAPOLATION,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);

            mv.visitLabel(end);
            mv.visitInsn(Opcodes.F2D);
        }

        private void emitThreePointSpline(int fSlot, IRNode.Spline.Multipoint mp,
                                          BranchScope snap, Label end,
                                          int splineTimingStartSlot, int splineTimingResultSlot) {
            float[] locs = mp.locations();
            Label leftExt = new Label();
            Label secondSegment = new Label();
            Label rightExt = new Label();

            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[2]);
            mv.visitInsn(Opcodes.FCMPL);
            mv.visitJumpInsn(Opcodes.IFGE, rightExt);

            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[0]);
            mv.visitInsn(Opcodes.FCMPG);
            mv.visitJumpInsn(Opcodes.IFLT, leftExt);

            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[1]);
            mv.visitInsn(Opcodes.FCMPG);
            mv.visitJumpInsn(Opcodes.IFGE, secondSegment);

            restoreBranch(snap);
            emitInterpolatedSegment(fSlot, mp, 0);
            emitSplineRuntimeRecord(3, DfcSplineStats.SEARCH_LINEAR, DfcSplineStats.EXIT_INTERIOR,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(secondSegment);
            restoreBranch(snap);
            emitInterpolatedSegment(fSlot, mp, 1);
            emitSplineRuntimeRecord(3, DfcSplineStats.SEARCH_LINEAR, DfcSplineStats.EXIT_INTERIOR,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(leftExt);
            restoreBranch(snap);
            emitLinearExtend(fSlot, locs, mp.derivatives(), 0, mp.values().get(0));
            emitSplineRuntimeRecord(3, DfcSplineStats.SEARCH_LINEAR, DfcSplineStats.EXIT_LEFT_EXTRAPOLATION,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(rightExt);
            restoreBranch(snap);
            emitLinearExtend(fSlot, locs, mp.derivatives(), 2, mp.values().get(2));
            emitSplineRuntimeRecord(3, DfcSplineStats.SEARCH_LINEAR, DfcSplineStats.EXIT_RIGHT_EXTRAPOLATION,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);
        }

        private void emitFourPointSpline(int fSlot, IRNode.Spline.Multipoint mp,
                                         BranchScope snap, Label end,
                                         int searchMode,
                                         int splineTimingStartSlot, int splineTimingResultSlot,
                                         boolean rightAlreadyChecked) {
            float[] locs = mp.locations();
            Label leftExt = new Label();
            Label segment1 = new Label();
            Label segment2 = new Label();
            Label rightExt = rightAlreadyChecked ? null : new Label();

            if (!rightAlreadyChecked) {
                mv.visitVarInsn(Opcodes.FLOAD, fSlot);
                mv.visitLdcInsn(locs[3]);
                mv.visitInsn(Opcodes.FCMPL);
                mv.visitJumpInsn(Opcodes.IFGE, rightExt);
            }

            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[0]);
            mv.visitInsn(Opcodes.FCMPG);
            mv.visitJumpInsn(Opcodes.IFLT, leftExt);

            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[1]);
            mv.visitInsn(Opcodes.FCMPG);
            mv.visitJumpInsn(Opcodes.IFGE, segment1);

            restoreBranch(snap);
            emitInterpolatedSegment(fSlot, mp, 0);
            emitSplineRuntimeRecord(4, searchMode, DfcSplineStats.EXIT_INTERIOR,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(segment1);
            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[2]);
            mv.visitInsn(Opcodes.FCMPG);
            mv.visitJumpInsn(Opcodes.IFGE, segment2);

            restoreBranch(snap);
            emitInterpolatedSegment(fSlot, mp, 1);
            emitSplineRuntimeRecord(4, searchMode, DfcSplineStats.EXIT_INTERIOR,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(segment2);
            restoreBranch(snap);
            emitInterpolatedSegment(fSlot, mp, 2);
            emitSplineRuntimeRecord(4, searchMode, DfcSplineStats.EXIT_INTERIOR,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(leftExt);
            restoreBranch(snap);
            emitLinearExtend(fSlot, locs, mp.derivatives(), 0, mp.values().get(0));
            emitSplineRuntimeRecord(4, searchMode, DfcSplineStats.EXIT_LEFT_EXTRAPOLATION,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            if (!rightAlreadyChecked) {
                mv.visitLabel(rightExt);
                restoreBranch(snap);
                emitLinearExtend(fSlot, locs, mp.derivatives(), 3, mp.values().get(3));
                emitSplineRuntimeRecord(4, searchMode, DfcSplineStats.EXIT_RIGHT_EXTRAPOLATION,
                        splineTimingStartSlot, splineTimingResultSlot);
                restoreBranch(snap);
            }
        }

        private void emitLinearSplineSegments(int fSlot, IRNode.Spline.Multipoint mp,
                                              BranchScope snap, Label end,
                                              int splineTimingStartSlot, int splineTimingResultSlot) {
            float[] locs = mp.locations();
            int lastSegment = locs.length - 2;
            for (int i = 0; i < lastSegment; i++) {
                mv.visitVarInsn(Opcodes.FLOAD, fSlot);
                mv.visitLdcInsn(locs[i + 1]);
                mv.visitInsn(Opcodes.FCMPG);
                Label notThis = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, notThis);
                restoreBranch(snap);
                emitInterpolatedSegment(fSlot, mp, i);
                emitSplineRuntimeRecord(locs.length, DfcSplineStats.SEARCH_LINEAR, DfcSplineStats.EXIT_INTERIOR,
                        splineTimingStartSlot, splineTimingResultSlot);
                restoreBranch(snap);
                mv.visitJumpInsn(Opcodes.GOTO, end);
                mv.visitLabel(notThis);
            }
            // The caller already handled left/right extrapolation, so falling through here
            // means the coordinate must belong to the last interior segment.
            restoreBranch(snap);
            emitInterpolatedSegment(fSlot, mp, lastSegment);
            emitSplineRuntimeRecord(locs.length, DfcSplineStats.SEARCH_LINEAR, DfcSplineStats.EXIT_INTERIOR,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);
        }

        private void emitBinarySplineSegments(int fSlot, IRNode.Spline.Multipoint mp,
                                              BranchScope snap, Label end,
                                              int loSegment, int hiSegment,
                                              int splineTimingStartSlot, int splineTimingResultSlot) {
            int segmentCount = hiSegment - loSegment + 1;
            if (segmentCount == 1) {
                restoreBranch(snap);
                emitInterpolatedSegment(fSlot, mp, loSegment);
                emitSplineRuntimeRecord(mp.locations().length, DfcSplineStats.SEARCH_BINARY,
                        DfcSplineStats.EXIT_INTERIOR,
                        splineTimingStartSlot, splineTimingResultSlot);
                restoreBranch(snap);
                mv.visitJumpInsn(Opcodes.GOTO, end);
                return;
            }

            int locationsIndex = pool.internSpline(mp.locations().clone());
            int segmentSlot = allocIntSlot();
            Label fallback = new Label();
            Label[] cases = new Label[segmentCount];
            for (int i = 0; i < segmentCount; i++) {
                cases[i] = new Label();
            }

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL, "splines", OBJECT_ARRAY_DESC);
            ldcInt(locationsIndex);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "[F");
            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_SPLINE_SUPPORT_INTERNAL, "selectSegmentBinary",
                    DFC_SPLINE_SELECT_BINARY_DESC, false);
            mv.visitVarInsn(Opcodes.ISTORE, segmentSlot);

            mv.visitVarInsn(Opcodes.ILOAD, segmentSlot);
            mv.visitTableSwitchInsn(loSegment, hiSegment, fallback, cases);
            for (int i = 0; i < segmentCount; i++) {
                mv.visitLabel(cases[i]);
                restoreBranch(snap);
                emitInterpolatedSegment(fSlot, mp, loSegment + i);
                emitSplineRuntimeRecord(mp.locations().length, DfcSplineStats.SEARCH_BINARY,
                        DfcSplineStats.EXIT_INTERIOR,
                        splineTimingStartSlot, splineTimingResultSlot);
                restoreBranch(snap);
                mv.visitJumpInsn(Opcodes.GOTO, end);
            }

            mv.visitLabel(fallback);
            restoreBranch(snap);
            emitInterpolatedSegment(fSlot, mp, hiSegment);
            emitSplineRuntimeRecord(mp.locations().length, DfcSplineStats.SEARCH_BINARY,
                    DfcSplineStats.EXIT_INTERIOR,
                    splineTimingStartSlot, splineTimingResultSlot);
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);
        }

        private void emitLutSplineSegments(int fSlot, IRNode.Spline.Multipoint mp,
                                           BranchScope snap, Label end,
                                           int splineTimingStartSlot, int splineTimingResultSlot) {
            int segmentCount = mp.locations().length - 1;
            int lutIndex = pool.internSpline(
                    DfcSplineSupport.buildSegmentLut(mp.locations(), SPLINE_SEGMENT_LUT_BUCKETS));
            int segmentSlot = allocIntSlot();
            Label fallback = new Label();
            Label[] cases = new Label[segmentCount];
            for (int i = 0; i < segmentCount; i++) {
                cases[i] = new Label();
            }

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL, "splines", OBJECT_ARRAY_DESC);
            ldcInt(lutIndex);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitTypeInsn(Opcodes.CHECKCAST, DFC_SPLINE_SEGMENT_LUT_INTERNAL);
            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_SPLINE_SUPPORT_INTERNAL, "selectSegment",
                    DFC_SPLINE_SELECT_SEGMENT_DESC, false);
            mv.visitVarInsn(Opcodes.ISTORE, segmentSlot);

            mv.visitVarInsn(Opcodes.ILOAD, segmentSlot);
            mv.visitTableSwitchInsn(0, segmentCount - 1, fallback, cases);
            for (int i = 0; i < segmentCount; i++) {
                mv.visitLabel(cases[i]);
                restoreBranch(snap);
                emitInterpolatedSegment(fSlot, mp, i);
                emitSplineRuntimeRecord(mp.locations().length, DfcSplineStats.SEARCH_LUT,
                        DfcSplineStats.EXIT_INTERIOR, splineTimingStartSlot, splineTimingResultSlot);
                restoreBranch(snap);
                mv.visitJumpInsn(Opcodes.GOTO, end);
            }

            mv.visitLabel(fallback);
            restoreBranch(snap);
            emitBinarySplineSegments(fSlot, mp, snap, end, 0, segmentCount - 1,
                    splineTimingStartSlot, splineTimingResultSlot);
        }

        private void emitSplineRuntimeRecord(int pointCount, int searchMode, int exitKind,
                                             int splineTimingStartSlot, int splineTimingResultSlot) {
            if (!SPLINE_RUNTIME_STATS_ENABLED) {
                return;
            }
            mv.visitVarInsn(Opcodes.FSTORE, splineTimingResultSlot);
            Label skipRecord = new Label();
            mv.visitVarInsn(Opcodes.LLOAD, splineTimingStartSlot);
            mv.visitInsn(Opcodes.LCONST_0);
            mv.visitInsn(Opcodes.LCMP);
            mv.visitJumpInsn(Opcodes.IFEQ, skipRecord);
            mv.visitLdcInsn(classInternalName.replace('/', '.'));
            mv.visitLdcInsn(pointCount);
            mv.visitLdcInsn(searchMode);
            mv.visitLdcInsn(exitKind);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(Opcodes.LLOAD, splineTimingStartSlot);
            mv.visitInsn(Opcodes.LSUB);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DFC_SPLINE_STATS_INTERNAL, "recordDetailedSampled",
                    DFC_SPLINE_STATS_RECORD_DETAILED_DESC, false);
            mv.visitLabel(skipRecord);
            mv.visitVarInsn(Opcodes.FLOAD, splineTimingResultSlot);
        }

        private void emitInterpolatedSegment(int fSlot, IRNode.Spline.Multipoint mp, int i) {
            float l0 = mp.locations()[i];
            float l1 = mp.locations()[i + 1];
            float d0 = mp.derivatives()[i];
            float d1 = mp.derivatives()[i + 1];

            // t = (f - l0) / (l1 - l0)
            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(l0);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitLdcInsn(l1 - l0);
            mv.visitInsn(Opcodes.FDIV);
            int tSlot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, tSlot);

            // y0 = sub-spline i
            emitSplineAsFloat(mp.values().get(i));
            int y0Slot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, y0Slot);

            // y1 = sub-spline i+1
            emitSplineAsFloat(mp.values().get(i + 1));
            int y1Slot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, y1Slot);

            // f8 = d0 * (l1 - l0) - (y1 - y0)
            mv.visitLdcInsn(d0 * (l1 - l0));
            mv.visitVarInsn(Opcodes.FLOAD, y1Slot);
            mv.visitVarInsn(Opcodes.FLOAD, y0Slot);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitInsn(Opcodes.FSUB);
            int f8Slot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, f8Slot);

            // f9 = -d1 * (l1 - l0) + (y1 - y0)
            mv.visitLdcInsn(-d1 * (l1 - l0));
            mv.visitVarInsn(Opcodes.FLOAD, y1Slot);
            mv.visitVarInsn(Opcodes.FLOAD, y0Slot);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitInsn(Opcodes.FADD);
            int f9Slot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, f9Slot);

            // y0 + t*(y1-y0) + t*(1-t)*(f8 + t*(f9-f8))
            mv.visitVarInsn(Opcodes.FLOAD, y0Slot);
            mv.visitVarInsn(Opcodes.FLOAD, tSlot);
            mv.visitVarInsn(Opcodes.FLOAD, y1Slot);
            mv.visitVarInsn(Opcodes.FLOAD, y0Slot);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FADD);
            mv.visitVarInsn(Opcodes.FLOAD, tSlot);
            mv.visitInsn(Opcodes.FCONST_1);
            mv.visitVarInsn(Opcodes.FLOAD, tSlot);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitVarInsn(Opcodes.FLOAD, f8Slot);
            mv.visitVarInsn(Opcodes.FLOAD, tSlot);
            mv.visitVarInsn(Opcodes.FLOAD, f9Slot);
            mv.visitVarInsn(Opcodes.FLOAD, f8Slot);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FADD);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FADD);
        }

        private void emitLinearExtend(int fSlot, float[] locs, float[] derivs, int idx,
                                      IRNode.Spline value) {
            float d = derivs[idx];
            emitSplineAsFloat(value);
            if (d == 0.0F) return;
            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[idx]);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitLdcInsn(d);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FADD);
        }

        private void emitSplineAsFloat(IRNode.Spline value) {
            switch (value) {
                case IRNode.Spline.Constant sc -> mv.visitLdcInsn(sc.value());
                case IRNode.Spline.Multipoint inner -> {
                    // Route through emit() so the splitter's helper-extraction is honoured
                    // for nested splines too. Returns a double; convert to float.
                    emit(inner);
                    mv.visitInsn(Opcodes.D2F);
                }
            }
        }

        private int allocFloatSlot() {
            int slot = nextLocal;
            nextLocal += 1;
            return slot;
        }

        /* ---------------- invoke / blend ---------------- */

        /**
         * Straight {@code pool[i].compute(ctx)} — do not wrap every extern in a
         * cache try/miss path: most externs are not {@code NoiseChunk} cache
         * wrappers, so a universal wrapper regresses hot paths (extra static
         * call, NaN check, second {@code GETFIELD} on miss). A future opt-in
         * can target only known wrapper slots at compile time.
         */
        private void emitInvoke(int idx) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            if (castSelfForSubclassNoiseFields) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
            }
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(idx), DENSITY_FUNCTION_DESC);
            mv.visitVarInsn(Opcodes.ALOAD, contextLocal);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DENSITY_FUNCTION_INTERNAL,
                    "compute", "(L" + FUNCTION_CONTEXT_INTERNAL + ";)D", true);
        }

        /** Marker sites flagged as cell-cache wrappers may use {@link DfcCacheFastPath}. */
        private void emitMarkerInvoke(int idx) {
            if (directInterpolatorMarkers) {
                if (directInterpolatorValueArraySlot >= 0) {
                    mv.visitVarInsn(Opcodes.ALOAD, directInterpolatorValueArraySlot);
                    emitGeneratedThis();
                    mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName,
                            externInterpolatorIndexFieldName(idx), "I");
                    mv.visitInsn(Opcodes.DALOAD);
                } else {
                    mv.visitVarInsn(Opcodes.ALOAD, directInterpolatorSoALocal);
                    emitGeneratedThis();
                    mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName,
                            externInterpolatorIndexFieldName(idx), "I");
                    mv.visitVarInsn(Opcodes.DLOAD, directInterpolatorXSlot);
                    mv.visitVarInsn(Opcodes.DLOAD, directInterpolatorYSlot);
                    mv.visitVarInsn(Opcodes.DLOAD, directInterpolatorZSlot);
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, INTERPOLATOR_SOA_PATH_INTERNAL,
                            "bts$getInterpolatorFillingValue", INTERPOLATOR_FILLING_DELTA_DESC, true);
                }
                return;
            }
            if (pool.externHasCacheWrapperFastPath(idx)) {
                int externSlot = allocRefSlot();
                int arrayAccessSlot = allocRefSlot();
                int cacheAccessSlot = allocRefSlot();
                Label typedCache = new Label();
                Label slowCompute = new Label();
                Label end = new Label();

                emitGeneratedThis();
                mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(idx), DENSITY_FUNCTION_DESC);
                mv.visitVarInsn(Opcodes.ASTORE, externSlot);

                emitGeneratedThis();
                mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName,
                        externCacheArrayFieldName(idx), DFC_CELL_CACHE_ARRAY_INDEX_ACCESS_DESC);
                mv.visitVarInsn(Opcodes.ASTORE, arrayAccessSlot);

                mv.visitVarInsn(Opcodes.ALOAD, arrayAccessSlot);
                mv.visitJumpInsn(Opcodes.IFNULL, typedCache);
                mv.visitVarInsn(Opcodes.ALOAD, externSlot);
                mv.visitVarInsn(Opcodes.ALOAD, arrayAccessSlot);
                mv.visitVarInsn(Opcodes.ALOAD, contextLocal);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CACHE_FAST_PATH_INTERNAL, "computeKnownArrayIndexAccess",
                        CACHE_FAST_ARRAY_INDEX_READ_DESC, false);
                mv.visitJumpInsn(Opcodes.GOTO, end);

                mv.visitLabel(typedCache);
                emitGeneratedThis();
                mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName,
                        externCacheAccessFieldName(idx), DFC_CELL_CACHE_ACCESS_DESC);
                mv.visitVarInsn(Opcodes.ASTORE, cacheAccessSlot);

                mv.visitVarInsn(Opcodes.ALOAD, cacheAccessSlot);
                mv.visitJumpInsn(Opcodes.IFNULL, slowCompute);
                mv.visitVarInsn(Opcodes.ALOAD, externSlot);
                mv.visitVarInsn(Opcodes.ALOAD, cacheAccessSlot);
                mv.visitVarInsn(Opcodes.ALOAD, contextLocal);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CACHE_FAST_PATH_INTERNAL, "computeKnownAccess",
                        CACHE_FAST_TYPED_READ_DESC, false);
                mv.visitJumpInsn(Opcodes.GOTO, end);

                mv.visitLabel(slowCompute);
                mv.visitVarInsn(Opcodes.ALOAD, externSlot);
                mv.visitVarInsn(Opcodes.ALOAD, contextLocal);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CACHE_FAST_PATH_INTERNAL, "computeKnownNonAccess",
                        CACHE_FAST_READ_DESC, false);
                mv.visitLabel(end);
            } else {
                emitGeneratedThis();
                mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(idx), DENSITY_FUNCTION_DESC);
                mv.visitVarInsn(Opcodes.ALOAD, contextLocal);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DENSITY_FUNCTION_INTERNAL,
                        "compute", "(L" + FUNCTION_CONTEXT_INTERNAL + ";)D", true);
            }
        }

        private void emitGeneratedThis() {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            if (castSelfForSubclassNoiseFields) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
            }
        }

        private void emitInlinedBlendedNoise(IRNode.InlinedBlendedNoise n) {
            BlendedNoiseByteEmitter.emit(
                    mv, classInternalName, pool, n.blendedSpecIndex(), castSelfForSubclassNoiseFields, this::allocDoubleSlot);
        }

        private void emitBlendDensity(IRNode.BlendDensity bd) {
            // Evaluate the input first with a clean operand stack so any branchy code
            // inside (RangeChoice arms, nested Spline.Multipoint ladders) doesn't have
            // to merge frames while Blender+ctx are sitting on the operand stack. The
            // previous emission order pushed Blender, ctx, then ran emit(bd.input()) —
            // when the input contained a BranchScope-using subtree, ASM's COMPUTE_FRAMES
            // would merge divergent arm frames at the join label and slots written on
            // only some arms became TOP. Subsequent DLOADs of those slots from the
            // outer scope's spill table then failed verification with
            // "get long/double overflows locals" (see CompiledDF_18 in run-output-fixed.log).
            // Spilling to a fresh slot first costs one extra DSTORE/DLOAD pair per call
            // and serialises the side-effects cleanly.
            emit(bd.input());
            int dSlot = allocDoubleSlot();
            mv.visitVarInsn(Opcodes.DSTORE, dSlot);

            mv.visitVarInsn(Opcodes.ALOAD, contextLocal);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT_INTERNAL,
                    "getBlender", "()Lnet/minecraft/world/level/levelgen/blending/Blender;", true);
            mv.visitVarInsn(Opcodes.ALOAD, contextLocal);
            mv.visitVarInsn(Opcodes.DLOAD, dSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/world/level/levelgen/blending/Blender",
                    "blendDensity",
                    "(L" + FUNCTION_CONTEXT_INTERNAL + ";D)D", false);
        }

        private void ldcInt(int v) {
            if (v >= -1 && v <= 5) mv.visitInsn(Opcodes.ICONST_0 + v);
            else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, v);
            else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, v);
            else mv.visitLdcInsn(v);
        }
    }
}

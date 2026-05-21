package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcNativePlanningStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcSplineStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.CellLatticeOption;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.vector.DfcVectorSupport;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.RefCount;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.SlabInnerNativeProgram;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.SlabNativeBatchPlan;
import dev.sixik.generator_accelerator.common.density.compiler.natives.CodegenNativeNoise;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * ASM emitter for the IR.
 *
 * <p>Layout of the generated class:
 * <pre>
 * public final class CompiledDF_N extends CompiledDensityFunction {
 *     public CompiledDF_N(double[] c, NormalNoise[] n, Object[] s, Object[] noiseOctaves,
 *                        DensityFunction[] e, double mn, double mx, MethodHandle[] hh,
 *                        NativeNoiseRegistry.HandleSet nativeHandles, byte[] slabProgram,
 *                        double[] slabConsts, MethodHandle ctorMH) {
 *         super(c, n, s, noiseOctaves, e, mn, mx, hh, nativeHandles, slabProgram,
 *               slabConsts, ctorMH);
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
     * <p>The default stays conservative for 3-4 point splines, but flips 5+ point splines
     * to binary search because telemetry showed that bucket is common enough to matter while
     * still being exact and stable in practice.
     */
    public static final int SPLINE_LINEAR_SEARCH_MAX_POINTS =
            Math.max(2, Integer.getInteger("dfc.codegen.splineLinearSearchMaxPoints", 4));
    static final SplineSearchMode SPLINE_SEARCH_MODE =
            parseSplineSearchMode(System.getProperty("dfc.codegen.splineSearchMode", "auto"));
    public static final boolean SPLINE_RUNTIME_STATS_ENABLED =
            Boolean.parseBoolean(System.getProperty("dfc.codegen.splineRuntimeStats.emit", "true"));
    /**
     * Optional exact LUT-guided segment selection for large interior splines.
     *
     * <p>The table predicts a likely segment and a tiny runtime fix-up loop walks to the
     * true segment before evaluating the existing cubic interpolation, so output stays
     * bit-for-bit equivalent to the current implementation.
     */
    public static final boolean SPLINE_SEGMENT_LUT_ENABLED =
            Boolean.getBoolean("dfc.codegen.splineSegmentLut");
    public static final int SPLINE_SEGMENT_LUT_MIN_POINTS =
            Math.max(5, Integer.getInteger("dfc.codegen.splineSegmentLutMinPoints", 9));
    public static final int SPLINE_SEGMENT_LUT_BUCKETS =
            Math.max(8, Integer.getInteger("dfc.codegen.splineSegmentLutBuckets", 128));

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
    private static final String DFC_NOISE_CHUNK_SLICE_ACCESS_INTERNAL =
            "dev/sixik/generator_accelerator/common/noise/DfcNoiseChunkSliceAccess";
    private static final String DFC_VECTOR_SUPPORT_INTERNAL = Type.getInternalName(DfcVectorSupport.class);
    private static final String DOUBLE_VECTOR_FROM_ARRAY_DESC =
            "(L" + DfcVectorSupport.VECTOR_SPECIES_INTERNAL + ";[DI)L" + DfcVectorSupport.DOUBLE_VECTOR_INTERNAL + ";";
    private static final String DOUBLE_VECTOR_INTO_ARRAY_DESC = "([DI)V";
    private static final String FUNCTION_CONTEXT_INTERNAL =
            "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    /** {@link DfcCacheFastPath#computeWithOptionalDirectRead}. */
    private static final String CACHE_FAST_READ_DESC =
            "(" + DENSITY_FUNCTION_DESC + "L" + FUNCTION_CONTEXT_INTERNAL + ";)D";
    private static final String CONTEXT_PROVIDER_INTERNAL =
            "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider";
    private static final String METHOD_HANDLE_INTERNAL = "java/lang/invoke/MethodHandle";
    private static final String METHOD_HANDLE_ARRAY_DESC = "[Ljava/lang/invoke/MethodHandle;";
    private static final String OBJECT_ARRAY_DESC = "[Ljava/lang/Object;";
    private static final String IMPROVED_NOISE_DESC = "L" + IMPROVED_NOISE_INTERNAL + ";";
    private static final String RUNTIME_INTERNAL =
            "dev/sixik/generator_accelerator/common/density/compiler/compiler/runtime/Runtime";
    private static final String REGIONAL_NOISE_BRICK_CACHE_INTERNAL =
            "dev/sixik/generator_accelerator/common/noise/region/GARegionalNoiseBrickCache";
    private static final String MTH_INTERNAL = "net/minecraft/util/Mth";
    private static final String NATIVE_HANDLE_SET_INTERNAL =
            "dev/sixik/generator_accelerator/common/density/compiler/natives/NativeNoiseRegistry$HandleSet";
    private static final String NATIVE_HANDLE_SET_DESC = "L" + NATIVE_HANDLE_SET_INTERNAL + ";";
    private static final String NOISE5_DESC = "(DDDDD)D";
    private static final String PLAIN_NORMAL_NOISE_SAMPLE_DESC =
            "(L" + NORMAL_NOISE_INTERNAL + ";L" + FUNCTION_CONTEXT_INTERNAL + ";DD)D";

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
     * <p>The trailing {@code HandleSet} owns opaque native noise handles; may be null.
     */
    public static final String CTOR_DESC =
            "([D[L" + NORMAL_NOISE_INTERNAL + ";[Ljava/lang/Object;[Ljava/lang/Object;[L"
                    + DENSITY_FUNCTION_INTERNAL + ";DD" + METHOD_HANDLE_ARRAY_DESC + NATIVE_HANDLE_SET_DESC + "[B[D"
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
            Boolean.getBoolean("dfc.codegen.cellFillDirectExternResidual");
    /**
     * Experimental add-extern cell-fill specialization.
     *
     * <p>Left disabled by default because real worldgen runs hit ASM 9.8 frame-merge
     * failures ({@code Frame.merge} / {@code ArrayIndexOutOfBoundsException}) on some
     * generated root shapes. Re-enable only when actively iterating on this path or when a
     * future rewrite simplifies the CFG enough to make frame computation stable again.
     */
    public static final boolean CELL_FILL_ADD_EXTERN_OVERRIDE_ENABLED =
            Boolean.getBoolean("dfc.codegen.cellFillAddExternOverride");

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

    private static final String NATIVE_BRIDGE_INTERNAL =
            "dev/sixik/generator_accelerator/common/density/compiler/natives/DfcNativeBridge";

    /** {@code DfcNativeBridge.slabInnerEval} — ends with slabLayout, colXi, colZi, columnCellHeight. */
    private static final String SLAB_INNER_EVAL_DESC = "([B[D[[DIIIIIIIID[DI)V";

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

    /** Batched cell-lattice inner: reads precomputed native noise from {@code nativeSlabOut[slot][flatIdx]}. */
    public static final String LATTICE_INNER_BATCHED_NAME = "lattice_inner_batched";
    /** Batched inner for XZ lattice (same descriptor; different static body + indy name). */
    public static final String LATTICE_INNER_BATCHED_XZ_NAME = "lattice_inner_batched_xz";
    /** Batched XZ inner for slice providers; native rows are addressed by {@code NoiseChunk.arrayIndex}. */
    public static final String LATTICE_INNER_BATCHED_XZ_SLICE_NAME = "lattice_inner_batched_xz_slice";
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

    /**
     * Private XZ+slab {@code fillArray} body, split from the public override so one large CFG
     * does not trigger ASM 9.8+ {@code Frame.merge} AIOOBE at {@code visitMaxs(0,0)} with
     * {@link ClassWriter#COMPUTE_FRAMES} on the same method as a {@code fillArray} fallback
     * edge to the supertype.
     */
    private static final String LATTICE_XZ_SLAB_FILL_BODY = "dfc$latticeXzSlabFill";
    private static final int SLAB_INDEX_XZ = 0;
    private static final int SLAB_INDEX_Y_COLUMN = 1;
    private static final int SLAB_INDEX_ARRAY_INDEX = 2;

    /** {@code (CompiledDensityFunction, FunctionContext, double, double[][]) -> double} */
    public static final String LATTICE_INNER_BATCHED_DESC =
            "(L" + COMPILED_BASE_INTERNAL + ";L" + FUNCTION_CONTEXT_INTERNAL + ";D[[D)D";

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
        emitNativeHandleFields(cw, pool);
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
        byte[] slabInnerBc = null;
        double[] slabInnerConsts = null;
        boolean slabInnerApplyBlendDensity = false;
        if (CELL_LATTICE_ENABLED && !(root instanceof IRNode.Const)) {
            var planOpt = CellLatticeOption.analyze(root);
            if (planOpt.isPresent()) {
                CellLatticeOption.LatticePlan plan = planOpt.get();
                SlabNativeBatchPlan slabPlan = null;
                boolean nativeOpsEnabled = CodegenNativeNoise.emitNativeOps();
                DfcNativePlanningStats.recordLatticeRoot(nativeOpsEnabled,
                        plan.hoistAxis() == CellLatticeOption.Axis.XZ_ONLY);
                if (nativeOpsEnabled) {
                    slabPlan = SlabNativeBatchPlan.analyze(root, plan, pool.noiseSpecCount(),
                            pool.blendedNoiseSpecCount()).orElse(null);
                }
                DfcNativePlanningStats.recordSlabPlan(slabPlan != null);
                boolean yHoist = plan.hoistAxis() == CellLatticeOption.Axis.Y_ONLY;
                String preName = yHoist ? LATTICE_Y_NAME : LATTICE_XZ_NAME;
                String innerName = yHoist ? LATTICE_INNER_NAME : LATTICE_INNER_XZ_NAME;
                String batchedName = yHoist ? LATTICE_INNER_BATCHED_NAME : LATTICE_INNER_BATCHED_XZ_NAME;
                emitLatticePrecomputeHelper(cw, classInternalName, plan, helpers, preName);
                emitLatticeInnerHelper(cw, classInternalName, root, plan, helpers, innerName);
                boolean nativeSlabVm = false;
                if (slabPlan != null) {
                    emitLatticeSlabCoordMethods(cw, classInternalName, helpers, slabPlan, plan.hoistAxis());
                    emitLatticeInnerBatchedHelper(cw, classInternalName, root, plan, helpers, slabPlan, batchedName);
                    if (plan.hoistAxis() == CellLatticeOption.Axis.XZ_ONLY) {
                        emitLatticeSliceSlabCoordMethods(cw, classInternalName, helpers, slabPlan);
                        emitLatticeInnerBatchedHelper(cw, classInternalName, root, plan, helpers, slabPlan,
                                LATTICE_INNER_BATCHED_XZ_SLICE_NAME, SLAB_INDEX_ARRAY_INDEX);
                    }
                    var slabProg = SlabInnerNativeProgram.tryCompile(root, plan, slabPlan, extracted);
                    if (slabProg.isPresent()) {
                        slabInnerBc = slabProg.get().bytecode();
                        slabInnerConsts = slabProg.get().constants();
                        slabInnerApplyBlendDensity = slabProg.get().applyBlendDensity();
                        nativeSlabVm = slabInnerBc.length > 0;
                        if (slabInnerApplyBlendDensity && plan.hoistAxis() != CellLatticeOption.Axis.XZ_ONLY) {
                            nativeSlabVm = false;
                        }
                    }
                    DfcNativePlanningStats.recordSlabInnerVm(nativeSlabVm);
                }
                emitLatticeFillArrayOverride(cw, classInternalName, slabPlan, pool, nativeSlabVm,
                        slabInnerApplyBlendDensity, plan);
                latticeEmitted = true;
            }
        }

        if (!latticeEmitted && root instanceof IRNode.Const c) {
            emitConstRootFillArrayOverride(cw, c.value());
        }
        boolean cellAddLatticeSpecialized = false;
        boolean cellAddExternSpecialized = false;
        if (!latticeEmitted) {
            cellAddLatticeSpecialized = emitCellFillAddScalarOverrideIfPossible(cw, classInternalName, root, helpers);
            if (!cellAddLatticeSpecialized && CELL_FILL_ADD_EXTERN_OVERRIDE_ENABLED) {
                cellAddExternSpecialized = emitCellFillAddExternOverrideIfPossible(cw, classInternalName, root, helpers, pool);
            }
        }

        cw.visitEnd();
        return new Result(cw.toByteArray(), helpers.emittedCount(), latticeEmitted,
                cellAddLatticeSpecialized, cellAddExternSpecialized, slabInnerBc, slabInnerConsts);
    }

    /**
     * Bytecode + count of regular helper methods generated + whether a cell-lattice
     * fast path was emitted. {@code latticeEmitted} is purely diagnostic — it is
     * surfaced through {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RouterPipeline}
     * so {@code /dfc stats} can report "lattice plans: K / N roots".
     */
    public record Result(byte[] bytecode, int helpersEmitted, boolean latticeEmitted,
                         boolean cellAddLatticeSpecialized,
                         boolean cellAddExternSpecialized,
                         byte[] slabInnerProgram, double[] slabInnerConsts) {}

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

    private static int nativeHandleCount(ConstantPool pool) {
        return pool.noiseSpecCount() + pool.blendedNoiseSpecCount();
    }

    static String nativeHandleFieldName(int index) {
        return "nativeHandle_" + index;
    }

    private static void emitNativeHandleFields(ClassWriter cw, ConstantPool pool) {
        for (int i = 0; i < nativeHandleCount(pool); i++) {
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                    nativeHandleFieldName(i), "J", null, null).visitEnd();
        }
    }

    /**
     * Per-octave field for {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode.InlinedBlendedNoise}:
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

    private static void emitExternFields(ClassWriter cw, ConstantPool pool) {
        int n = pool.externCount();
        for (int i = 0; i < n; i++) {
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                    externFieldName(i), DENSITY_FUNCTION_DESC, null, null).visitEnd();
        }
    }

    private static void emitConstructor(ClassWriter cw, String classInternalName, ConstantPool pool) {
        // (double[], NormalNoise[], Object[], Object[], DensityFunction[], double, double,
        //  MethodHandle[], NativeNoiseRegistry.HandleSet, byte[], double[], MethodHandle)
        // Slot layout: this=0, constants=1, noises=2, splines=3, noiseOctaves=4,
        // externs=5, minValue=6/7, maxValue=8/9, helperHandles=10, nativeHandles=11,
        // slabInnerProgram=12, slabInnerConsts=13, constructorMH=14.
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
        mv.visitVarInsn(Opcodes.ALOAD, 12);
        mv.visitVarInsn(Opcodes.ALOAD, 13);
        mv.visitVarInsn(Opcodes.ALOAD, 14);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, "<init>", CTOR_DESC, false);

        for (int i = 0; i < nativeHandleCount(pool); i++) {
            emitNativeHandlePutfield(mv, classInternalName, i);
        }

        for (int i = 0; i < pool.externCount(); i++) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            ldcIntStatic(mv, i);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitFieldInsn(Opcodes.PUTFIELD, classInternalName, externFieldName(i), DENSITY_FUNCTION_DESC);
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

    private static void emitNativeHandlePutfield(MethodVisitor mv, String classInternalName, int handleIndex) {
        Label zero = new Label();
        Label put = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 11);
        mv.visitJumpInsn(Opcodes.IFNULL, zero);
        mv.visitVarInsn(Opcodes.ALOAD, 11);
        ldcIntStatic(mv, handleIndex);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NATIVE_HANDLE_SET_INTERNAL, "handle", "(I)J", false);
        mv.visitJumpInsn(Opcodes.GOTO, put);
        mv.visitLabel(zero);
        mv.visitInsn(Opcodes.LCONST_0);
        mv.visitLabel(put);
        mv.visitFieldInsn(Opcodes.PUTFIELD, classInternalName, nativeHandleFieldName(handleIndex), "J");
    }

    private static void emitNativeHandleFieldLoad(MethodVisitor mv, String classInternalName,
                                                  int handleIndex, boolean castSelfToSubclass) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (castSelfToSubclass) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
        }
        mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, nativeHandleFieldName(handleIndex), "J");
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
                CoordinateSlotUse.singletonIdentitySet(plan.hoistedSubtree()), null));

        EmitState st = new EmitState(mv, classInternalName, helpers, /* castSelfForSubclassNoiseFields */ true);
        st.preinstallSpill(plan.hoistedSubtree(), yPrecomputedSlot);
        st.emit(root);

        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static String latticeSlabCoordMethodName(int slotIndex) {
        return "lattice_slab_coord_" + slotIndex;
    }

    private static String latticeSliceSlabCoordMethodName(int slotIndex) {
        return "lattice_slice_slab_coord_" + slotIndex;
    }

    private static void emitLatticeSlabCoordMethods(ClassWriter cw, String classInternalName,
                                                    HelperRegistry helpers,
                                                    SlabNativeBatchPlan slabPlan,
                                                    CellLatticeOption.Axis latticeAxis) {
        String desc = "(L" + COMPILED_BASE_INTERNAL + ";" + NOISE_CHUNK_DESC + "I[D[D[D)V";
        int i = 0;
        for (SlabNativeBatchPlan.Slot s : slabPlan.slots()) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    latticeSlabCoordMethodName(i), desc, null, null);
            mv.visitCode();
            // 0=self, 1=nc, 2=flatIdx, 3=xs, 4=ys, 5=zs — stash before prologue clobbers 2–4
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitVarInsn(Opcodes.ISTORE, 20);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ASTORE, 21);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitVarInsn(Opcodes.ASTORE, 22);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.ASTORE, 23);
            if (latticeAxis == CellLatticeOption.Axis.XZ_ONLY) {
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.ISUB);
                mv.visitVarInsn(Opcodes.ILOAD, 20);
                mv.visitInsn(Opcodes.ISUB);
                mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");
            }
            CoordinateSlotUse slotUse = switch (s) {
                case SlabNativeBatchPlan.NormalSlot ns -> CoordinateSlotUse.analyzeCoordinates(
                        helpers.extracted, ns.noise().coordX(), ns.noise().coordY(), ns.noise().coordZ());
                case SlabNativeBatchPlan.BlendedSlot ignored -> CoordinateSlotUse.ALL;
                case SlabNativeBatchPlan.MarkerSlot ignored -> CoordinateSlotUse.NONE;
            };
            emitCoordPrologue(mv, slotUse);
            switch (s) {
                case SlabNativeBatchPlan.NormalSlot ns -> {
                    EmitState st = new EmitState(mv, classInternalName, helpers, true);
                    st.reserveLocalsFrom(24);
                    mv.visitVarInsn(Opcodes.ALOAD, 21);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    st.emit(ns.noise().coordX());
                    mv.visitInsn(Opcodes.DASTORE);
                    mv.visitVarInsn(Opcodes.ALOAD, 22);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    st.emit(ns.noise().coordY());
                    mv.visitInsn(Opcodes.DASTORE);
                    mv.visitVarInsn(Opcodes.ALOAD, 23);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    st.emit(ns.noise().coordZ());
                    mv.visitInsn(Opcodes.DASTORE);
                }
                case SlabNativeBatchPlan.BlendedSlot ignored -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 21);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    mv.visitVarInsn(Opcodes.ILOAD, 2);
                    mv.visitInsn(Opcodes.I2D);
                    mv.visitInsn(Opcodes.DASTORE);
                    mv.visitVarInsn(Opcodes.ALOAD, 22);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    mv.visitVarInsn(Opcodes.ILOAD, 3);
                    mv.visitInsn(Opcodes.I2D);
                    mv.visitInsn(Opcodes.DASTORE);
                    mv.visitVarInsn(Opcodes.ALOAD, 23);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    mv.visitVarInsn(Opcodes.ILOAD, 4);
                    mv.visitInsn(Opcodes.I2D);
                    mv.visitInsn(Opcodes.DASTORE);
                }
                case SlabNativeBatchPlan.MarkerSlot ignored -> {
                }
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            i++;
        }
    }

    private static void emitLatticeSliceSlabCoordMethods(ClassWriter cw, String classInternalName,
                                                         HelperRegistry helpers,
                                                         SlabNativeBatchPlan slabPlan) {
        String desc = "(L" + COMPILED_BASE_INTERNAL + ";" + NOISE_CHUNK_DESC + "I[D[D[D)V";
        int i = 0;
        for (SlabNativeBatchPlan.Slot s : slabPlan.slots()) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    latticeSliceSlabCoordMethodName(i), desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitVarInsn(Opcodes.ISTORE, 20);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ASTORE, 21);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitVarInsn(Opcodes.ASTORE, 22);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.ASTORE, 23);

            CoordinateSlotUse slotUse = switch (s) {
                case SlabNativeBatchPlan.NormalSlot ns -> CoordinateSlotUse.analyzeCoordinates(
                        helpers.extracted, ns.noise().coordX(), ns.noise().coordY(), ns.noise().coordZ());
                case SlabNativeBatchPlan.BlendedSlot ignored -> CoordinateSlotUse.ALL;
                case SlabNativeBatchPlan.MarkerSlot ignored -> CoordinateSlotUse.NONE;
            };
            emitCoordPrologue(mv, slotUse);
            switch (s) {
                case SlabNativeBatchPlan.NormalSlot ns -> {
                    EmitState st = new EmitState(mv, classInternalName, helpers, true);
                    st.reserveLocalsFrom(24);
                    mv.visitVarInsn(Opcodes.ALOAD, 21);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    st.emit(ns.noise().coordX());
                    mv.visitInsn(Opcodes.DASTORE);
                    mv.visitVarInsn(Opcodes.ALOAD, 22);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    st.emit(ns.noise().coordY());
                    mv.visitInsn(Opcodes.DASTORE);
                    mv.visitVarInsn(Opcodes.ALOAD, 23);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    st.emit(ns.noise().coordZ());
                    mv.visitInsn(Opcodes.DASTORE);
                }
                case SlabNativeBatchPlan.BlendedSlot ignored -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 21);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    mv.visitVarInsn(Opcodes.ILOAD, 2);
                    mv.visitInsn(Opcodes.I2D);
                    mv.visitInsn(Opcodes.DASTORE);
                    mv.visitVarInsn(Opcodes.ALOAD, 22);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    mv.visitVarInsn(Opcodes.ILOAD, 3);
                    mv.visitInsn(Opcodes.I2D);
                    mv.visitInsn(Opcodes.DASTORE);
                    mv.visitVarInsn(Opcodes.ALOAD, 23);
                    mv.visitVarInsn(Opcodes.ILOAD, 20);
                    mv.visitVarInsn(Opcodes.ILOAD, 4);
                    mv.visitInsn(Opcodes.I2D);
                    mv.visitInsn(Opcodes.DASTORE);
                }
                case SlabNativeBatchPlan.MarkerSlot ignored -> {
                }
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            i++;
        }
    }

    private static void emitLatticeInnerBatchedHelper(ClassWriter cw, String classInternalName,
                                                      IRNode root,
                                                      CellLatticeOption.LatticePlan plan,
                                                      HelperRegistry helpers,
                                                      SlabNativeBatchPlan slabPlan,
                                                      String batchedMethodName) {
        int slabIndexMode = plan.hoistAxis() == CellLatticeOption.Axis.XZ_ONLY
                ? SLAB_INDEX_Y_COLUMN
                : SLAB_INDEX_XZ;
        emitLatticeInnerBatchedHelper(cw, classInternalName, root, plan, helpers, slabPlan,
                batchedMethodName, slabIndexMode);
    }

    private static void emitLatticeInnerBatchedHelper(ClassWriter cw, String classInternalName,
                                                      IRNode root,
                                                      CellLatticeOption.LatticePlan plan,
                                                      HelperRegistry helpers,
                                                      SlabNativeBatchPlan slabPlan,
                                                      String batchedMethodName,
                                                      int slabIndexMode) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                batchedMethodName, LATTICE_INNER_BATCHED_DESC, null, null);
        mv.visitCode();
        // (self, ctx, precomputed D, nativeSlabOut [[D)D — slots 0,1,2-3,4. Move precomputed D to 5-6
        // before ASTORE of [[D: DSTORE 5 occupies 5 and 6 and must not follow an ASTORE into 6.
        // Stash [[D at 8; after preinstallSpill(5) the emitter's nextLocal is 7, so without a guard
        // allocDoubleSlot() would use 7-8 and clobber 8 — reserve locals from 9 after preinstall.
        final int yPrecomputedSlot = 5;
        final int slabOutLocal = 8;
        mv.visitVarInsn(Opcodes.DLOAD, 2);
        mv.visitVarInsn(Opcodes.DSTORE, yPrecomputedSlot);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ASTORE, slabOutLocal);
        // Descriptor uses FunctionContext, but slab batch indexing reads NoiseChunk fields on local 1
        // (see EmitState.emitSlabNoiseSampleLoad). Narrow so bytecode verifies; lattice fillArray only
        // invokes this with a NoiseChunk context.
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, NOISE_CHUNK_INTERNAL);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        IdentityHashMap<IRNode, Integer> slabMap = new IdentityHashMap<>();
        for (SlabNativeBatchPlan.Slot s : slabPlan.slots()) {
            IRNode key = switch (s) {
                case SlabNativeBatchPlan.NormalSlot ns -> ns.noise();
                case SlabNativeBatchPlan.BlendedSlot bs -> bs.noise();
                case SlabNativeBatchPlan.MarkerSlot ms -> ms.marker();
            };
            slabMap.put(key, s.slotIndex());
        }
        emitCoordPrologue(mv, CoordinateSlotUse.analyze(root, helpers.extracted, false,
                CoordinateSlotUse.singletonIdentitySet(plan.hoistedSubtree()), slabMap));
        EmitState st = new EmitState(mv, classInternalName, helpers, true, slabMap, slabOutLocal, slabIndexMode);
        st.preinstallSpill(plan.hoistedSubtree(), yPrecomputedSlot);
        st.reserveLocalsFrom(slabOutLocal + 1);
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
     * <p>When {@link SlabNativeBatchPlan} is present and JNI handles are non-zero at runtime,
     * each Y-slab prefills native noise via {@code DfcNativeBridge.*Batch} and evaluates
     * {@link #LATTICE_INNER_BATCHED_NAME}; otherwise the scalar {@link #LATTICE_INNER_NAME} path runs.
     */
    private static void emitLatticeFillArrayOverride(ClassWriter cw, String classInternalName,
                                                     SlabNativeBatchPlan slabPlan,
                                                     ConstantPool pool,
                                                     boolean nativeSlabInnerVm,
                                                     boolean nativeSlabInnerApplyBlendDensity,
                                                     CellLatticeOption.LatticePlan latticePlan) {
        if (slabPlan == null) {
            emitLatticeFillArrayScalarOnly(cw, classInternalName, latticePlan);
        } else {
            emitLatticeFillArrayWithOptionalSlabBatch(cw, classInternalName, slabPlan, pool, nativeSlabInnerVm,
                    nativeSlabInnerApplyBlendDensity, latticePlan);
        }
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

        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitInsn(Opcodes.ISUB);
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

        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitInsn(Opcodes.ISUB);
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

    private static void emitLatticeFillArrayInnerBatchedXZ(MethodVisitor mv, String batchedIndyName) {
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
        mv.visitVarInsn(Opcodes.ALOAD, 32);
        mv.visitInvokeDynamicInsn(batchedIndyName, LATTICE_INNER_BATCHED_DESC, HELPER_BSM);
        mv.visitInsn(Opcodes.DASTORE);

        mv.visitIincInsn(8, 1);
        mv.visitJumpInsn(Opcodes.GOTO, zLoopHead);
        mv.visitLabel(zLoopExit);

        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(Opcodes.GOTO, xLoopHead);
        mv.visitLabel(xLoopExit);
    }

    /**
     * Puts {@code this.slabInnerProgram} in local 52. {@link #emitNativeSlabInnerAfterBatch} starts with
     * this; the {@code !nativeSlabInnerVm} branch to the batched inner label must do the same so
     * {@link ClassWriter} can merge stack-map frames.
     */
    private static void emitLoadSlabInnerProgramToLocal52(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL, "slabInnerProgram", "[B");
        mv.visitVarInsn(Opcodes.ASTORE, 52);
    }

    private static void emitInitSlabScratchLocals(MethodVisitor mv) {
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 30);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 31);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 32);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 33);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 34);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 35);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 36);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 50);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 52);
    }

    private static void emitAllocateSlabScratch(MethodVisitor mv, int slotCount) {
        ldcIntStatic(mv, slotCount);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "[D");
        mv.visitVarInsn(Opcodes.ASTORE, 32);

        for (int si = 0; si < slotCount; si++) {
            mv.visitVarInsn(Opcodes.ALOAD, 32);
            ldcIntStatic(mv, si);
            mv.visitVarInsn(Opcodes.ILOAD, 30);
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);
            mv.visitInsn(Opcodes.AASTORE);
        }

        mv.visitVarInsn(Opcodes.ILOAD, 30);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);
        mv.visitVarInsn(Opcodes.ASTORE, 33);
        mv.visitVarInsn(Opcodes.ILOAD, 30);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);
        mv.visitVarInsn(Opcodes.ASTORE, 34);
        mv.visitVarInsn(Opcodes.ILOAD, 30);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);
        mv.visitVarInsn(Opcodes.ASTORE, 35);

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NATIVE_BRIDGE_INTERNAL, "useAvx2Path", "()Z", false);
        mv.visitVarInsn(Opcodes.ISTORE, 36);
    }

    private static void emitAllocateSlabInnerScratchIfAvailable(MethodVisitor mv) {
        Label done = new Label();
        emitLoadSlabInnerProgramToLocal52(mv);
        mv.visitVarInsn(Opcodes.ALOAD, 52);
        mv.visitJumpInsn(Opcodes.IFNULL, done);
        mv.visitVarInsn(Opcodes.ALOAD, 52);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IFLE, done);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NATIVE_BRIDGE_INTERNAL, "isAvailable", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, done);
        mv.visitVarInsn(Opcodes.ILOAD, 30);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);
        mv.visitVarInsn(Opcodes.ASTORE, 50);
        mv.visitLabel(done);
    }

    /**
     * After native noise slab JNI fills {@code nativeSlabOut}, optionally run the lattice-inner postfix
     * VM in one JNI call and scatter into {@code values} using {@code NoiseChunk#arrayIndex}.
     */
    private static void emitNativeSlabInnerAfterBatch(MethodVisitor mv, Label yAfterInner, Label batchedJavaInner) {
        mv.visitVarInsn(Opcodes.ALOAD, 50);
        mv.visitJumpInsn(Opcodes.IFNULL, batchedJavaInner);

        mv.visitVarInsn(Opcodes.ALOAD, 52);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL, "slabInnerConsts", "[D");
        mv.visitVarInsn(Opcodes.ALOAD, 32);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "firstNoiseX", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "firstNoiseZ", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellStartBlockY", "I");
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.DLOAD, 11);
        mv.visitVarInsn(Opcodes.ALOAD, 50);
        mv.visitVarInsn(Opcodes.ILOAD, 30);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NATIVE_BRIDGE_INTERNAL, "slabInnerEval", SLAB_INNER_EVAL_DESC, false);

        Label scatterDone = new Label();
        Label scalarScatter = new Label();
        if (DfcVectorSupport.AVAILABLE && DfcVectorSupport.PREFERRED_LANES >= 2) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, DFC_VECTOR_SUPPORT_INTERNAL, "AVAILABLE", "Z");
            mv.visitJumpInsn(Opcodes.IFEQ, scalarScatter);
            mv.visitFieldInsn(Opcodes.GETSTATIC, DFC_VECTOR_SUPPORT_INTERNAL, "PREFERRED_LANES", "I");
            mv.visitVarInsn(Opcodes.ISTORE, 54);
            mv.visitVarInsn(Opcodes.ILOAD, 30);
            mv.visitVarInsn(Opcodes.ILOAD, 54);
            mv.visitJumpInsn(Opcodes.IF_ICMPLT, scalarScatter);

            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
            mv.visitVarInsn(Opcodes.ISTORE, 53);

            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 51);
            Label vecLoop = new Label();
            Label vecDone = new Label();
            mv.visitLabel(vecLoop);
            mv.visitVarInsn(Opcodes.ILOAD, 51);
            mv.visitVarInsn(Opcodes.ILOAD, 54);
            mv.visitInsn(Opcodes.IADD);
            mv.visitVarInsn(Opcodes.ILOAD, 30);
            mv.visitJumpInsn(Opcodes.IF_ICMPGT, vecDone);

            mv.visitFieldInsn(Opcodes.GETSTATIC, DfcVectorSupport.DOUBLE_VECTOR_INTERNAL,
                    "SPECIES_PREFERRED", "L" + DfcVectorSupport.VECTOR_SPECIES_INTERNAL + ";");
            mv.visitVarInsn(Opcodes.ALOAD, 50);
            mv.visitVarInsn(Opcodes.ILOAD, 51);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, DfcVectorSupport.DOUBLE_VECTOR_INTERNAL,
                    "fromArray", DOUBLE_VECTOR_FROM_ARRAY_DESC, false);

            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 53);
            mv.visitVarInsn(Opcodes.ILOAD, 51);
            mv.visitInsn(Opcodes.IADD);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DfcVectorSupport.DOUBLE_VECTOR_INTERNAL,
                    "intoArray", DOUBLE_VECTOR_INTO_ARRAY_DESC, false);

            mv.visitVarInsn(Opcodes.ILOAD, 51);
            mv.visitVarInsn(Opcodes.ILOAD, 54);
            mv.visitInsn(Opcodes.IADD);
            mv.visitVarInsn(Opcodes.ISTORE, 51);
            mv.visitJumpInsn(Opcodes.GOTO, vecLoop);
            mv.visitLabel(vecDone);

            Label remHead = new Label();
            Label remEnd = new Label();
            mv.visitLabel(remHead);
            mv.visitVarInsn(Opcodes.ILOAD, 51);
            mv.visitVarInsn(Opcodes.ILOAD, 30);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, remEnd);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 53);
            mv.visitVarInsn(Opcodes.ILOAD, 51);
            mv.visitInsn(Opcodes.IADD);
            mv.visitVarInsn(Opcodes.ALOAD, 50);
            mv.visitVarInsn(Opcodes.ILOAD, 51);
            mv.visitInsn(Opcodes.DALOAD);
            mv.visitInsn(Opcodes.DASTORE);
            mv.visitIincInsn(51, 1);
            mv.visitJumpInsn(Opcodes.GOTO, remHead);
            mv.visitLabel(remEnd);

            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ILOAD, 53);
            mv.visitVarInsn(Opcodes.ILOAD, 30);
            mv.visitInsn(Opcodes.IADD);
            mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

            mv.visitJumpInsn(Opcodes.GOTO, scatterDone);
        }

        mv.visitLabel(scalarScatter);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 51);
        Label cHead = new Label();
        Label cEnd = new Label();
        mv.visitLabel(cHead);
        mv.visitVarInsn(Opcodes.ILOAD, 51);
        mv.visitVarInsn(Opcodes.ILOAD, 30);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, cEnd);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
        mv.visitInsn(Opcodes.DUP_X1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 50);
        mv.visitVarInsn(Opcodes.ILOAD, 51);
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitIincInsn(51, 1);
        mv.visitJumpInsn(Opcodes.GOTO, cHead);
        mv.visitLabel(cEnd);

        mv.visitLabel(scatterDone);
        mv.visitJumpInsn(Opcodes.GOTO, yAfterInner);
    }

    private static void emitLatticeFillArrayWithOptionalSlabBatch(ClassWriter cw, String classInternalName,
                                                                  SlabNativeBatchPlan slabPlan,
                                                                  ConstantPool pool,
                                                                  boolean nativeSlabInnerVm,
                                                                  boolean nativeSlabInnerApplyBlendDensity,
                                                                  CellLatticeOption.LatticePlan latticePlan) {
        if (latticePlan.hoistAxis() == CellLatticeOption.Axis.XZ_ONLY) {
            emitLatticeFillArrayWithOptionalSlabBatchXz(cw, classInternalName, slabPlan, pool,
                    nativeSlabInnerVm, nativeSlabInnerApplyBlendDensity);
            return;
        }
        String desc = "([DL" + CONTEXT_PROVIDER_INTERNAL + ";)V";
        String coordDesc = "(L" + COMPILED_BASE_INTERNAL + ";" + NOISE_CHUNK_DESC + "I[D[D[D)V";
        String batchNormalDesc = "(J[D[D[D[DIZ)V";
        String batchBlendedDesc = "(J[D[D[D[DIZ)V";
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

        emitInitSlabScratchLocals(mv);
        Label slabSetupDone = new Label();
        int nn = pool.noiseSpecCount();
        if (!slabPlan.isEmpty()) {
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitJumpInsn(Opcodes.IFLE, slabSetupDone);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitJumpInsn(Opcodes.IFLE, slabSetupDone);

            for (SlabNativeBatchPlan.Slot s : slabPlan.slots()) {
                if (s instanceof SlabNativeBatchPlan.MarkerSlot) {
                    continue;
                }
                emitNativeHandleFieldLoad(mv, classInternalName, s.nativeHandleIndex(nn), false);
                mv.visitInsn(Opcodes.LCONST_0);
                mv.visitInsn(Opcodes.LCMP);
                mv.visitJumpInsn(Opcodes.IFEQ, slabSetupDone);
            }

            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitVarInsn(Opcodes.ISTORE, 30);

            emitAllocateSlabScratch(mv, slabPlan.slots().size());
            if (nativeSlabInnerVm) {
                emitAllocateSlabInnerScratchIfAvailable(mv);
            }
        }
        mv.visitLabel(slabSetupDone);

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

        Label scalarXZ = new Label();
        Label batchedXZ = new Label();
        Label yAfterInner = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 32);
        mv.visitJumpInsn(Opcodes.IFNULL, scalarXZ);

        int si = 0;
        for (SlabNativeBatchPlan.Slot slot : slabPlan.slots()) {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 40);
            Label prepXHead = new Label();
            Label prepXEnd = new Label();
            mv.visitLabel(prepXHead);
            mv.visitVarInsn(Opcodes.ILOAD, 40);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, prepXEnd);

            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ILOAD, 40);
            mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");

            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 41);
            Label prepZHead = new Label();
            Label prepZEnd = new Label();
            mv.visitLabel(prepZHead);
            mv.visitVarInsn(Opcodes.ILOAD, 41);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, prepZEnd);

            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ILOAD, 41);
            mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");

            mv.visitVarInsn(Opcodes.ILOAD, 40);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 41);
            mv.visitInsn(Opcodes.IADD);
            mv.visitVarInsn(Opcodes.ISTORE, 29);

            if (slot instanceof SlabNativeBatchPlan.MarkerSlot ms) {
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitVarInsn(Opcodes.ILOAD, 29);
                mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
                mv.visitVarInsn(Opcodes.ALOAD, 32);
                ldcIntStatic(mv, si);
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitVarInsn(Opcodes.ILOAD, 29);
                emitMarkerSlotCompute(mv, classInternalName, pool, ms.marker().externIndex(), 3);
                mv.visitInsn(Opcodes.DASTORE);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitVarInsn(Opcodes.ILOAD, 29);
                mv.visitVarInsn(Opcodes.ALOAD, 33);
                mv.visitVarInsn(Opcodes.ALOAD, 34);
                mv.visitVarInsn(Opcodes.ALOAD, 35);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, classInternalName, latticeSlabCoordMethodName(si), coordDesc, false);
            }

            mv.visitIincInsn(41, 1);
            mv.visitJumpInsn(Opcodes.GOTO, prepZHead);
            mv.visitLabel(prepZEnd);

            mv.visitIincInsn(40, 1);
            mv.visitJumpInsn(Opcodes.GOTO, prepXHead);
            mv.visitLabel(prepXEnd);

            if (!(slot instanceof SlabNativeBatchPlan.MarkerSlot)) {
                emitNativeHandleFieldLoad(mv, classInternalName, slot.nativeHandleIndex(nn), false);
                mv.visitVarInsn(Opcodes.ALOAD, 33);
                mv.visitVarInsn(Opcodes.ALOAD, 34);
                mv.visitVarInsn(Opcodes.ALOAD, 35);
                mv.visitVarInsn(Opcodes.ALOAD, 32);
                ldcIntStatic(mv, si);
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitVarInsn(Opcodes.ILOAD, 30);
                mv.visitVarInsn(Opcodes.ILOAD, 36);
                String batchName = slot instanceof SlabNativeBatchPlan.NormalSlot
                        ? "normalNoiseStackBatch" : "blendedNoiseBatch";
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, NATIVE_BRIDGE_INTERNAL, batchName,
                        slot instanceof SlabNativeBatchPlan.NormalSlot
                                ? batchNormalDesc : batchBlendedDesc, false);
            }

            si++;
        }

        if (nativeSlabInnerVm) {
            emitNativeSlabInnerAfterBatch(mv, yAfterInner, batchedXZ);
        } else {
            // Scratch locals were initialized before the loop, so batchedXZ has the same frame shape as
            // emitNativeSlabInnerAfterBatch's early Java-batched branch.
            mv.visitJumpInsn(Opcodes.GOTO, batchedXZ);
        }

        mv.visitLabel(scalarXZ);
        emitLatticeFillArrayInnerScalarXZ(mv, LATTICE_INNER_NAME);
        mv.visitJumpInsn(Opcodes.GOTO, yAfterInner);

        mv.visitLabel(batchedXZ);
        emitLatticeFillArrayInnerBatchedXZ(mv, LATTICE_INNER_BATCHED_NAME);

        mv.visitLabel(yAfterInner);
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
    }

    /**
     * XZ-hoist native slab batch: outer (x,z), column batch along Y.
     */
    private static void emitLatticeFillArrayWithOptionalSlabBatchXz(ClassWriter cw, String classInternalName,
                                                                    SlabNativeBatchPlan slabPlan,
                                                                    ConstantPool pool,
                                                                    boolean nativeSlabInnerVm,
                                                                    boolean nativeSlabInnerApplyBlendDensity) {
        String desc = "([DL" + CONTEXT_PROVIDER_INTERNAL + ";)V";
        String bodyDesc = "([D" + NOISE_CHUNK_DESC + ")V";
        String coordDesc = "(L" + COMPILED_BASE_INTERNAL + ";" + NOISE_CHUNK_DESC + "I[D[D[D)V";
        String batchNormalDesc = "(J[D[D[D[DIZ)V";
        String batchBlendedDesc = "(J[D[D[D[DIZ)V";
        // Emitted first: large CFG in isolation. Public fillArray (below) is tiny: guard + super fallback
        // only — that split avoids ASM 9.8+ Frame.merge AIOOBE when computing stack maps in one method.
        MethodVisitor body = cw.visitMethod(
                Opcodes.ACC_PRIVATE, LATTICE_XZ_SLAB_FILL_BODY, bodyDesc, null, null);
        body.visitCode();
        // Match historical local layout: inner routines expect slot 3 = NoiseChunk; args are 0,1,2 = this, a, nc.
        body.visitVarInsn(Opcodes.ALOAD, 2);
        body.visitVarInsn(Opcodes.ASTORE, 3);
        body.visitVarInsn(Opcodes.ALOAD, 3);
        body.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellWidth", "I");
        body.visitVarInsn(Opcodes.ISTORE, 4);
        body.visitVarInsn(Opcodes.ALOAD, 3);
        body.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        body.visitVarInsn(Opcodes.ISTORE, 5);
        body.visitVarInsn(Opcodes.ALOAD, 3);
        body.visitInsn(Opcodes.ICONST_0);
        body.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        emitInitSlabScratchLocals(body);
        body.visitInsn(Opcodes.ICONST_0);
        body.visitVarInsn(Opcodes.ISTORE, 7);
        Label xLoopHead = new Label();
        Label xLoopExit = new Label();
        // Zero cell extent: skip the entire (x,z) body including native batch + slab-inner VM emission.
        body.visitVarInsn(Opcodes.ILOAD, 4);
        body.visitJumpInsn(Opcodes.IFLE, xLoopExit);
        body.visitVarInsn(Opcodes.ILOAD, 5);
        body.visitJumpInsn(Opcodes.IFLE, xLoopExit);

        Label slabSetupDone = new Label();
        int nn = pool.noiseSpecCount();
        if (!slabPlan.isEmpty()) {
            for (SlabNativeBatchPlan.Slot s : slabPlan.slots()) {
                if (s instanceof SlabNativeBatchPlan.MarkerSlot) {
                    continue;
                }
                emitNativeHandleFieldLoad(body, classInternalName, s.nativeHandleIndex(nn), false);
                body.visitInsn(Opcodes.LCONST_0);
                body.visitInsn(Opcodes.LCMP);
                body.visitJumpInsn(Opcodes.IFEQ, slabSetupDone);
            }

            body.visitVarInsn(Opcodes.ILOAD, 5);
            body.visitVarInsn(Opcodes.ISTORE, 30);

            emitAllocateSlabScratch(body, slabPlan.slots().size());
            if (nativeSlabInnerVm) {
                emitAllocateSlabInnerScratchIfAvailable(body);
            }
        }
        body.visitLabel(slabSetupDone);

        body.visitLabel(xLoopHead);
        body.visitVarInsn(Opcodes.ILOAD, 7);
        body.visitVarInsn(Opcodes.ILOAD, 4);
        body.visitJumpInsn(Opcodes.IF_ICMPGE, xLoopExit);
        body.visitVarInsn(Opcodes.ALOAD, 3);
        body.visitVarInsn(Opcodes.ILOAD, 7);
        body.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");
        body.visitInsn(Opcodes.ICONST_0);
        body.visitVarInsn(Opcodes.ISTORE, 8);
        Label zLoopHead = new Label();
        Label zLoopExit = new Label();
        body.visitLabel(zLoopHead);
        body.visitVarInsn(Opcodes.ILOAD, 8);
        body.visitVarInsn(Opcodes.ILOAD, 4);
        body.visitJumpInsn(Opcodes.IF_ICMPGE, zLoopExit);
        body.visitVarInsn(Opcodes.ALOAD, 3);
        body.visitVarInsn(Opcodes.ILOAD, 8);
        body.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");

        Label scalarCol = new Label();
        Label batchedCol = new Label();
        Label colAfterInner = new Label();

        body.visitVarInsn(Opcodes.ALOAD, 0);
        body.visitVarInsn(Opcodes.ALOAD, 3);
        body.visitInvokeDynamicInsn(LATTICE_XZ_NAME, HELPER_DESC, HELPER_BSM);
        body.visitVarInsn(Opcodes.DSTORE, 11);

        if (slabPlan.isEmpty()) {
            body.visitJumpInsn(Opcodes.GOTO, scalarCol);
        }

        body.visitVarInsn(Opcodes.ALOAD, 32);
        body.visitJumpInsn(Opcodes.IFNULL, scalarCol);

        int si = 0;
        for (SlabNativeBatchPlan.Slot slot : slabPlan.slots()) {
            body.visitInsn(Opcodes.ICONST_0);
            body.visitVarInsn(Opcodes.ISTORE, 40);
            Label prepYHead = new Label();
            Label prepYEnd = new Label();
            body.visitLabel(prepYHead);
            body.visitVarInsn(Opcodes.ILOAD, 40);
            body.visitVarInsn(Opcodes.ILOAD, 30);
            body.visitJumpInsn(Opcodes.IF_ICMPGE, prepYEnd);

            body.visitVarInsn(Opcodes.ALOAD, 3);
            body.visitInsn(Opcodes.DUP);
            body.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
            body.visitInsn(Opcodes.ICONST_1);
            body.visitInsn(Opcodes.ISUB);
            body.visitVarInsn(Opcodes.ILOAD, 40);
            body.visitInsn(Opcodes.ISUB);
            body.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");

            body.visitVarInsn(Opcodes.ILOAD, 40);
            body.visitVarInsn(Opcodes.ISTORE, 29);

            if (slot instanceof SlabNativeBatchPlan.MarkerSlot ms) {
                body.visitVarInsn(Opcodes.ILOAD, 40);
                body.visitVarInsn(Opcodes.ILOAD, 4);
                body.visitVarInsn(Opcodes.ILOAD, 4);
                body.visitInsn(Opcodes.IMUL);
                body.visitInsn(Opcodes.IMUL);
                body.visitVarInsn(Opcodes.ILOAD, 7);
                body.visitVarInsn(Opcodes.ILOAD, 4);
                body.visitInsn(Opcodes.IMUL);
                body.visitInsn(Opcodes.IADD);
                body.visitVarInsn(Opcodes.ILOAD, 8);
                body.visitInsn(Opcodes.IADD);
                body.visitVarInsn(Opcodes.ISTORE, 53);
                body.visitVarInsn(Opcodes.ALOAD, 3);
                body.visitVarInsn(Opcodes.ILOAD, 53);
                body.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
                body.visitVarInsn(Opcodes.ALOAD, 32);
                ldcIntStatic(body, si);
                body.visitInsn(Opcodes.AALOAD);
                body.visitVarInsn(Opcodes.ILOAD, 29);
                emitMarkerSlotCompute(body, classInternalName, pool, ms.marker().externIndex(), 3);
                body.visitInsn(Opcodes.DASTORE);
            } else {
                body.visitVarInsn(Opcodes.ALOAD, 0);
                body.visitVarInsn(Opcodes.ALOAD, 3);
                body.visitVarInsn(Opcodes.ILOAD, 29);
                body.visitVarInsn(Opcodes.ALOAD, 33);
                body.visitVarInsn(Opcodes.ALOAD, 34);
                body.visitVarInsn(Opcodes.ALOAD, 35);
                body.visitMethodInsn(Opcodes.INVOKESTATIC, classInternalName, latticeSlabCoordMethodName(si), coordDesc, false);
            }

            body.visitIincInsn(40, 1);
            body.visitJumpInsn(Opcodes.GOTO, prepYHead);
            body.visitLabel(prepYEnd);

            if (!(slot instanceof SlabNativeBatchPlan.MarkerSlot)) {
                emitNativeHandleFieldLoad(body, classInternalName, slot.nativeHandleIndex(nn), false);
                body.visitVarInsn(Opcodes.ALOAD, 33);
                body.visitVarInsn(Opcodes.ALOAD, 34);
                body.visitVarInsn(Opcodes.ALOAD, 35);
                body.visitVarInsn(Opcodes.ALOAD, 32);
                ldcIntStatic(body, si);
                body.visitInsn(Opcodes.AALOAD);
                body.visitVarInsn(Opcodes.ILOAD, 30);
                body.visitVarInsn(Opcodes.ILOAD, 36);
                String batchName = slot instanceof SlabNativeBatchPlan.NormalSlot
                        ? "normalNoiseStackBatch" : "blendedNoiseBatch";
                body.visitMethodInsn(Opcodes.INVOKESTATIC, NATIVE_BRIDGE_INTERNAL, batchName,
                        slot instanceof SlabNativeBatchPlan.NormalSlot
                                ? batchNormalDesc : batchBlendedDesc, false);
            }
            si++;
        }

        if (nativeSlabInnerVm) {
            emitNativeSlabInnerAfterBatchXz(body, colAfterInner, batchedCol, nativeSlabInnerApplyBlendDensity);
        } else {
            body.visitJumpInsn(Opcodes.GOTO, batchedCol);
        }

        body.visitLabel(scalarCol);
        emitLatticeFillArrayInnerScalarColumnXz(body, LATTICE_INNER_XZ_NAME);
        body.visitJumpInsn(Opcodes.GOTO, colAfterInner);

        body.visitLabel(batchedCol);
        emitLatticeFillArrayInnerBatchedColumnXz(body, LATTICE_INNER_BATCHED_XZ_NAME);

        body.visitLabel(colAfterInner);
        body.visitIincInsn(8, 1);
        body.visitJumpInsn(Opcodes.GOTO, zLoopHead);
        body.visitLabel(zLoopExit);
        body.visitIincInsn(7, 1);
        body.visitJumpInsn(Opcodes.GOTO, xLoopHead);
        body.visitLabel(xLoopExit);

        body.visitVarInsn(Opcodes.ALOAD, 3);
        body.visitVarInsn(Opcodes.ILOAD, 4);
        body.visitVarInsn(Opcodes.ILOAD, 4);
        body.visitInsn(Opcodes.IMUL);
        body.visitVarInsn(Opcodes.ILOAD, 5);
        body.visitInsn(Opcodes.IMUL);
        body.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
        body.visitInsn(Opcodes.RETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();

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
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL, classInternalName, LATTICE_XZ_SLAB_FILL_BODY, bodyDesc, false);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(sliceCheck);
        emitLatticeFillArraySliceXzNativeFastPath(mv, classInternalName, slabPlan, pool, fallback);

        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, "fillArray", desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor cell = cw.visitMethod(Opcodes.ACC_PUBLIC, "dfc$fillCell", CELL_FILL_DESC, null, null);
        cell.visitCode();
        cell.visitVarInsn(Opcodes.ALOAD, 0);
        cell.visitVarInsn(Opcodes.ALOAD, 1);
        cell.visitVarInsn(Opcodes.ALOAD, 2);
        cell.visitMethodInsn(Opcodes.INVOKESPECIAL, classInternalName, LATTICE_XZ_SLAB_FILL_BODY, bodyDesc, false);
        cell.visitInsn(Opcodes.RETURN);
        cell.visitMaxs(0, 0);
        cell.visitEnd();
    }

    private static void emitLatticeFillArraySliceXzNativeFastPath(MethodVisitor mv,
                                                                  String classInternalName,
                                                                  SlabNativeBatchPlan slabPlan,
                                                                  ConstantPool pool,
                                                                  Label fallback) {
        Label notSlice = new Label();
        Label scalarColumn = new Label();

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

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInvokeDynamicInsn(LATTICE_XZ_NAME, HELPER_DESC, HELPER_BSM);
        mv.visitVarInsn(Opcodes.DSTORE, 11);

        if (slabPlan.isEmpty()) {
            mv.visitJumpInsn(Opcodes.GOTO, scalarColumn);
        }

        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IFLE, scalarColumn);

        int nn = pool.noiseSpecCount();
        for (SlabNativeBatchPlan.Slot s : slabPlan.slots()) {
            if (s instanceof SlabNativeBatchPlan.MarkerSlot) {
                continue;
            }
            emitNativeHandleFieldLoad(mv, classInternalName, s.nativeHandleIndex(nn), false);
            mv.visitInsn(Opcodes.LCONST_0);
            mv.visitInsn(Opcodes.LCMP);
            mv.visitJumpInsn(Opcodes.IFEQ, scalarColumn);
        }

        emitInitSlabScratchLocals(mv);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ISTORE, 30);
        emitAllocateSlabScratch(mv, slabPlan.slots().size());

        String batchNormalDesc = "(J[D[D[D[DIZ)V";
        String batchBlendedDesc = "(J[D[D[D[DIZ)V";
        int si = 0;
        for (SlabNativeBatchPlan.Slot slot : slabPlan.slots()) {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 40);
            Label prepHead = new Label();
            Label prepEnd = new Label();
            mv.visitLabel(prepHead);
            mv.visitVarInsn(Opcodes.ILOAD, 40);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, prepEnd);

            emitSliceRowContext(mv, 40);

            if (slot instanceof SlabNativeBatchPlan.MarkerSlot ms) {
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitVarInsn(Opcodes.ILOAD, 40);
                mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
                mv.visitVarInsn(Opcodes.ALOAD, 32);
                ldcIntStatic(mv, si);
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitVarInsn(Opcodes.ILOAD, 40);
                emitMarkerSlotCompute(mv, classInternalName, pool, ms.marker().externIndex(), 3);
                mv.visitInsn(Opcodes.DASTORE);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitVarInsn(Opcodes.ILOAD, 40);
                mv.visitVarInsn(Opcodes.ALOAD, 33);
                mv.visitVarInsn(Opcodes.ALOAD, 34);
                mv.visitVarInsn(Opcodes.ALOAD, 35);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, classInternalName, latticeSliceSlabCoordMethodName(si),
                        "(L" + COMPILED_BASE_INTERNAL + ";" + NOISE_CHUNK_DESC + "I[D[D[D)V", false);
            }

            mv.visitIincInsn(40, 1);
            mv.visitJumpInsn(Opcodes.GOTO, prepHead);
            mv.visitLabel(prepEnd);

            if (!(slot instanceof SlabNativeBatchPlan.MarkerSlot)) {
                emitNativeHandleFieldLoad(mv, classInternalName, slot.nativeHandleIndex(nn), false);
                mv.visitVarInsn(Opcodes.ALOAD, 33);
                mv.visitVarInsn(Opcodes.ALOAD, 34);
                mv.visitVarInsn(Opcodes.ALOAD, 35);
                mv.visitVarInsn(Opcodes.ALOAD, 32);
                ldcIntStatic(mv, si);
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitVarInsn(Opcodes.ILOAD, 30);
                mv.visitVarInsn(Opcodes.ILOAD, 36);
                String batchName = slot instanceof SlabNativeBatchPlan.NormalSlot
                        ? "normalNoiseStackBatch" : "blendedNoiseBatch";
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, NATIVE_BRIDGE_INTERNAL, batchName,
                        slot instanceof SlabNativeBatchPlan.NormalSlot
                                ? batchNormalDesc : batchBlendedDesc, false);
            }
            si++;
        }

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 40);
        Label batchedHead = new Label();
        Label batchedEnd = new Label();
        mv.visitLabel(batchedHead);
        mv.visitVarInsn(Opcodes.ILOAD, 40);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, batchedEnd);
        emitSliceRowContext(mv, 40);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 40);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.DLOAD, 11);
        mv.visitVarInsn(Opcodes.ALOAD, 32);
        mv.visitInvokeDynamicInsn(LATTICE_INNER_BATCHED_XZ_SLICE_NAME, LATTICE_INNER_BATCHED_DESC, HELPER_BSM);
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitIincInsn(40, 1);
        mv.visitJumpInsn(Opcodes.GOTO, batchedHead);
        mv.visitLabel(batchedEnd);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(scalarColumn);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 40);
        Label scalarHead = new Label();
        Label scalarEnd = new Label();
        mv.visitLabel(scalarHead);
        mv.visitVarInsn(Opcodes.ILOAD, 40);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, scalarEnd);
        emitSliceRowContext(mv, 40);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 40);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.DLOAD, 11);
        mv.visitInvokeDynamicInsn(LATTICE_INNER_XZ_NAME, LATTICE_INNER_DESC, HELPER_BSM);
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitIincInsn(40, 1);
        mv.visitJumpInsn(Opcodes.GOTO, scalarHead);
        mv.visitLabel(scalarEnd);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(notSlice);
        mv.visitJumpInsn(Opcodes.GOTO, fallback);
    }

    private static void emitLatticeFillArrayInnerScalarColumnXz(MethodVisitor mv, String innerIndyName) {
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
    }

    private static void emitLatticeFillArrayInnerBatchedColumnXz(MethodVisitor mv, String batchedIndyName) {
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
        mv.visitVarInsn(Opcodes.ALOAD, 32);
        mv.visitInvokeDynamicInsn(batchedIndyName, LATTICE_INNER_BATCHED_DESC, HELPER_BSM);
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitIincInsn(6, -1);
        mv.visitJumpInsn(Opcodes.GOTO, yLoopHead);
        mv.visitLabel(yLoopExit);
    }

    private static void emitNativeSlabInnerAfterBatchXz(MethodVisitor mv, Label colAfterInner, Label batchedJavaInner,
                                                       boolean applyBlendDensity) {
        mv.visitVarInsn(Opcodes.ALOAD, 50);
        mv.visitJumpInsn(Opcodes.IFNULL, batchedJavaInner);

        mv.visitVarInsn(Opcodes.ALOAD, 52);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL, "slabInnerConsts", "[D");
        mv.visitVarInsn(Opcodes.ALOAD, 32);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "firstNoiseX", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "firstNoiseZ", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellStartBlockY", "I");
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.DLOAD, 11);
        mv.visitVarInsn(Opcodes.ALOAD, 50);
        mv.visitVarInsn(Opcodes.ILOAD, 30);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NATIVE_BRIDGE_INTERNAL, "slabInnerEval", SLAB_INNER_EVAL_DESC, false);

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 51);
        Label cHead = new Label();
        Label cEnd = new Label();
        mv.visitLabel(cHead);
        mv.visitVarInsn(Opcodes.ILOAD, 51);
        mv.visitVarInsn(Opcodes.ILOAD, 30);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, cEnd);
        mv.visitVarInsn(Opcodes.ILOAD, 51);
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
        mv.visitVarInsn(Opcodes.ISTORE, 53);
        if (applyBlendDensity) {
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitVarInsn(Opcodes.ILOAD, 51);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");
        }
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 53);
        if (applyBlendDensity) {
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT_INTERNAL,
                    "getBlender", "()Lnet/minecraft/world/level/levelgen/blending/Blender;", true);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ALOAD, 50);
            mv.visitVarInsn(Opcodes.ILOAD, 51);
            mv.visitInsn(Opcodes.DALOAD);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/world/level/levelgen/blending/Blender",
                    "blendDensity",
                    "(L" + FUNCTION_CONTEXT_INTERNAL + ";D)D", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 50);
            mv.visitVarInsn(Opcodes.ILOAD, 51);
            mv.visitInsn(Opcodes.DALOAD);
        }
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitIincInsn(51, 1);
        mv.visitJumpInsn(Opcodes.GOTO, cHead);
        mv.visitLabel(cEnd);
        mv.visitJumpInsn(Opcodes.GOTO, colAfterInner);
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
            return analyze(root, extracted, forceInlineRoot, Collections.emptySet(), null);
        }

        static CoordinateSlotUse analyze(IRNode root, Set<IRNode> extracted, boolean forceInlineRoot,
                                         Set<IRNode> preinstalledSpills,
                                         IdentityHashMap<IRNode, Integer> slabBatchSlots) {
            if (root == null) {
                return ALL;
            }
            Analyzer analyzer = new Analyzer(extracted, preinstalledSpills, slabBatchSlots);
            return analyzer.node(root, forceInlineRoot);
        }

        static CoordinateSlotUse analyzeCoordinates(Set<IRNode> extracted, IRNode x, IRNode y, IRNode z) {
            Analyzer analyzer = new Analyzer(extracted, Collections.emptySet(), null);
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
            private final IdentityHashMap<IRNode, Integer> slabBatchSlots;
            private final IdentityHashMap<IRNode, CoordinateSlotUse> normalMemo = new IdentityHashMap<>();
            private final IdentityHashMap<IRNode, CoordinateSlotUse> forcedMemo = new IdentityHashMap<>();

            Analyzer(Set<IRNode> extracted, Set<IRNode> preinstalledSpills,
                     IdentityHashMap<IRNode, Integer> slabBatchSlots) {
                this.extracted = extracted == null ? Collections.emptySet() : extracted;
                this.preinstalledSpills = preinstalledSpills == null ? Collections.emptySet() : preinstalledSpills;
                this.slabBatchSlots = slabBatchSlots;
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
                    case IRNode.InlinedNoise in -> slabBatchSlots != null && slabBatchSlots.containsKey(in)
                            ? NONE
                            : node(in.coordX(), false).plus(node(in.coordY(), false)).plus(node(in.coordZ(), false));
                    case IRNode.InlinedBlendedNoise ibn -> slabBatchSlots != null && slabBatchSlots.containsKey(ibn)
                            ? NONE
                            : ALL;
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
        /**
         * True for static {@code helper_N} methods: local 0 is typed as
         * {@link CompiledDensityFunction} in the method descriptor, but
         * {@link #emitOctaveContribution} reads subclass-only {@code noise_*} fields.
         * A {@code CHECKCAST} to the generated class is required for verification.
         * {@code compute()} passes false — {@code this} is already the precise subclass.
         */
        private final boolean castSelfForSubclassNoiseFields;
        /** When non-null, {@link IRNode.InlinedNoise} / {@link IRNode.InlinedBlendedNoise} load slab results. */
        private final IdentityHashMap<IRNode, Integer> slabBatchSlots;
        /** Local holding {@code double[][]} slab out rows; valid when {@link #slabBatchSlots} non-null. */
        private final int slabOutLocal;
        /** One of {@code SLAB_INDEX_*}: how a batched helper maps the current context to a slab row. */
        private final int slabIndexMode;

        EmitState(MethodVisitor mv, String classInternalName, HelperRegistry helpers,
                  boolean castSelfForSubclassNoiseFields) {
            this(mv, classInternalName, helpers, castSelfForSubclassNoiseFields, CoordinateReusePlan.EMPTY);
        }

        EmitState(MethodVisitor mv, String classInternalName, HelperRegistry helpers,
                  boolean castSelfForSubclassNoiseFields,
                  CoordinateReusePlan coordinateReuse) {
            this(mv, classInternalName, helpers, castSelfForSubclassNoiseFields,
                    coordinateReuse, null, -1, SLAB_INDEX_XZ);
        }

        EmitState(MethodVisitor mv, String classInternalName, HelperRegistry helpers,
                  boolean castSelfForSubclassNoiseFields,
                  IdentityHashMap<IRNode, Integer> slabBatchSlots,
                  int slabOutLocal) {
            this(mv, classInternalName, helpers, castSelfForSubclassNoiseFields,
                    CoordinateReusePlan.EMPTY, slabBatchSlots, slabOutLocal, SLAB_INDEX_XZ);
        }

        EmitState(MethodVisitor mv, String classInternalName, HelperRegistry helpers,
                  boolean castSelfForSubclassNoiseFields,
                  IdentityHashMap<IRNode, Integer> slabBatchSlots,
                  int slabOutLocal,
                  int slabIndexMode) {
            this(mv, classInternalName, helpers, castSelfForSubclassNoiseFields,
                    CoordinateReusePlan.EMPTY, slabBatchSlots, slabOutLocal, slabIndexMode);
        }

        EmitState(MethodVisitor mv, String classInternalName, HelperRegistry helpers,
                  boolean castSelfForSubclassNoiseFields,
                  CoordinateReusePlan coordinateReuse,
                  IdentityHashMap<IRNode, Integer> slabBatchSlots,
                  int slabOutLocal,
                  int slabIndexMode) {
            this.mv = mv;
            this.classInternalName = classInternalName;
            this.helpers = helpers;
            this.pool = helpers.pool;
            this.coordinateReuse = coordinateReuse == null ? CoordinateReusePlan.EMPTY : coordinateReuse;
            this.castSelfForSubclassNoiseFields = castSelfForSubclassNoiseFields;
            this.slabBatchSlots = slabBatchSlots;
            this.slabOutLocal = slabOutLocal;
            this.slabIndexMode = slabIndexMode;
        }

        private void emitSlabNoiseSampleLoad(int batchSlotIndex) {
            mv.visitVarInsn(Opcodes.ALOAD, slabOutLocal);
            Codegen.ldcIntStatic(mv, batchSlotIndex);
            mv.visitInsn(Opcodes.AALOAD);
            if (slabIndexMode == SLAB_INDEX_Y_COLUMN) {
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.ISUB);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");
                mv.visitInsn(Opcodes.ISUB);
            } else if (slabIndexMode == SLAB_INDEX_ARRAY_INDEX) {
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellWidth", "I");
                mv.visitInsn(Opcodes.IMUL);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");
                mv.visitInsn(Opcodes.IADD);
            }
            mv.visitInsn(Opcodes.DALOAD);
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
                mv.visitVarInsn(Opcodes.ALOAD, 1);          // ctx
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
            mv.visitVarInsn(Opcodes.ALOAD, 1);
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
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "dev/sixik/generator_accelerator/common/density/compiler/compiler/runtime/Runtime",
                    "squeeze", "(D)D", false);
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
         * live only in {@link #emitSlabNoiseSampleLoad} (batched lattice helpers), fixed
         * upstream by narrowing {@code ctx} in {@link Codegen#emitLatticeInnerBatchedHelper}.
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
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn((double) g.fromY());
            mv.visitLdcInsn((double) g.toY());
            mv.visitLdcInsn(g.fromValue());
            mv.visitLdcInsn(g.toValue());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/util/Mth", "clampedMap",
                    "(DDDDD)D", false);
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
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitLdcInsn(n.xzScale());
            mv.visitLdcInsn(n.yScale());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGIONAL_NOISE_BRICK_CACHE_INTERNAL, "samplePlainNormalNoise",
                    PLAIN_NORMAL_NOISE_SAMPLE_DESC, false);
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
            if (slabBatchSlots != null) {
                Integer bix = slabBatchSlots.get(n);
                if (bix != null) {
                    emitSlabNoiseSampleLoad(bix);
                    return;
                }
            }
            var spec = pool.noiseSpec(n.specPoolIndex());
            int cxSlot = coordinateSlot(n.coordX());
            int cySlot = coordinateSlot(n.coordY());
            int czSlot = coordinateSlot(n.coordZ());

            if (CodegenNativeNoise.emitNativeOps()) {
                Label fallback = new Label();
                Label afterNative = new Label();
                emitNativeHandleFieldLoad(mv, classInternalName, n.specPoolIndex(), castSelfForSubclassNoiseFields);
                int hSlot = allocLongSlot();
                mv.visitVarInsn(Opcodes.LSTORE, hSlot);
                mv.visitVarInsn(Opcodes.LLOAD, hSlot);
                mv.visitInsn(Opcodes.LCONST_0);
                mv.visitInsn(Opcodes.LCMP);
                mv.visitJumpInsn(Opcodes.IFEQ, fallback);
                mv.visitVarInsn(Opcodes.LLOAD, hSlot);
                mv.visitVarInsn(Opcodes.DLOAD, cxSlot);
                mv.visitVarInsn(Opcodes.DLOAD, cySlot);
                mv.visitVarInsn(Opcodes.DLOAD, czSlot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, NATIVE_BRIDGE_INTERNAL, "normalNoiseStackSample1",
                        "(JDDD)D", false);
                mv.visitJumpInsn(Opcodes.GOTO, afterNative);
                mv.visitLabel(fallback);
                emitInlinedNoiseJavaTail(n, spec, cxSlot, cySlot, czSlot);
                mv.visitLabel(afterNative);
            } else {
                emitInlinedNoiseJavaTail(n, spec, cxSlot, cySlot, czSlot);
            }
        }

        private void emitInlinedNoiseJavaTail(IRNode.InlinedNoise n, dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec spec,
                                              int cxSlot, int cySlot, int czSlot) {
            emitBranchSum(spec.first(), n.specPoolIndex(), 0, cxSlot, cySlot, czSlot);
            var second = spec.second();
            int sCx, sCy, sCz;
            if (second.activeOctaves().length > 0 && Double.compare(second.inputCoordScale(), 1.0) != 0) {
                sCx = allocDoubleSlot();
                mv.visitVarInsn(Opcodes.DLOAD, cxSlot);
                mv.visitLdcInsn(second.inputCoordScale());
                mv.visitInsn(Opcodes.DMUL);
                mv.visitVarInsn(Opcodes.DSTORE, sCx);
                sCy = allocDoubleSlot();
                mv.visitVarInsn(Opcodes.DLOAD, cySlot);
                mv.visitLdcInsn(second.inputCoordScale());
                mv.visitInsn(Opcodes.DMUL);
                mv.visitVarInsn(Opcodes.DSTORE, sCy);
                sCz = allocDoubleSlot();
                mv.visitVarInsn(Opcodes.DLOAD, czSlot);
                mv.visitLdcInsn(second.inputCoordScale());
                mv.visitInsn(Opcodes.DMUL);
                mv.visitVarInsn(Opcodes.DSTORE, sCz);
            } else {
                sCx = cxSlot;
                sCy = cySlot;
                sCz = czSlot;
            }
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
                                   int specIdx, int branchIdx, int cxSlot, int cySlot, int czSlot) {
            int count = branch.activeOctaves().length;
            if (count == 0) {
                mv.visitInsn(Opcodes.DCONST_0);
                return;
            }
            // first contribution leaves a double on the stack; subsequent ones DADD.
            for (int i = 0; i < count; i++) {
                emitOctaveContribution(specIdx, branchIdx, i,
                        branch.inputFactors()[i], branch.ampValueFactors()[i],
                        cxSlot, cySlot, czSlot);
                if (i > 0) mv.visitInsn(Opcodes.DADD);
            }
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
                                            int cxSlot, int cySlot, int czSlot) {
            mv.visitLdcInsn(ampValueFactor);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            if (castSelfForSubclassNoiseFields) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
            }
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName,
                    noiseFieldName(specIdx, branchIdx, activeOctaveIdx), IMPROVED_NOISE_DESC);

            mv.visitVarInsn(Opcodes.DLOAD, cxSlot);
            mv.visitLdcInsn(inputFactor);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL,
                    "wrapAxis", "(D)D", false);

            mv.visitVarInsn(Opcodes.DLOAD, cySlot);
            mv.visitLdcInsn(inputFactor);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL,
                    "wrapAxis", "(D)D", false);

            mv.visitVarInsn(Opcodes.DLOAD, czSlot);
            mv.visitLdcInsn(inputFactor);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL,
                    "wrapAxis", "(D)D", false);

            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IMPROVED_NOISE_INTERNAL,
                    "noise", "(DDD)D", false);
            mv.visitInsn(Opcodes.DMUL);
        }

        /**
         * Standalone {@link IRNode.WeirdRarity} emission: just delegates to the same
         * static helper {@link #emitWeirdScaled} previously used inline. Surfaced as
         * its own node so {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.RefCount}
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
            // Compute coordinate, spill to slot, then dispatch to the right segment.
            emit(mp.coordinate());
            mv.visitInsn(Opcodes.D2F);
            int fSlot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, fSlot);

            float[] locs = mp.locations();
            int n = locs.length;
            Label end = new Label();
            boolean binarySearch = useBinarySplineSearch(n);
            boolean lutSearch = useSplineSegmentLut(n, locs);
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

            Label leftExt = new Label();
            Label rightExt = new Label();

            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[0]);
            mv.visitInsn(Opcodes.FCMPG);
            mv.visitJumpInsn(Opcodes.IFLT, leftExt);

            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[n - 1]);
            mv.visitInsn(Opcodes.FCMPL);
            mv.visitJumpInsn(Opcodes.IFGE, rightExt);

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

        private void emitLinearSplineSegments(int fSlot, IRNode.Spline.Multipoint mp,
                                              BranchScope snap, Label end,
                                              int splineTimingStartSlot, int splineTimingResultSlot) {
            float[] locs = mp.locations();
            for (int i = 0; i < locs.length - 1; i++) {
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
        }

        private void emitBinarySplineSegments(int fSlot, IRNode.Spline.Multipoint mp,
                                              BranchScope snap, Label end,
                                              int loSegment, int hiSegment,
                                              int splineTimingStartSlot, int splineTimingResultSlot) {
            if (loSegment == hiSegment) {
                restoreBranch(snap);
                emitInterpolatedSegment(fSlot, mp, loSegment);
                emitSplineRuntimeRecord(mp.locations().length, DfcSplineStats.SEARCH_BINARY,
                        DfcSplineStats.EXIT_INTERIOR,
                        splineTimingStartSlot, splineTimingResultSlot);
                restoreBranch(snap);
                mv.visitJumpInsn(Opcodes.GOTO, end);
                return;
            }

            int midSegment = (loSegment + hiSegment) >>> 1;
            Label right = new Label();
            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(mp.locations()[midSegment + 1]);
            mv.visitInsn(Opcodes.FCMPG);
            mv.visitJumpInsn(Opcodes.IFGE, right);
            emitBinarySplineSegments(fSlot, mp, snap, end, loSegment, midSegment,
                    splineTimingStartSlot, splineTimingResultSlot);
            mv.visitLabel(right);
            restoreBranch(snap);
            emitBinarySplineSegments(fSlot, mp, snap, end, midSegment + 1, hiSegment,
                    splineTimingStartSlot, splineTimingResultSlot);
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
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DENSITY_FUNCTION_INTERNAL,
                    "compute", "(L" + FUNCTION_CONTEXT_INTERNAL + ";)D", true);
        }

        /** Marker sites flagged as cell-cache wrappers may use {@link DfcCacheFastPath}. */
        private void emitMarkerInvoke(int idx) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            if (castSelfForSubclassNoiseFields) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
            }
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(idx), DENSITY_FUNCTION_DESC);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            if (pool.externHasCacheWrapperFastPath(idx)) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CACHE_FAST_PATH_INTERNAL, "computeWithOptionalDirectRead",
                        CACHE_FAST_READ_DESC, false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DENSITY_FUNCTION_INTERNAL,
                        "compute", "(L" + FUNCTION_CONTEXT_INTERNAL + ";)D", true);
            }
        }

        private void emitInlinedBlendedNoise(IRNode.InlinedBlendedNoise n) {
            if (slabBatchSlots != null) {
                Integer bix = slabBatchSlots.get(n);
                if (bix != null) {
                    emitSlabNoiseSampleLoad(bix);
                    return;
                }
            }
            if (CodegenNativeNoise.emitNativeOps()) {
                Label fallback = new Label();
                Label afterNative = new Label();
                int blendedIdx = pool.noiseSpecCount() + n.blendedSpecIndex();
                emitNativeHandleFieldLoad(mv, classInternalName, blendedIdx, castSelfForSubclassNoiseFields);
                int hSlot = allocLongSlot();
                mv.visitVarInsn(Opcodes.LSTORE, hSlot);
                mv.visitVarInsn(Opcodes.LLOAD, hSlot);
                mv.visitInsn(Opcodes.LCONST_0);
                mv.visitInsn(Opcodes.LCMP);
                mv.visitJumpInsn(Opcodes.IFEQ, fallback);
                mv.visitVarInsn(Opcodes.LLOAD, hSlot);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitInsn(Opcodes.I2D);
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                mv.visitInsn(Opcodes.I2D);
                mv.visitVarInsn(Opcodes.ILOAD, 4);
                mv.visitInsn(Opcodes.I2D);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, NATIVE_BRIDGE_INTERNAL, "blendedNoiseSample1",
                        "(JDDD)D", false);
                mv.visitJumpInsn(Opcodes.GOTO, afterNative);
                mv.visitLabel(fallback);
                BlendedNoiseByteEmitter.emit(
                        mv, classInternalName, pool, n.blendedSpecIndex(), castSelfForSubclassNoiseFields, this::allocDoubleSlot);
                mv.visitLabel(afterNative);
            } else {
                BlendedNoiseByteEmitter.emit(
                        mv, classInternalName, pool, n.blendedSpecIndex(), castSelfForSubclassNoiseFields, this::allocDoubleSlot);
            }
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

            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT_INTERNAL,
                    "getBlender", "()Lnet/minecraft/world/level/levelgen/blending/Blender;", true);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
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

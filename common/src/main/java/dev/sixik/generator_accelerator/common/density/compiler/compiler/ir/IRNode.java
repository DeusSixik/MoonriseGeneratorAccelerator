package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.List;
import java.util.Objects;

/**
 * Sealed interface for the compiler's intermediate representation.
 *
 * <p>Every node is a value-typed record so {@link Object#equals} and {@link Object#hashCode}
 * are structural. {@link IRBuilder} interns nodes through a {@code HashMap<IRNode, IRNode>}
 * — once interned, {@code ==} on two IR references means semantic equality, and the
 * surrounding tree becomes a true DAG. This is the deduplication mechanism that lets the
 * codegen treat refcount {@code >= 2} nodes as candidates for spilling to a local slot.
 *
 * <p>Heavy "extern" references — noise samplers, opaque mod-provided DensityFunctions,
 * spline AST blobs — are stored by integer index into a {@link
 * dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool}. The
 * pool is keyed by {@link System#identityHashCode}, so two textually identical IR nodes
 * referring to the same {@code NormalNoise} instance dedup cleanly without depending on
 * {@code NormalNoise} having sane equals.
 */
public sealed interface IRNode {

    /** A literal {@code double}. */
    record Const(double value) implements IRNode {}

    /** Block coordinate accessors, pulled from {@link DensityFunction.FunctionContext}. */
    record BlockX() implements IRNode {
        public static final BlockX INSTANCE = new BlockX();
    }
    record BlockY() implements IRNode {
        public static final BlockY INSTANCE = new BlockY();
    }
    record BlockZ() implements IRNode {
        public static final BlockZ INSTANCE = new BlockZ();
    }

    /** Two-argument arithmetic. */
    enum BinOp { ADD, SUB, MUL, DIV, MIN, MAX }
    record Bin(BinOp op, IRNode left, IRNode right) implements IRNode {}

    /** Single-argument transforms with a closed-form. */
    enum UnaryOp {
        ABS, NEG, SQUARE, CUBE, HALF_NEGATIVE, QUARTER_NEGATIVE, SQUEEZE
    }
    record Unary(UnaryOp op, IRNode input) implements IRNode {}

    /** {@link DensityFunctions.Clamp}. */
    record Clamp(IRNode input, double min, double max) implements IRNode {}

    /** {@link DensityFunctions.RangeChoice}. Branches on whether {@code input} lies in {@code [min, max)}. */
    record RangeChoice(IRNode input, double min, double max, IRNode whenInRange, IRNode whenOutOfRange)
            implements IRNode {}

    /** {@link DensityFunctions.YClampedGradient}. */
    record YClampedGradient(int fromY, int toY, double fromValue, double toValue) implements IRNode {}

    /**
     * {@link DensityFunctions.Noise} — sample {@code noise[noiseIndex]} at {@code (x*xz, y*yScale, z*xz)}.
     */
    record Noise(int noiseIndex, double xzScale, double yScale, double maxValue) implements IRNode {}

    /**
     * {@link DensityFunctions.ShiftedNoise} — sample at
     * {@code (x*xz + shiftX, y*yScale + shiftY, z*xz + shiftZ)}.
     */
    record ShiftedNoise(int noiseIndex, double xzScale, double yScale,
                        IRNode shiftX, IRNode shiftY, IRNode shiftZ,
                        double maxValue) implements IRNode {}

    /** {@code shiftA(noise)}: noise sampled at {@code (x*0.25, 0, z*0.25) * 4}. */
    record ShiftA(int noiseIndex, double maxValue) implements IRNode {}
    /** {@code shiftB(noise)}: noise at {@code (z*0.25, x*0.25, 0) * 4}. */
    record ShiftB(int noiseIndex, double maxValue) implements IRNode {}
    /** {@code shift(noise)}: noise at {@code (x*0.25, y*0.25, z*0.25) * 4}. */
    record Shift(int noiseIndex, double maxValue) implements IRNode {}

    /** {@link DensityFunctions.WeirdScaledSampler}. */
    record WeirdScaled(IRNode input, int noiseIndex, int rarityValueMapperOrdinal, double maxValue)
            implements IRNode {}

    /**
     * Fully unrolled, octave-baked replacement for {@link Noise} / {@link ShiftedNoise} /
     * {@link ShiftA} / {@link ShiftB} / {@link Shift} produced by
     * {@link NoiseExpander}. The {@code specPoolIndex} keys into {@link
     * dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool}'s
     * parallel {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec}
     * list; the codegen emits the per-octave loop fully unrolled with LDC'd constants and
     * per-octave {@code GETFIELD} on the generated class's typed
     * {@code ImprovedNoise} fields.
     *
     * <p>The three coordinate sub-IR nodes are exactly what would otherwise be emitted as
     * the {@code (x*xz, y*yScale, z*xz)} expression for {@link Noise}, the
     * {@code (x*xz + shiftX, y*yScale + shiftY, z*xz + shiftZ)} expression for
     * {@link ShiftedNoise}, etc. Surfacing them as IR lets {@link IROptimizer}'s second
     * pass and {@link RefCount} / {@code Splitter} share them across multiple
     * {@code InlinedNoise} call sites that happen to want the same coordinates (very
     * common for scale-1.0 chains that all sample at {@code (x, y, z)}).
     *
     * <p>{@code maxValue} is the propagated {@link NormalNoise#maxValue()} — needed by
     * {@link Bounds} for downstream short-circuiting; it would otherwise have to round-trip
     * through the constant pool, which is awkward when the spec hasn't yet been keyed in.
     */
    record InlinedNoise(int specPoolIndex, IRNode coordX, IRNode coordY, IRNode coordZ,
                        double maxValue) implements IRNode {}

    /**
     * Inlined {@link net.minecraft.world.level.levelgen.synth.BlendedNoise#compute} with
     * the vanilla 1.21.x two-loop + {@code Mth.clampedLerp} structure (5-arg
     * {@link net.minecraft.world.level.levelgen.synth.ImprovedNoise#noise} per octave).
     */
    record InlinedBlendedNoise(int blendedSpecIndex, double maxValue) implements IRNode {}

    /**
     * Standalone {@code WeirdScaledSampler} rarity-mapping IR node. Produced by
     * {@link NoiseExpander} as the {@code rarity} factor of the
     * {@code abs(noise(...)) * rarity(input)} decomposition; carries just enough to emit
     * the {@code Runtime.weirdRarity} static call. Lives as its own node (rather than
     * being inlined back into a {@link Bin}) so {@link RefCount} / {@code Splitter} see
     * the single materialised double instead of duplicating the input through every use.
     */
    record WeirdRarity(IRNode input, int rarityValueMapperOrdinal) implements IRNode {}

    /**
     * {@link DensityFunctions.EndIslandDensityFunction}. Captured as an opaque extern
     * because its core relies on a precomputed simplex-noise grid; the compiler simply
     * delegates to a captured instance through INVOKEVIRTUAL.
     */
    record EndIslands(int externIndex) implements IRNode {}

    /**
     * {@link net.minecraft.world.level.levelgen.Beardifier} when
     * Beardifier specialization.
     * is enabled — same runtime dispatch as {@link Invoke}, distinct IR for audits / fingerprinting.
     */
    record Beardifier(int externIndex) implements IRNode {}

    /**
     * Inlined spline AST. Children are IR nodes (the spline's flattened coordinate / value
     * branches), with two parallel {@code float[]} arrays of locations and derivatives per
     * level interpreted by the codegen. {@code splineIndex} points at the original
     * {@link net.minecraft.util.CubicSpline} blob for fallback / parity debugging.
     */
    sealed interface Spline extends IRNode {
        record Constant(float value) implements Spline {}
        /**
         * Multipoint spline node. {@code coordinate} computes the spline's coordinate at
         * the current context; {@code locations} / {@code derivatives} are sorted by
         * {@code locations}. {@code values} are the per-segment sub-splines, evaluated
         * lazily inside the bytecode-emitted branch ladder.
         *
         * <p>{@code minValue} / {@code maxValue} are the precomputed cached bounds from
         * {@link net.minecraft.util.CubicSpline.Multipoint#minValue()} so we don't have to
         * recompute them.
         */
        record Multipoint(IRNode coordinate, float[] locations, List<Spline> values, float[] derivatives,
                          float minValue, float maxValue) implements Spline {
            // Records derive equals/hashCode from components; arrays use identity equality.
            // We override to keep the structural-equality property the IR depends on.
            @Override public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Multipoint mp)) return false;
                return java.util.Arrays.equals(this.locations, mp.locations)
                        && java.util.Arrays.equals(this.derivatives, mp.derivatives)
                        && Float.compare(this.minValue, mp.minValue) == 0
                        && Float.compare(this.maxValue, mp.maxValue) == 0
                        && this.coordinate.equals(mp.coordinate)
                        && this.values.equals(mp.values);
            }
            @Override public int hashCode() {
                return Objects.hash(coordinate, java.util.Arrays.hashCode(locations),
                        java.util.Arrays.hashCode(derivatives), values, minValue, maxValue);
            }
        }
    }

    /**
     * {@link DensityFunctions.Marker}: never inlined. Compiled as the boundary between
     * compilation units — its {@link #child} is compiled separately and the resulting
     * {@code DensityFunctions.Marker(type, compiledChild)} is captured as an extern.
     * NoiseChunk's {@code wrap} visitor will swap the marker for the appropriate cell
     * cache wrapper at chunk-init time; preserving the marker class is what makes that
     * mechanism keep working.
     */
    record Marker(int externIndex) implements IRNode {}

    /**
     * Opaque mod-provided / unrecognised DensityFunction. Captured by identity into the
     * extern array; the codegen emits {@code INVOKEINTERFACE DensityFunction.compute}.
     */
    record Invoke(int externIndex) implements IRNode {}

    /**
     * BlendDensity — wraps an inner IR node with a runtime call to
     * {@code FunctionContext.getBlender().blendDensity}. Captured as a special node so
     * the codegen can inline the inner IR but still emit the per-context blend call.
     */
    record BlendDensity(IRNode input) implements IRNode {}
}

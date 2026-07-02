package dev.sixik.generator_accelerator.common.density.compiler.compiler.spline;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRBuilder;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a {@link CubicSpline} (specifically the Spline.Coordinate-typed flavour used by
 * the worldgen density-function "spline" node) and produces a self-contained
 * {@link IRNode.Spline} tree. Every {@link DensityFunctions.Spline.Coordinate} is routed
 * through the enclosing {@link IRBuilder}, so coordinate computations participate in
 * the outer hash-cons table — that's what enables cross-spline CSE (continents/erosion/
 * weirdness shared between many splines) and is the single biggest source of speed-up.
 *
 * <p>{@code Multipoint} splines are kept as {@link IRNode.Spline.Multipoint} for the
 * codegen to lower into an inline binary-search ladder; we don't flatten further here
 * because the codegen needs the structured form anyway.
 *
 * <p><b>Vanilla coverage</b>: in Minecraft 1.21.x, {@link CubicSpline} has only two
 * implementations — {@link CubicSpline.Constant} and {@link CubicSpline.Multipoint}.
 * Worldgen JSON therefore cannot produce any other shape; the fallback branch below
 * is defensive.
 */
public final class SplineInliner {

    private final IRBuilder outerBuilder;

    public SplineInliner(IRBuilder outerBuilder) {
        this.outerBuilder = outerBuilder;
    }

    /**
     * Convert a {@link CubicSpline} (the worldgen flavour: {@code <Spline.Point,
     * Spline.Coordinate>}) into IR. Only the worldgen flavour is handled; other
     * specialisations fall back to an opaque IR node by being captured as an extern at
     * the calling site.
     */
    public IRNode inline(CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline) {
        return walk(spline);
    }

    @SuppressWarnings("unchecked")
    private IRNode.Spline walk(CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline) {
        if (spline instanceof CubicSpline.Constant<?, ?> c) {
            return new IRNode.Spline.Constant(c.value());
        }
        if (spline instanceof CubicSpline.Multipoint<?, ?> raw) {
            CubicSpline.Multipoint<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> mp =
                    (CubicSpline.Multipoint<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>) raw;
            // Coordinate goes through the outer IRBuilder so it shares with sibling DFs.
            IRNode coord = outerBuilder.walkChild(mp.coordinate().function().value());

            List<IRNode.Spline> values = new ArrayList<>(mp.values().size());
            for (CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> v : mp.values()) {
                values.add(walk(v));
            }

            return new IRNode.Spline.Multipoint(
                    coord,
                    mp.locations().clone(),
                    values,
                    mp.derivatives().clone(),
                    mp.minValue(),
                    mp.maxValue());
        }
        throw new UnsupportedSplineException("Unsupported CubicSpline implementation: " + spline.getClass().getName());
    }

    public static final class UnsupportedSplineException extends RuntimeException {
        public UnsupportedSplineException(String message) {
            super(message);
        }
    }
}

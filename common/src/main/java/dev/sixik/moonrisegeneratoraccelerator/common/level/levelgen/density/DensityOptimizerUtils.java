package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.density;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;

import java.util.Optional;

public class DensityOptimizerUtils {

    public static boolean isConst(DensityFunction densityFunction) {
        return densityFunction instanceof DensityFunctions.Constant
                || densityFunction instanceof DensityFunctions.BlendOffset
                || densityFunction instanceof DensityFunctions.BlendAlpha
                || densityFunction instanceof DensityFunctions.BeardifierMarker;
    }

    public static Optional<Double> getValueIfConstant(DensityFunction densityFunction) {

        if(densityFunction instanceof DensityFunctions.Constant constant)
            return Optional.of(constant.value());
        if(densityFunction instanceof DensityFunctions.BlendOffset blendOffset) {
            return Optional.of(0.0);
        }
        if(densityFunction instanceof DensityFunctions.BlendAlpha blendAlpha)
            return Optional.of(1.0);
        if(densityFunction instanceof DensityFunctions.BeardifierMarker beardifierMarker)
            return Optional.of(0.0);

        return Optional.empty();
    }

    /**
     * Removes wrapper layers from density functions to expose the core operation.
     *
     * <p>Specifically handles:
     * <ul>
     *   <li>{@code HolderHolder} - extracts the bound function value if available</li>
     *   <li>{@code MarkerOrMarked} - returns the wrapped inner function</li>
     *   <li>All other types - returns the function unchanged</li>
     * </ul></p>
     *
     * @param f The density function to unwrap
     * @return The innermost function after removing wrapper layers
     */
    public static DensityFunction unwrap(DensityFunction f) {
        if(f instanceof NoiseChunk.NoiseChunkDensityFunction w) {
            return w.wrapped();
        }
        if (f instanceof DensityFunctions.HolderHolder holder) {
            return holder.function().isBound() ? holder.function().value() : f;
        }

        return f;
    }

    public static DensityFunction deepUnwrap(DensityFunction f) {
        DensityFunction current = f;

        while (true) {
            if (current instanceof NoiseChunk.NoiseChunkDensityFunction w) {
                current = w.wrapped();
                continue;
            }
            if (current instanceof DensityFunctions.HolderHolder holder) {
                if (holder.function().isBound()) {
                    current = holder.function().value();
                    continue;
                }
                break;
            }
            break;
        }

        return current;
    }

    /**
     * Applies a mapping transformation to a constant value.
     *
     * @param type The type of mapping operation to apply
     * @param val The input value to transform
     * @return The transformed value according to the mapping type
     */
    public static double transformMapped(DensityFunctions.Mapped.Type type, double val) {
        return switch (type) {
            case ABS -> Math.abs(val);
            case SQUARE -> val * val;
            case CUBE -> val * val * val;
            case HALF_NEGATIVE -> val > 0 ? val : val * 0.5;
            case QUARTER_NEGATIVE -> val > 0 ? val : val * 0.25;
            case SQUEEZE -> {
                double e = Mth.clamp(val, -1.0, 1.0);
                yield e / 2.0 - e * e * e / 24.0;
            }
        };
    }

    /**
     * Checks if a density function represents the constant zero.
     *
     * @param f The density function to check
     * @return {@code true} if the function is a constant with value 0.0
     */
    public static boolean isZero(DensityFunction f) {
        return getValueIfConstant(f).filter(aDouble -> aDouble == 0.0).isPresent();
    }

    /**
     * Checks if a density function represents the constant one.
     *
     * @param f The density function to check
     * @return {@code true} if the function is a constant with value 1.0
     */
    public static boolean isOne(DensityFunction f) {
        return getValueIfConstant(f).filter(aDouble -> aDouble == 1.0).isPresent();
    }
}

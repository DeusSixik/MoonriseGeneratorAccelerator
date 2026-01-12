package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Optimizes density function expressions by applying constant folding and branch pruning.
 *
 * <p>This optimizer traverses density function trees recursively, replacing operations
 * with constant results where possible and eliminating unnecessary branches based on
 * value ranges. It uses memoization to avoid redundant computations and prevent infinite
 * recursion on cyclic graphs.</p>
 *
 * <p>Key optimizations performed include:
 * <ul>
 *   <li>Constant folding for arithmetic operations (ADD, MUL, MIN, MAX)</li>
 *   <li>Removal of redundant clamp operations when bounds are already satisfied</li>
 *   <li>Branch pruning for conditional expressions when conditions are statically determined</li>
 *   <li>Simplification of operations with identity elements (0 for ADD, 1 for MUL)</li>
 *   <li>Folding of mapped operations when applied to constants</li>
 *   <li>Elimination of unnecessary wrappers and markers</li>
 * </ul>
 * </p>
 */
public class DensityOptimizer {

    /*
        The cache is necessary in order not to get stuck in
        recursive graphs and optimize the same thing twice.
     */
    private final Map<DensityFunction, DensityFunction> cache = new IdentityHashMap<>();


    /**
     * Optimizes a density function by applying simplification rules recursively.
     *
     * <p>This method traverses the function tree in a bottom-up fashion:
     * <ol>
     *   <li>First optimizes all child functions recursively</li>
     *   <li>Then applies local simplification rules to the current node</li>
     * </ol>
     * Results are cached using identity-based hashing to ensure each function
     * is optimized only once, even in presence of shared subexpressions.</p>
     *
     * @param function The density function to optimize
     * @return An optimized equivalent function, potentially simplified to a constant
     *         or with fewer operations
     */
    public DensityFunction optimize(DensityFunction function) {
        if (cache.containsKey(function)) {
            return cache.get(function);
        }

        /*
            Recursively optimizing children (bottom-up approach)
         */
        DensityFunction optimizedChildren = function.mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction child) {
                return rewriteLocal(child);
            }

            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
                return noise;
            }
        });

        /*
            Applying the simplification rules to the current node
         */
        final DensityFunction result = rewriteLocal(optimizedChildren);

        cache.put(function, result);
        return result;
    }

    /**
     * Applies local simplification rules to a single density function node.
     *
     * <p>This method examines the function structure and applies optimization
     * rules such as constant folding, range-based pruning, and identity
     * simplifications. The function is first unwrapped to remove any
     * HolderHolder or MarkerOrMarked wrappers before analysis.</p>
     *
     * @param f The density function node to rewrite
     * @return A potentially simplified version of the input function
     */
    private DensityFunction rewriteLocal(DensityFunction f) {
        /*
            The most important step is to remove the wrappers to see the real data.
         */
        DensityFunction unwrapped = unwrap(f);

        if (unwrapped instanceof DensityFunctions.Constant) {
            return unwrapped;
        }

        if (f instanceof DensityFunctions.MarkerOrMarked marker) {
            DensityFunction inner = unwrap(marker.wrapped());
            if (inner instanceof DensityFunctions.Constant) {
                return inner;
            }
        }

        /*
            Constant Folding (Mapped & Clamp)
         */
        if (f instanceof DensityFunctions.Mapped mapped) {
            DensityFunction inner = unwrap(mapped.input());
            if (inner instanceof DensityFunctions.Constant c) {
                return DensityFunctions.constant(transformMapped(mapped.type(), c.value()));
            }
        }

        if (f instanceof DensityFunctions.Clamp clamp) {
            DensityFunction inner = unwrap(clamp.input());
            if (inner instanceof DensityFunctions.Constant c) {
                return DensityFunctions.constant(Mth.clamp(c.value(), clamp.minValue(), clamp.maxValue()));
            }

            /*
                If the input range is already inside the boundaries of the clamp -> clamp is not needed
             */
            if (inner.minValue() >= clamp.minValue() && inner.maxValue() <= clamp.maxValue()) {
                return clamp.input();
            }
        }

        /*
            Arithmetic Folding (Add, Mul, Min, Max)
         */
        if (f instanceof DensityFunctions.TwoArgumentSimpleFunction ap2) {
            DensityFunction a = ap2.argument1();
            DensityFunction b = ap2.argument2();

            /*
                We always look at the deployed versions for verification
             */
            DensityFunction aUnwrapped = unwrap(a);
            DensityFunction bUnwrapped = unwrap(b);

            var type = ap2.type();

            /*
                Constant + Constant
             */
            if (aUnwrapped instanceof DensityFunctions.Constant ca && bUnwrapped instanceof DensityFunctions.Constant cb) {
                return switch (type) {
                    case ADD -> DensityFunctions.constant(ca.value() + cb.value());
                    case MUL -> DensityFunctions.constant(ca.value() * cb.value());
                    case MIN -> DensityFunctions.constant(Math.min(ca.value(), cb.value()));
                    case MAX -> DensityFunctions.constant(Math.max(ca.value(), cb.value()));
                };
            }

            /*
                Simplification from 0 and 1
             */
            if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
                if (isZero(aUnwrapped)) return b;
                if (isZero(bUnwrapped)) return a;
            }

            if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
                if (isZero(aUnwrapped) || isZero(bUnwrapped)) return DensityFunctions.zero();
                if (isOne(aUnwrapped)) return b;
                if (isOne(bUnwrapped)) return a;
            }

            /*
                Pruning (removing MIN/MAX branches by ranges)
             */
            if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MIN) {
                if (aUnwrapped.maxValue() <= bUnwrapped.minValue()) return a;
                if (bUnwrapped.maxValue() <= aUnwrapped.minValue()) return b;
            }
            if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MAX) {
                if (aUnwrapped.minValue() >= bUnwrapped.maxValue()) return a;
                if (bUnwrapped.minValue() >= aUnwrapped.maxValue()) return b;
            }

//            /*
//                We pass the Ms to the constructor "a" and "b" (possibly wrapped in a Holder),
//                or the expanded "aU"/"bU" — preferably expanded to remove the extra call stack.
//             */
//            return switch (type) {
//                case ADD -> new DensitySpecializations.FastAdd(aUnwrapped, bUnwrapped);
//                case MUL -> new DensitySpecializations.FastMul(aUnwrapped, bUnwrapped);
//                case MIN -> new DensitySpecializations.FastMin(aUnwrapped, bUnwrapped);
//                case MAX -> new DensitySpecializations.FastMax(aUnwrapped, bUnwrapped);
//            };
        }

        /*
            MulOrAdd Folding
         */
        if (f instanceof DensityFunctions.MulOrAdd ma) {
            DensityFunction inputU = unwrap(ma.input());
            if (inputU instanceof DensityFunctions.Constant c) {
                return switch (ma.specificType()) {
                    case ADD -> DensityFunctions.constant(c.value() + ma.argument());
                    case MUL -> DensityFunctions.constant(c.value() * ma.argument());
                };
            }

//            return switch (ma.specificType()) {
//                case ADD -> new DensitySpecializations.FastLinearAdd(inputU, ma.argument());
//                case MUL -> new DensitySpecializations.FastLinearMul(inputU, ma.argument());
//            };
        }

        /*
            Branch Pruning (RangeChoice)
         */
        if (f instanceof DensityFunctions.RangeChoice rc) {
            DensityFunction input = unwrap(rc.input());
            double min = input.minValue();
            double max = input.maxValue();

            /*
                If the input is always out of range -> take the else branch
             */
            if (max < rc.minInclusive() || min >= rc.maxExclusive()) {
                return rc.whenOutOfRange();
            }

            /*
                If the input is always inside the range -> take the then branch
             */
            if (min >= rc.minInclusive() && max < rc.maxExclusive()) {
                return rc.whenInRange();
            }

            /*
                If the branches are identical -> the condition is not needed
             */
            if (unwrap(rc.whenInRange()).equals(unwrap(rc.whenOutOfRange()))) {
                return rc.whenInRange();
            }
        }

        /*
            Spline Folding
         */
        if (f instanceof DensityFunctions.Spline spline) {
            if (spline.minValue() == spline.maxValue()) {
                return DensityFunctions.constant(spline.minValue());
            }
        }

        if (f instanceof DensityFunctions.ShiftedNoise sn) {
            DensityFunction sx = unwrap(sn.shiftX());
            DensityFunction sy = unwrap(sn.shiftY());
            DensityFunction sz = unwrap(sn.shiftZ());

            if (isZero(sx) && isZero(sy) && isZero(sz)) {
                return new DensityFunctions.Noise(sn.noise(), sn.xzScale(), sn.yScale());
            }
        }

        return f;
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
    private DensityFunction unwrap(DensityFunction f) {
        if (f instanceof DensityFunctions.HolderHolder holder) {
            return holder.function().isBound() ? holder.function().value() : f;
        }
        if (f instanceof DensityFunctions.MarkerOrMarked marker) {
            return marker.wrapped();
        }
        return f;
    }

    /**
     * Applies a mapping transformation to a constant value.
     *
     * @param type The type of mapping operation to apply
     * @param val The input value to transform
     * @return The transformed value according to the mapping type
     */
    private double transformMapped(DensityFunctions.Mapped.Type type, double val) {
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
    private static boolean isZero(DensityFunction f) {
        return f instanceof DensityFunctions.Constant c && c.value() == 0.0;
    }

    /**
     * Checks if a density function represents the constant one.
     *
     * @param f The density function to check
     * @return {@code true} if the function is a constant with value 1.0
     */
    private static boolean isOne(DensityFunction f) {
        return f instanceof DensityFunctions.Constant c && c.value() == 1.0;
    }
}


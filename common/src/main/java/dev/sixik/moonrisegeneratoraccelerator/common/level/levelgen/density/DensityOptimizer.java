package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.density;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.density.wrappers.ConstantShiftedNoise;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

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
     * @param function The density function node to rewrite
     * @return A potentially simplified version of the input function
     */
    private DensityFunction rewriteLocal(DensityFunction function) {
        /*
            The most important step is to remove the wrappers to see the real data.
         */
        DensityFunction unwrapped = unwrap(function);

        if (DensityOptimizerUtils.isConst(unwrapped)) {
            return unwrapped;
        }

        if (unwrapped instanceof DensityFunctions.MarkerOrMarked marker) {
            DensityFunction inner = unwrap(marker.wrapped());
            if (DensityOptimizerUtils.isConst(inner)) {
                return inner;
            }
        }

        /*
            Constant Folding (Mapped & Clamp)
         */
        if (unwrapped instanceof DensityFunctions.Mapped mapped) {
            DensityFunction inner = unwrap(mapped.input());

            var opt = DensityOptimizerUtils.getValueIfConstant(inner);
            if(opt.isPresent()) {
                return DensityFunctions.constant(transformMapped(mapped.type(), opt.get()));
            }

//           return DensitySpecializations.create(mapped.type(), function, inner);
        }

        if (unwrapped instanceof DensityFunctions.Clamp clamp) {
            DensityFunction inner = unwrap(clamp.input());
            var opt = DensityOptimizerUtils.getValueIfConstant(inner);

            if(opt.isPresent()) {
                return DensityFunctions.constant(Mth.clamp(opt.get(), clamp.minValue(), clamp.maxValue()));
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
        if (unwrapped instanceof DensityFunctions.TwoArgumentSimpleFunction ap2) {
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

            var opt1 = DensityOptimizerUtils.getValueIfConstant(aUnwrapped);
            var opt2 = DensityOptimizerUtils.getValueIfConstant(bUnwrapped);

            if (opt1.isPresent() && opt2.isPresent()) {
                return switch (type) {
                    case ADD -> DensityFunctions.constant(opt1.get() + opt2.get());
                    case MUL -> DensityFunctions.constant(opt1.get() * opt2.get());
                    case MIN -> DensityFunctions.constant(Math.min(opt1.get(), opt2.get()));
                    case MAX -> DensityFunctions.constant(Math.max(opt1.get(), opt2.get()));
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

            /*
                We pass the Ms to the constructor "a" and "b" (possibly wrapped in a Holder),
                or the expanded "aU"/"bU" — preferably expanded to remove the extra call stack.
             */
//            return switch (type) {
//                case ADD -> new DensitySpecializations.FastAdd(function, aUnwrapped, bUnwrapped);
//                case MUL -> new DensitySpecializations.FastMul(function, aUnwrapped, bUnwrapped);
//                case MIN -> new DensitySpecializations.FastMin(function, aUnwrapped, bUnwrapped);
//                case MAX -> new DensitySpecializations.FastMax(function, aUnwrapped, bUnwrapped);
//            };
        }

        /*
            MulOrAdd Folding
         */
        if (unwrapped instanceof DensityFunctions.MulOrAdd ma) {
            DensityFunction inputU = unwrap(ma.input());
            Optional<Double> opt = DensityOptimizerUtils.getValueIfConstant(inputU);

            if(opt.isPresent()) {
                return switch (ma.specificType()) {
                    case ADD -> DensityFunctions.constant(opt.get() + ma.argument());
                    case MUL -> DensityFunctions.constant(opt.get() * ma.argument());
                };
            }

//            return switch (ma.specificType()) {
//                case ADD -> new DensitySpecializations.FastAddConstant(function, ma.input(), ma.argument(), ma.minValue(), ma.maxValue());
//                case MUL -> new DensitySpecializations.FastMulConstant(function, ma.input(), ma.argument(), ma.minValue(), ma.maxValue());
//            };
        }

        /*
            Branch Pruning (RangeChoice)
         */
        if (unwrapped instanceof DensityFunctions.RangeChoice rc) {
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
        if (unwrapped instanceof DensityFunctions.Spline spline) {
            if (spline.minValue() == spline.maxValue()) {
                return DensityFunctions.constant(spline.minValue());
            }
        }

        if (unwrapped instanceof DensityFunctions.ShiftedNoise sn) {
            DensityFunction sx = unwrap(sn.shiftX());
            DensityFunction sy = unwrap(sn.shiftY());
            DensityFunction sz = unwrap(sn.shiftZ());

            if (isZero(sx) && isZero(sy) && isZero(sz)) {
                return new DensityFunctions.Noise(sn.noise(), sn.xzScale(), sn.yScale());
            }

//            var optsx = DensityOptimizerUtils.getValueIfConstant(sx);
//            var optsy = DensityOptimizerUtils.getValueIfConstant(sy);
//            var optsz = DensityOptimizerUtils.getValueIfConstant(sz);
//
//            if(optsx.isPresent() && optsy.isPresent() && optsz.isPresent()) {
//                return new ConstantShiftedNoise(optsx.get(), optsy.get(), optsz.get(), sn.xzScale(), sn.yScale(), sn.noise());
//            }
        }

//        if(f instanceof DensityFunctions.Spline spline) {
//            return convertInner(spline.spline());
//        }

        return unwrapped;
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
        return DensityOptimizerUtils.deepUnwrap(f);
    }

    /**
     * Applies a mapping transformation to a constant value.
     *
     * @param type The type of mapping operation to apply
     * @param val The input value to transform
     * @return The transformed value according to the mapping type
     */
    private double transformMapped(DensityFunctions.Mapped.Type type, double val) {
        return DensityOptimizerUtils.transformMapped(type, val);
    }

    /**
     * Checks if a density function represents the constant zero.
     *
     * @param f The density function to check
     * @return {@code true} if the function is a constant with value 0.0
     */
    private static boolean isZero(DensityFunction f) {
        return DensityOptimizerUtils.isZero(f);
    }

    /**
     * Checks if a density function represents the constant one.
     *
     * @param f The density function to check
     * @return {@code true} if the function is a constant with value 1.0
     */
    private static boolean isOne(DensityFunction f) {
        return DensityOptimizerUtils.isOne(f);
    }
}


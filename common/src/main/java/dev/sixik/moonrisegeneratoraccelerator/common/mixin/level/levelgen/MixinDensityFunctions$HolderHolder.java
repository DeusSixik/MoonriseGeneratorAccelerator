package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.*;

@Mixin(DensityFunctions.HolderHolder.class)
public abstract class MixinDensityFunctions$HolderHolder implements DensityFunction {

    @Shadow
    @Final
    private Holder<DensityFunction> function;
    @Unique
    private DensityFunction bts$densityFunctionCached;

    @Unique
    private double bts$minValue = Double.NEGATIVE_INFINITY;

    @Unique
    private double bts$maxValue = Double.POSITIVE_INFINITY;

    @Unique
    private boolean bts$isBound;

    @Unique
    private void bts$cache() {
        final Holder<DensityFunction> function = this.function;
        bts$isBound = function.isBound();

        if(bts$isBound) {
            if(bts$densityFunctionCached == null) {
                bts$densityFunctionCached = function.value();
                bts$minValue = bts$densityFunctionCached.minValue();
                bts$maxValue = bts$densityFunctionCached.maxValue();
            }
        } else {
            bts$densityFunctionCached = null;
            bts$minValue = Double.NEGATIVE_INFINITY;
            bts$maxValue = Double.POSITIVE_INFINITY;
        }
    }

    /**
     * @author Sixik
     * @reason Use pre getting value
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext functionContext) {
        bts$cache();
        return bts$densityFunctionCached.compute(functionContext);
    }

    /**
     * @author Sixik
     * @reason Use pre getting value
     */
    @Overwrite
    public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
        bts$cache();
        bts$densityFunctionCached.fillArray(ds, contextProvider);
    }

    /**
     * @author Sixik
     * @reason Use pre getting value
     */
    @Overwrite
    public double minValue() {
        bts$cache();
        return bts$minValue;
    }

    /**
     * @author Sixik
     * @reason Use pre getting value
     */
    @Overwrite
    public double maxValue() {
        bts$cache();
        return bts$maxValue;
    }
}

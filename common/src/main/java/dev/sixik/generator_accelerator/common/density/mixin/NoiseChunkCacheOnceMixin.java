package dev.sixik.generator_accelerator.common.density.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheOnceWrappedAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * One-slot and optional array-row cache: mirrors the fast branches of
 * {@code NoiseChunk.CacheOnce#compute}.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheOnce")
public class NoiseChunkCacheOnceMixin implements DfcCellCacheAccess, DfcCacheOnceWrappedAccess {

    @Shadow
    @Final
    private NoiseChunk field_36605;

    @Shadow
    @Final
    private DensityFunction function;

    @Shadow
    private long lastCounter;

    @Shadow
    private long lastArrayCounter;

    @Shadow
    private double lastValue;

    @Shadow
    private double[] lastArray;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        if (context != this.field_36605) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        double[] array = this.lastArray;
        if (array != null
                && this.lastArrayCounter == this.field_36605.arrayInterpolationCounter) {
            int index = this.field_36605.arrayIndex;
            if (index >= 0 && index < array.length) {
                return array[index];
            }
        }
        if (this.lastCounter == this.field_36605.interpolationCounter) {
            return this.lastValue;
        }
        return DfcCacheFastPath.CACHE_MISS;
    }

    @Override
    public double dfc$computeWrapped(DensityFunction.FunctionContext context) {
        return this.function.compute(context);
    }

    @Override
    public DensityFunction dfc$wrappedFunction() {
        return this.function;
    }
}

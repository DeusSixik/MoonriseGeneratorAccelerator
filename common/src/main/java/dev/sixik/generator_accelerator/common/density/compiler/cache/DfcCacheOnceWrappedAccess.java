package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Optional bridge for {@code NoiseChunk.CacheOnce}: compute the wrapped
 * function directly when the caller already decided the cache probe is useless.
 */
public interface DfcCacheOnceWrappedAccess {

    double dfc$computeWrapped(DensityFunction.FunctionContext context);

    DensityFunction dfc$wrappedFunction();
}

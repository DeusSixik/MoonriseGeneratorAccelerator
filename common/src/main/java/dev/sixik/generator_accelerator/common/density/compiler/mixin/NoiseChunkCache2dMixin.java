package dev.sixik.generator_accelerator.common.density.compiler.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcThreadOwnedCacheAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 2D (XZ) one-slot cache: same (blockX, blockZ) as last call returns the stored value.
 * Generated direct reads only use the cache from its owner thread; foreign threads miss
 * and fall back to a direct compute without mutating the shared slot.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$Cache2D")
public class NoiseChunkCache2dMixin implements DfcCellCacheAccess {

    @Shadow
    private long lastPos2D;

    @Shadow
    private double lastValue;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        if (!((Object) this instanceof DfcThreadOwnedCacheAccess owner)
                || !owner.dfc$isOwnedByCurrentThread()) {
            return DfcCacheFastPath.CACHE_MISS;
        }

        final int bx = context.blockX();
        final int bz = context.blockZ();
        final long key = (long)bx & 0xFFFFFFFFL | ((long)bz << 32);

        if (this.lastPos2D == key) {
            return this.lastValue;
        }
        return DfcCacheFastPath.CACHE_MISS;
    }
}

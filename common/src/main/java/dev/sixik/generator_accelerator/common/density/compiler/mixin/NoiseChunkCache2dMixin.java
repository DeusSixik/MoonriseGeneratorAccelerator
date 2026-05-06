package dev.sixik.generator_accelerator.common.density.compiler.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 2D (XZ) one-slot cache: same (blockX, blockZ) as last call returns the stored value.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$Cache2D")
public class NoiseChunkCache2dMixin implements DfcCellCacheAccess {

    @Shadow
    private long lastPos2D;

    @Shadow
    private double lastValue;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        int bx = context.blockX();
        int bz = context.blockZ();
        if (this.lastPos2D == ChunkPos.asLong(bx, bz)) {
            return this.lastValue;
        }
        return DfcCacheFastPath.CACHE_MISS;
    }
}

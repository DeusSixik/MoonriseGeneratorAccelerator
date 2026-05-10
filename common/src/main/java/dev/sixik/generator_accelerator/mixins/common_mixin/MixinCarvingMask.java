package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$CarvingMaskExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarvingMask;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.BitSet;

@Mixin(CarvingMask.class)
public class MixinCarvingMask implements GA$CarvingMaskExtension {

    @Shadow
    @Final
    private BitSet mask;
    @Shadow @Final private int minY;
    @Shadow private CarvingMask.Mask additionalMask;

    @Override
    public void bts$addPositionsFast(ChunkPos chunkPos, LongArrayList output) {
        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();

        for (int i = this.mask.nextSetBit(0); i >= 0; i = this.mask.nextSetBit(i + 1)) {
            int lx = i & 15;
            int lz = (i >> 4) & 15;
            int ly = (i >> 8) + this.minY;

            output.add(BlockPos.asLong(startX + lx, ly, startZ + lz));
        }
    }

    @Override
    public void bts$addPositionsRaw(ChunkPos chunkPos, LongScratchBuffer output) {
        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();

        for (int i = this.mask.nextSetBit(0); i >= 0; i = this.mask.nextSetBit(i + 1)) {
            int lx = i & 15;
            int lz = (i >> 4) & 15;
            int ly = (i >> 8) + this.minY;

            output.add(BlockPos.asLong(startX + lx, ly, startZ + lz));
        }
    }

    @Override
    public boolean ga$setIfAbsent(int x, int y, int z) {
        if (this.additionalMask != null && this.additionalMask.test(x, y, z)) {
            return false;
        }

        int index = this.ga$index(x, y, z);
        if (this.mask.get(index)) {
            return false;
        }

        this.mask.set(index);
        return true;
    }

    private int ga$index(int x, int y, int z) {
        return (x & 15) | ((z & 15) << 4) | ((y - this.minY) << 8);
    }
}

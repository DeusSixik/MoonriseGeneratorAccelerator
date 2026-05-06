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

    @Override
    public void bts$addPositionsFast(ChunkPos chunkPos, LongArrayList output) {
        this.bts$addPositions(chunkPos, output::add);
    }

    @Override
    public void bts$addPositionsRaw(ChunkPos chunkPos, LongScratchBuffer output) {
        this.bts$addPositions(chunkPos, output::add);
    }

    private void bts$addPositions(ChunkPos chunkPos, PositionConsumer output) {
        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();

        for (int i = this.mask.nextSetBit(0); i >= 0; i = this.mask.nextSetBit(i + 1)) {
            int lx = i & 15;
            int lz = (i >> 4) & 15;
            int ly = (i >> 8) + this.minY;

            output.add(BlockPos.asLong(startX + lx, ly, startZ + lz));
        }
    }

    private interface PositionConsumer {
        void add(long packedPos);
    }
}

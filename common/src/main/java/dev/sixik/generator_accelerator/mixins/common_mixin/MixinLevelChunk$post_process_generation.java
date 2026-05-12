package dev.sixik.generator_accelerator.mixins.common_mixin;

import com.google.common.collect.ImmutableList;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinChunkAccessAccessor;
import it.unimi.dsi.fastutil.shorts.ShortList;
import it.unimi.dsi.fastutil.shorts.ShortListIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = LevelChunk.class, priority = 999)
public abstract class MixinLevelChunk$post_process_generation {
    @Shadow
    @Final
    private Level level;

    @Shadow
    public abstract BlockState getBlockState(BlockPos pos);

    @Shadow
    public abstract BlockEntity getBlockEntity(BlockPos pos);

    /**
     * @author Sixik
     * @reason Use FastUtil's primitive short iterator and skip the pending block-entity
     * copy when there is nothing to promote.
     */
    @Overwrite
    public void postProcessGeneration() {
        LevelChunk self = (LevelChunk) (Object) this;
        ChunkAccess chunkAccess = (ChunkAccess) (Object) this;
        MixinChunkAccessAccessor chunkAccessor = (MixinChunkAccessAccessor) chunkAccess;
        ChunkPos chunkPos = self.getPos();
        int chunkMinX = chunkPos.x << 4;
        int chunkMinZ = chunkPos.z << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        ShortList[] lists = chunkAccessor.ga$getPostProcessing();
        for (int sectionIndex = 0; sectionIndex < lists.length; sectionIndex++) {
            ShortList list = lists[sectionIndex];
            if (list == null) {
                continue;
            }

            ShortListIterator iterator = list.iterator();
            int sectionY = chunkAccess.getSectionYFromSectionIndex(sectionIndex);
            int sectionMinY = sectionY << 4;
            while (iterator.hasNext()) {
                short packed = iterator.nextShort();
                pos.set(
                        chunkMinX + (packed & 15),
                        sectionMinY + ((packed >>> 4) & 15),
                        chunkMinZ + ((packed >>> 8) & 15)
                );
                BlockState state = this.getBlockState(pos);
                FluidState fluidState = state.getFluidState();
                if (!fluidState.isEmpty()) {
                    fluidState.tick(this.level, pos);
                }

                if (!(state.getBlock() instanceof LiquidBlock)) {
                    BlockState updatedState = Block.updateFromNeighbourShapes(state, this.level, pos);
                    this.level.setBlock(pos, updatedState, 20);
                }
            }
            list.clear();
        }

        var pendingBlockEntities = chunkAccessor.ga$getPendingBlockEntities();
        if (!pendingBlockEntities.isEmpty()) {
            for (BlockPos blockEntityPos : ImmutableList.copyOf(pendingBlockEntities.keySet())) {
                this.getBlockEntity(blockEntityPos);
            }
            pendingBlockEntities.clear();
        }
        chunkAccessor.ga$getUpgradeData().upgrade(self);
    }
}

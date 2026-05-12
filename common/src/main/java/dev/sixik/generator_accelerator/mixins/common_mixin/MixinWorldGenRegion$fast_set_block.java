package dev.sixik.generator_accelerator.mixins.common_mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldGenRegion.class)
public abstract class MixinWorldGenRegion$fast_set_block implements WorldGenLevel {

    @Shadow
    public abstract boolean ensureCanWrite(BlockPos pos);

    @Shadow
    public abstract @NotNull ChunkAccess getChunk(int x, int z);

    @Unique
    private int bts$lastChunkX = Integer.MIN_VALUE;
    @Unique
    private int bts$lastChunkZ = Integer.MIN_VALUE;
    @Unique
    private ChunkAccess bts$lastChunk = null;

    @Redirect(method = "setBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/WorldGenRegion;getChunk(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    public ChunkAccess setBlock(WorldGenRegion instance, BlockPos blockPos) {
        return this.ga$getCachedChunk(blockPos);
    }

    /**
     * @author Sixik
     * @reason Feature predicates repeatedly read adjacent blocks from the same chunk.
     * Cache the last chunk lookup and avoid WorldGenRegion#getChunk dependency checks.
     */
    @Overwrite
    public BlockState getBlockState(BlockPos blockPos) {
        return this.ga$getCachedChunk(blockPos).getBlockState(blockPos);
    }

    /**
     * @author Sixik
     * @reason Same hot chunk cache as getBlockState for fluid predicates/ticks.
     */
    @Overwrite
    public FluidState getFluidState(BlockPos blockPos) {
        return this.ga$getCachedChunk(blockPos).getFluidState(blockPos);
    }

    @Unique
    private ChunkAccess ga$getCachedChunk(BlockPos blockPos) {
        final int chunkX = blockPos.getX() >> 4;
        final int chunkZ = blockPos.getZ() >> 4;

        if (chunkX == this.bts$lastChunkX && chunkZ == this.bts$lastChunkZ && this.bts$lastChunk != null) {
            return this.bts$lastChunk;
        } else {
            final ChunkAccess chunkAccess = this.getChunk(chunkX, chunkZ);
            this.bts$lastChunkX = chunkX;
            this.bts$lastChunkZ = chunkZ;
            this.bts$lastChunk = chunkAccess;
            return chunkAccess;
        }
    }
}

package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.common.features.pipeline.DecorationWorkspaceBridge;
import dev.sixik.generator_accelerator.common.worldgen.GAWorldGenRegionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldGenRegion.class)
public abstract class MixinWorldGenRegion$fast_set_block implements WorldGenLevel, GAWorldGenRegionAccess {

    @Shadow
    public abstract boolean ensureCanWrite(BlockPos pos);

    @Shadow
    public abstract @NotNull ChunkAccess getChunk(int x, int z);

    @Shadow
    public abstract ChunkPos getCenter();

    @Shadow
    @Final
    private ChunkStep generatingStep;

    @Shadow
    @Final
    private ChunkAccess center;

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
        BlockState workspaceState = DecorationWorkspaceBridge.readCurrentWorkspaceBlock(blockPos);
        if (workspaceState != null) {
            return workspaceState;
        }
        return this.ga$getCachedChunk(blockPos).getBlockState(blockPos);
    }

    /**
     * @author Sixik
     * @reason Same hot chunk cache as getBlockState for fluid predicates/ticks.
     */
    @Overwrite
    public FluidState getFluidState(BlockPos blockPos) {
        BlockState workspaceState = DecorationWorkspaceBridge.readCurrentWorkspaceBlock(blockPos);
        if (workspaceState != null) {
            return workspaceState.getFluidState();
        }
        return this.ga$getCachedChunk(blockPos).getFluidState(blockPos);
    }

    @Redirect(
            method = "setBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"
            )
    )
    private BlockState ga$setBlockStateAndMirrorWorkspace(
            ChunkAccess chunk,
            BlockPos pos,
            BlockState state,
            boolean moved
    ) {
        BlockState previous = chunk.setBlockState(pos, state, moved);
        DecorationWorkspaceBridge.mirrorCurrentWorkspaceWrite(chunk, pos, state);
        return previous;
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

    @Override
    public boolean ga$canWriteWithoutLogging(BlockPos pos) {
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        ChunkPos centerPos = this.getCenter();
        int radius = Math.max(0, this.generatingStep.blockStateWriteRadius());
        if (Math.abs(centerPos.x - chunkX) > radius || Math.abs(centerPos.z - chunkZ) > radius) {
            return false;
        }
        if (!this.center.isUpgrading()) {
            return true;
        }
        LevelHeightAccessor height = this.center.getHeightAccessorForGeneration();
        return pos.getY() >= height.getMinBuildHeight() && pos.getY() < height.getMaxBuildHeight();
    }
}

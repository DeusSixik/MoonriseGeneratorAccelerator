package dev.sixik.generator_accelerator.mixins.common_mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
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

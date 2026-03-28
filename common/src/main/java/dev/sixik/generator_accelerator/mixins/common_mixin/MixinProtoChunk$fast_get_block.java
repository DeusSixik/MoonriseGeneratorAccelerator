package dev.sixik.generator_accelerator.mixins.common_mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ProtoChunk.class)
public abstract class MixinProtoChunk$fast_get_block extends ChunkAccess {

    @Unique
    private int bts$minY;

    @Unique
    private int bts$maxY;

    @Unique
    private int bts$minSection;

    private MixinProtoChunk$fast_get_block(ChunkPos chunkPos, UpgradeData upgradeData, LevelHeightAccessor levelHeightAccessor, Registry<Biome> registry, long l, @Nullable LevelChunkSection[] levelChunkSections, @Nullable BlendingData blendingData) {
        super(chunkPos, upgradeData, levelHeightAccessor, registry, l, levelChunkSections, blendingData);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;[Lnet/minecraft/world/level/chunk/LevelChunkSection;Lnet/minecraft/world/ticks/ProtoChunkTicks;Lnet/minecraft/world/ticks/ProtoChunkTicks;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V", at = @At("TAIL"))
    public void bts$init(CallbackInfo ci) {
        this.bts$maxY = getMaxBuildHeight();
        this.bts$minY = getMinBuildHeight();
        this.bts$minSection = getMinSection();
    }

    @Redirect(method = {"getBlockState", "getFluidState"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ProtoChunk;isOutsideBuildHeight(I)Z"))
    public boolean bts$redirect$isOutsideBuildHeight(ProtoChunk instance, int y) {
        return y < bts$minY || y >= bts$maxY;
    }

    @Redirect(method = {"getBlockState", "getFluidState"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ProtoChunk;getSectionIndex(I)I"))
    public int bts$redirect$getSectionIndex(ProtoChunk instance, int y) {
        return (y >> 4) - bts$minSection;
    }

    @Redirect(method = "markPosForPostprocessing", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ProtoChunk;isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"))
    public boolean bts$redirect$isOutsideBuildHeight(ProtoChunk instance, BlockPos blockPos) {
        final int y = blockPos.getY();
        return y < bts$minY || y >= bts$maxY;
    }
}

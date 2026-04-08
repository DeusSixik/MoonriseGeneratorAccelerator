package dev.sixik.generator_accelerator.mixins.common_mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumSet;

@Mixin(ProtoChunk.class)
public abstract class MixinProtoChunk$fast_set_block extends ChunkAccess {

    private MixinProtoChunk$fast_set_block(ChunkPos chunkPos, UpgradeData upgradeData, LevelHeightAccessor levelHeightAccessor, Registry<Biome> registry, long l, @Nullable LevelChunkSection[] levelChunkSections, @Nullable BlendingData blendingData) {
        super(chunkPos, upgradeData, levelHeightAccessor, registry, l, levelChunkSections, blendingData);
    }

    @Shadow public abstract @NotNull ChunkStatus getPersistedStatus();

    @Unique
    private static final Heightmap.Types[] bts$HEIGHTMAP_TYPES = Heightmap.Types.values();

    @Inject(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/status/ChunkStatus;heightmapsAfter()Ljava/util/EnumSet;"), cancellable = true)
    public void optimizeSetBlockHeightmap(
            BlockPos blockPos, BlockState blockState, boolean bl, CallbackInfoReturnable<BlockState> cir,
            @Local(ordinal = 1) int y,
            @Local(ordinal = 4) int lx,
            @Local(ordinal = 6) int lz,
            @Local(ordinal = 0) BlockState blockState2
    ) {
        EnumSet<Heightmap.Types> activeTypes = this.getPersistedStatus().heightmapsAfter();
        boolean needsPriming = false;

        for (int i = 0; i < bts$HEIGHTMAP_TYPES.length; i++) {
            Heightmap.Types type = bts$HEIGHTMAP_TYPES[i];
            if (activeTypes.contains(type) && this.heightmaps.get(type) == null) {
                needsPriming = true;
                break;
            }
        }

        if (needsPriming) {
            EnumSet<Heightmap.Types> toPrime = EnumSet.noneOf(Heightmap.Types.class);
            for (int i = 0; i < bts$HEIGHTMAP_TYPES.length; i++) {
                Heightmap.Types type = bts$HEIGHTMAP_TYPES[i];
                if (activeTypes.contains(type) && this.heightmaps.get(type) == null) {
                    toPrime.add(type);
                }
            }
            Heightmap.primeHeightmaps((ProtoChunk) (Object) this, toPrime);
        }

        for (int i = 0; i < bts$HEIGHTMAP_TYPES.length; i++) {
            Heightmap.Types type = bts$HEIGHTMAP_TYPES[i];
            if (activeTypes.contains(type)) {
                Heightmap map = this.heightmaps.get(type);
                if (map != null) {
                    map.update(lx, y, lz, blockState);
                }
            }
        }

        cir.setReturnValue(blockState2);
    }
}

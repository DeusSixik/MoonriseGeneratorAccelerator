package dev.sixik.generator_accelerator.common.surface.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.sixik.generator_accelerator.common.surface.FastBlockColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SurfaceSystem.class)
public class MixinSurfaceSystem$fast_set_block {

    private BlockPos.MutableBlockPos bts$pos;
    private ChunkAccess bts$chunk;

    @ModifyVariable(method = "buildSurface", at = @At(value = "STORE"), name = "blockColumn")
    public BlockColumn bts$modifyArg(BlockColumn value) {
        return new FastBlockColumn(bts$chunk, bts$pos);
    }

    @Inject(method = "buildSurface", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getPos()Lnet/minecraft/world/level/ChunkPos;", shift = At.Shift.AFTER))
    public void bts$buildSurface$redirect_block_column(
            RandomState randomState, BiomeManager biomeManager, Registry<Biome> registry, boolean bl,
            WorldGenerationContext worldGenerationContext, ChunkAccess chunkAccess,
            NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource, CallbackInfo ci,
            @Local(ordinal = 0) BlockPos.MutableBlockPos blockPos) {
        bts$pos = blockPos;
        bts$chunk = chunkAccess;
    }
}

package dev.sixik.generator_accelerator.common.surface.mixin;

import dev.sixik.generator_accelerator.common.surface.FastBlockColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(SurfaceSystem.class)
public class MixinSurfaceSystem$fast_set_block {

    @Unique
    private final ThreadLocal<BlockPos.MutableBlockPos> bts$pos = new ThreadLocal<>();

    @Unique
    private final ThreadLocal<ChunkAccess> bts$chunk = new ThreadLocal<>();

    @Inject(method = "buildSurface", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getPos()Lnet/minecraft/world/level/ChunkPos;", shift = At.Shift.AFTER), locals = LocalCapture.CAPTURE_FAILHARD)
    public void bts$buildSurface$redirect_block_column(
            RandomState randomState, BiomeManager biomeManager, Registry<Biome> registry,
            boolean bl, WorldGenerationContext worldGenerationContext, ChunkAccess chunkAccess,
            NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource, CallbackInfo ci,
            BlockPos.MutableBlockPos mutableBlockPos) {

        bts$pos.set(mutableBlockPos);
        bts$chunk.set(chunkAccess);
    }

    @ModifyVariable(method = "buildSurface", at = @At(value = "STORE"), ordinal = 0)
    public BlockColumn bts$modifyArg(BlockColumn value) {
        return new FastBlockColumn(bts$chunk.get(), bts$pos.get());
    }
}

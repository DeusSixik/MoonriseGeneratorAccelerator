package dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sixik.generator_accelerator.common.biome.ClimateSamplerRaw;
import dev.sixik.generator_accelerator.common.biome.TerraBlenderClimateSearch;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import terrablender.mixin.MultiNoiseBiomeSourceAccess;
import terrablender.worldgen.IExtendedParameterList;

@Mixin(value = LevelChunkSection.class, priority = 1600)
public abstract class Terrablender$MixinLevelChunkSection$raw_biome_lookup {
    @Unique
    private static final ThreadLocal<long[]> GA$RAW_TARGET = ThreadLocal.withInitial(() -> new long[6]);

    /**
     * @author Sixik
     * @reason Bypass TerraBlender's TargetPoint allocation during section biome filling and
     * feed quantized climate values directly into FlatClimateIndex.
     */
    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.common.biome.mixin.MixinLevelChunkSection$optimize_biome_iteration",
            name = "fillBiomesFromNoise"
    )
    @WrapOperation(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/BiomeResolver;getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;"
            )
    )
    private Holder<Biome> ga$rawTerraBlenderBiome(
            BiomeResolver resolver,
            int x,
            int y,
            int z,
            Climate.Sampler sampler,
            Operation<Holder<Biome>> original
    ) {
        if (resolver instanceof MultiNoiseBiomeSource source
                && ((MultiNoiseBiomeSourceAccess) source).getParameters().left().orElse(null) instanceof IExtendedParameterList<?> parameterList
                && parameterList.isInitialized()) {
            long[] target = GA$RAW_TARGET.get();
            ((ClimateSamplerRaw) (Object) sampler).ga$sampleRaw(x, y, z, target);
            return TerraBlenderClimateSearch.findRaw(parameterList, target, x, y, z);
        }

        return original.call(resolver, x, y, z, sampler);
    }
}

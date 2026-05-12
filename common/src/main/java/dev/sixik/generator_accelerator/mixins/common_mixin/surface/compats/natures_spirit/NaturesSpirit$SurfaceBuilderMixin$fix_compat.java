package dev.sixik.generator_accelerator.mixins.common_mixin.surface.compats.natures_spirit;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sixik.generator_accelerator.common.surface.vector.VectorBlockColumn;
import net.hibiscus.naturespirit.config.NSConfig;
import net.hibiscus.naturespirit.registration.NSBiomes;
import net.hibiscus.naturespirit.registration.NSWorldGen;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SurfaceSystem.class, priority = 1500)
public class NaturesSpirit$SurfaceBuilderMixin$fix_compat {

    @Shadow
    @Final
    private BlockState defaultBlock;
    @Unique
    private NormalNoise sugiPillarNoise;
    @Unique
    private NormalNoise sugiPillarRoofNoise;
    @Unique
    private NormalNoise sugiSurfaceNoise;
    @Unique
    private NormalNoise stratifiedDesertPillarNoise;
    @Unique
    private NormalNoise stratifiedDesertPillarRoofNoise;
    @Unique
    private NormalNoise stratifiedDesertSurfaceNoise;

    @Inject(
            method = {"<init>"},
            at = {@At("TAIL")}
    )
    private void injectNoise(RandomState noiseConfig, BlockState defaultState, int seaLevel, PositionalRandomFactory randomDeriver, CallbackInfo ci) {
        this.sugiPillarNoise = noiseConfig.getOrCreateNoise(NSWorldGen.SUGI_PILLAR);
        this.sugiPillarRoofNoise = noiseConfig.getOrCreateNoise(NSWorldGen.SUGI_PILLAR_ROOF);
        this.sugiSurfaceNoise = noiseConfig.getOrCreateNoise(NSWorldGen.SUGI_SURFACE);
        this.stratifiedDesertPillarNoise = noiseConfig.getOrCreateNoise(NSWorldGen.STRATIFIED_DESERT_PILLAR);
        this.stratifiedDesertPillarRoofNoise = noiseConfig.getOrCreateNoise(NSWorldGen.STRATIFIED_DESERT_PILLAR_ROOF);
        this.stratifiedDesertSurfaceNoise = noiseConfig.getOrCreateNoise(NSWorldGen.STRATIFIED_DESERT_SURFACE);
    }


    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.mixins.common_mixin.surface.SurfaceSystem$new_build_surface",
            name = "buildSurface"
    )
    @Inject(method = "@MixinSquared:Handler", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/resources/ResourceKey;)Z", ordinal = 0))
    private void injectPillars(RandomState noiseConfig, BiomeManager biomeAccess, Registry<Biome> biomeRegistry, boolean useLegacyRandom, WorldGenerationContext heightContext, ChunkAccess chunk, NoiseChunk chunkNoiseSampler, SurfaceRules.RuleSource materialRule, CallbackInfo ci, @Local Holder<Biome> registryEntry, @Local(ordinal = 2) int k, @Local(ordinal = 3) int l, @Local(ordinal = 4) int m, @Local(ordinal = 5) int n, @Local VectorBlockColumn blockColumn) {
        if (NSConfig.sugiAndStratifiedPillars) {
            int o = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, k, l) + 1;
            if (registryEntry.is(NSBiomes.SUGI_FOREST) || registryEntry.is(NSBiomes.BLOOMING_SUGI_FOREST)) {
                this.placeSugiPillar(blockColumn, m, n, o, chunk);
            }

            if (registryEntry.is(NSBiomes.STRATIFIED_DESERT) || registryEntry.is(NSBiomes.LIVELY_DUNES) || registryEntry.is(NSBiomes.BLOOMING_DUNES)) {
                this.placeStratifiedDesertPillar(blockColumn, m, n, o, chunk);
            }
        }

    }

    @Unique
    private void placeSugiPillar(BlockColumn column, int x, int z, int surfaceY, LevelHeightAccessor chunk) {
        double e = Math.min(Math.abs(this.sugiSurfaceNoise.getValue((double)x, (double)0.0F, (double)z) * (double)8.5F), this.sugiPillarRoofNoise.getValue((double)x * 0.2, (double)0.0F, (double)z * 0.2) * (double)12.0F);
        if (e > (double)-10.0F) {
            double h = Math.abs(this.sugiPillarNoise.getValue((double)x * 0.9, (double)0.0F, (double)z * 0.8) * 2.05);
            double i = (double)32.0F + Math.min(e * e * (double)6.75F, Math.ceil(h * (double)30.0F) + (double)48.0F);
            int j = Mth.floor(i);
            if (surfaceY <= j) {
                for(int k = j; k >= chunk.getMinBuildHeight(); --k) {
                    BlockState blockState = column.getBlock(k);
                    if (blockState.is(this.defaultBlock.getBlock())) {
                        break;
                    }
                }

                for(int var15 = j; var15 >= chunk.getMinBuildHeight() && (column.getBlock(var15).isAir() || column.getBlock(var15).is(Blocks.WATER)); --var15) {
                    column.setBlock(var15, this.defaultBlock);
                }
            }
        }

    }

    @Unique
    private void placeStratifiedDesertPillar(BlockColumn column, int x, int z, int surfaceY, LevelHeightAccessor chunk) {
        double e = Math.min(Math.abs(this.stratifiedDesertSurfaceNoise.getValue((double)x, (double)0.0F, (double)z) * (double)8.5F), this.stratifiedDesertPillarNoise.getValue((double)x * 0.2, (double)0.0F, (double)z * 0.2) * (double)14.0F);
        if (!(e <= (double)0.0F)) {
            double h = Math.abs(this.stratifiedDesertPillarRoofNoise.getValue((double)x * (double)0.75F, (double)0.0F, (double)z * (double)0.75F) * (double)2.25F);
            double i = (double)54.0F + Math.min(e * e * (double)3.5F, Math.ceil(h * (double)30.0F) + (double)38.0F);
            int j = Mth.floor(i);
            if (surfaceY <= j) {
                for(int k = j; k >= chunk.getMinBuildHeight(); --k) {
                    BlockState blockState = column.getBlock(k);
                    if (blockState.is(this.defaultBlock.getBlock())) {
                        break;
                    }
                }

                for(int var15 = j; var15 >= chunk.getMinBuildHeight() && (column.getBlock(var15).isAir() || column.getBlock(var15).is(Blocks.WATER)); --var15) {
                    column.setBlock(var15, this.defaultBlock);
                }
            }
        }

    }
}

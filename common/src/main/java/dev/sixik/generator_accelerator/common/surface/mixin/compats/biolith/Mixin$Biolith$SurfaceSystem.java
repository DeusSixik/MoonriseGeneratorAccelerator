package dev.sixik.generator_accelerator.common.surface.mixin.compats.biolith;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.sugar.Local;
import com.terraformersmc.biolith.api.surface.BiolithSurfaceBuilder;
import com.terraformersmc.biolith.impl.surface.SurfaceBuilderCollector;
import dev.sixik.generator_accelerator.common.surface.SurfaceGenerationState;
import dev.sixik.generator_accelerator.common.surface.vector.VectorBlockColumn;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(value = SurfaceSystem.class, priority = 1500)
public abstract class Mixin$Biolith$SurfaceSystem {

    @Shadow @Final
    public PositionalRandomFactory noiseRandom;
    @Shadow @Final private int seaLevel;

    @Unique
    private static BiolithSurfaceBuilder[] BIOLITH_BUILDERS_CACHE = null;

    @Unique
    private static final ThreadLocal<SurfaceGenerationState> STATE = ThreadLocal.withInitial(SurfaceGenerationState::new);

    @Unique
    private BiolithSurfaceBuilder[] bts$getBuildersFast() {
        Set<BiolithSurfaceBuilder> set = SurfaceBuilderCollector.getBuilders();
        if (BIOLITH_BUILDERS_CACHE == null || BIOLITH_BUILDERS_CACHE.length != set.size()) {
            BIOLITH_BUILDERS_CACHE = set.toArray(new BiolithSurfaceBuilder[0]);
        }
        return BIOLITH_BUILDERS_CACHE;
    }

    /**
     * ФАЗА 1: Ранняя генерация (generate).
     * Внедряемся в первый цикл, ДО применения векторных правил поверхности.
     */
    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.common.surface.mixin.SurfaceSystem$new_build_surface",
            name = "buildSurface"
    )
    @Inject(
            method = {"@MixinSquared:Handler"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/resources/ResourceKey;)Z",
                    ordinal = 0
            )
    )
    private void biolith_compat$injectEarlyGenerate(
            RandomState pRandomState, BiomeManager pBiomeManager, Registry<Biome> unused,
            boolean pUseLegacyRandomSource, WorldGenerationContext pContext, ChunkAccess pChunk,
            NoiseChunk pNoiseChunk, SurfaceRules.RuleSource ruleSource, CallbackInfo ci,

            @Local(ordinal = 4) int globalX,
            @Local(ordinal = 5) int globalZ,
            @Local(ordinal = 6) int surfaceY,
            @Local(type = Holder.class) Holder<Biome> biomeHolder,
            @Local(type = VectorBlockColumn.class) VectorBlockColumn fastColumn,
            @Local(type = BlockPos.MutableBlockPos.class, ordinal = 0) BlockPos.MutableBlockPos columnPos
    ) {
        BiolithSurfaceBuilder[] builders = bts$getBuildersFast();
        if (builders.length == 0) return;

        columnPos.setX(globalX).setZ(globalZ);
        RandomSource random = this.noiseRandom.at(globalX, surfaceY, globalZ);
        Biome biome = biomeHolder.value();

        for (int i = 0; i < builders.length; i++) {
            BiolithSurfaceBuilder builder = builders[i];
            if (builder.filterBiome(biomeHolder)) {
                builder.generate(
                        pBiomeManager, fastColumn, random, pChunk, biome,
                        globalX, globalZ, surfaceY, this.seaLevel
                );
            }
        }
    }

    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.common.surface.mixin.SurfaceSystem$new_build_surface",
            name = "buildSurface"
    )
    @Inject(
            method = {"@MixinSquared:Handler"},
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/sixik/generator_accelerator/common/surface/vector/VectorChunkContext;buildDepthMap(Lnet/minecraft/world/level/chunk/ChunkAccess;)V"
            )
    )
    private void biolith_compat$captureForLate(
            RandomState pRandomState, BiomeManager pBiomeManager, Registry<Biome> unused,
            boolean pUseLegacyRandomSource, WorldGenerationContext pContext, ChunkAccess pChunk,
            NoiseChunk pNoiseChunk, SurfaceRules.RuleSource ruleSource, CallbackInfo ci,

            @Local(type = VectorChunkContext.class) VectorChunkContext ctx,
            @Local(type = Holder[].class) Holder<Biome>[] surfaceBiomes,
            @Local(type = VectorBlockColumn.class) VectorBlockColumn fastColumn,
            @Local(type = BlockPos.MutableBlockPos.class, ordinal = 0) BlockPos.MutableBlockPos columnPos,
            @Local(ordinal = 0) boolean hasFrozenOcean
    ) {
        SurfaceGenerationState state = STATE.get();
        state.ctx = ctx;
        state.surfaceBiomes = surfaceBiomes;
        state.fastColumn = fastColumn;
        state.columnPos = columnPos;
        state.hasFrozenOcean = hasFrozenOcean;
    }

    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.common.surface.mixin.SurfaceSystem$new_build_surface",
            name = "buildSurface"
    )
    @Inject(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/resources/ResourceKey;)Z",
                    ordinal = 1
            )
    )
    private void biolith_compat$injectLateInsideFrozenOceanLoop(
            RandomState pRandomState, BiomeManager pBiomeManager, Registry<Biome> unused,
            boolean pUseLegacyRandomSource, WorldGenerationContext pContext, ChunkAccess pChunk,
            NoiseChunk pNoiseChunk, SurfaceRules.RuleSource ruleSource, CallbackInfo ci,

            @Local(ordinal = 4) int idx,
            @Local(ordinal = 5) int globalX,
            @Local(ordinal = 6) int globalZ,
            @Local(type = Holder.class) Holder<Biome> biomeHolder
    ) {
        SurfaceGenerationState state = STATE.get();
        if (state.ctx == null) return;

        BiolithSurfaceBuilder[] builders = bts$getBuildersFast();
        if (builders.length == 0) return;

        Biome biome = biomeHolder.value();
        int surfaceMinY = state.ctx.minSurfaceLevels[idx];
        RandomSource random = this.noiseRandom.at(globalX, surfaceMinY, globalZ);

        state.columnPos.setX(globalX).setZ(globalZ);

        for (int i = 0; i < builders.length; i++) {
            BiolithSurfaceBuilder builder = builders[i];
            if (builder.filterBiome(biomeHolder)) {
                builder.generateLate(
                        pBiomeManager, state.fastColumn, random, pChunk, biome,
                        globalX, globalZ, surfaceMinY, this.seaLevel, surfaceMinY
                );
            }
        }
    }

    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.common.surface.mixin.SurfaceSystem$new_build_surface",
            name = "buildSurface"
    )
    @Inject(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/sixik/generator_accelerator/api/mixin/InjectHelper;inject()V"
            )
    )
    private void biolith_compat$injectLateIfNoFrozenOcean(
            RandomState pRandomState, BiomeManager pBiomeManager, Registry<Biome> unused,
            boolean pUseLegacyRandomSource, WorldGenerationContext pContext, ChunkAccess pChunk,
            NoiseChunk pNoiseChunk, SurfaceRules.RuleSource ruleSource, CallbackInfo ci
    ) {
        SurfaceGenerationState state = STATE.get();
        if (state.ctx == null) return;

        if (!state.hasFrozenOcean) {
            BiolithSurfaceBuilder[] builders = bts$getBuildersFast();

            if (builders.length > 0) {
                int minBlockX = pChunk.getPos().getMinBlockX();
                int minBlockZ = pChunk.getPos().getMinBlockZ();

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int idx = x | (z << 4);
                        Holder<Biome> biomeHolder = state.surfaceBiomes[idx];
                        Biome biome = biomeHolder.value();

                        int globalX = minBlockX + x;
                        int globalZ = minBlockZ + z;

                        int surfaceMinY = state.ctx.minSurfaceLevels[idx];
                        RandomSource random = this.noiseRandom.at(globalX, surfaceMinY, globalZ);

                        state.columnPos.setX(globalX).setZ(globalZ);

                        for (int i = 0; i < builders.length; i++) {
                            BiolithSurfaceBuilder builder = builders[i];
                            if (builder.filterBiome(biomeHolder)) {
                                builder.generateLate(
                                        pBiomeManager, state.fastColumn, random, pChunk, biome,
                                        globalX, globalZ, surfaceMinY, this.seaLevel, surfaceMinY
                                );
                            }
                        }
                    }
                }
            }
        }

        state.ctx = null;
        state.surfaceBiomes = null;
        state.fastColumn = null;
        state.columnPos = null;
    }
}

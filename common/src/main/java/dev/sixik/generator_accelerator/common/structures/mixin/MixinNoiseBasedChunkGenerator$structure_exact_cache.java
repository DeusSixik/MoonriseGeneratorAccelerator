package dev.sixik.generator_accelerator.common.structures.mixin;

import dev.sixik.generator_accelerator.common.structures.StructureNoiseColumnCache;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NoiseBasedChunkGenerator.class, priority = 900)
public abstract class MixinNoiseBasedChunkGenerator$structure_exact_cache {

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void ga$structureBaseHeightCacheGet(
            int x,
            int z,
            Heightmap.Types type,
            LevelHeightAccessor levelHeightAccessor,
            RandomState randomState,
            CallbackInfoReturnable<Integer> cir
    ) {
        StructureNoiseColumnCache cache = StructureNoiseColumnCache.current();
        if (cache == null) {
            return;
        }

        NoiseGeneratorSettings generatorSettings = this.settings.value();
        NoiseSettings noiseSettings = generatorSettings.noiseSettings().clampToHeightAccessor(levelHeightAccessor);
        int cached = cache.getBaseHeight(
                (NoiseBasedChunkGenerator) (Object) this,
                x,
                z,
                type,
                levelHeightAccessor,
                randomState,
                generatorSettings,
                noiseSettings
        );
        if (cached != StructureNoiseColumnCache.MISS) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getBaseHeight", at = @At("RETURN"))
    private void ga$structureBaseHeightCachePut(
            int x,
            int z,
            Heightmap.Types type,
            LevelHeightAccessor levelHeightAccessor,
            RandomState randomState,
            CallbackInfoReturnable<Integer> cir
    ) {
        StructureNoiseColumnCache cache = StructureNoiseColumnCache.current();
        if (cache == null) {
            return;
        }

        NoiseGeneratorSettings generatorSettings = this.settings.value();
        NoiseSettings noiseSettings = generatorSettings.noiseSettings().clampToHeightAccessor(levelHeightAccessor);
        cache.putBaseHeight(
                (NoiseBasedChunkGenerator) (Object) this,
                x,
                z,
                type,
                levelHeightAccessor,
                randomState,
                generatorSettings,
                noiseSettings,
                cir.getReturnValue()
        );
    }

    @Inject(method = "getBaseColumn", at = @At("HEAD"), cancellable = true)
    private void ga$structureBaseColumnCacheGet(
            int x,
            int z,
            LevelHeightAccessor levelHeightAccessor,
            RandomState randomState,
            CallbackInfoReturnable<NoiseColumn> cir
    ) {
        StructureNoiseColumnCache cache = StructureNoiseColumnCache.current();
        if (cache == null) {
            return;
        }

        NoiseGeneratorSettings generatorSettings = this.settings.value();
        NoiseSettings noiseSettings = generatorSettings.noiseSettings().clampToHeightAccessor(levelHeightAccessor);
        NoiseColumn cached = cache.getBaseColumn(
                (NoiseBasedChunkGenerator) (Object) this,
                x,
                z,
                levelHeightAccessor,
                randomState,
                generatorSettings,
                noiseSettings
        );
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getBaseColumn", at = @At("RETURN"))
    private void ga$structureBaseColumnCachePut(
            int x,
            int z,
            LevelHeightAccessor levelHeightAccessor,
            RandomState randomState,
            CallbackInfoReturnable<NoiseColumn> cir
    ) {
        StructureNoiseColumnCache cache = StructureNoiseColumnCache.current();
        if (cache == null) {
            return;
        }

        NoiseColumn column = cir.getReturnValue();
        if (column == null) {
            return;
        }

        NoiseGeneratorSettings generatorSettings = this.settings.value();
        NoiseSettings noiseSettings = generatorSettings.noiseSettings().clampToHeightAccessor(levelHeightAccessor);
        if (cache.hasBaseColumn(
                (NoiseBasedChunkGenerator) (Object) this,
                x,
                z,
                levelHeightAccessor,
                randomState,
                generatorSettings,
                noiseSettings
        )) {
            return;
        }

        cache.putBaseColumn(
                (NoiseBasedChunkGenerator) (Object) this,
                x,
                z,
                levelHeightAccessor,
                randomState,
                generatorSettings,
                noiseSettings,
                column
        );
    }
}

package dev.sixik.generator_accelerator.common.flat_block_structure.mixin;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

@Mixin(value = NoiseBasedChunkGenerator.class, priority = 9999)
public abstract class MixinNoiseBasedChunkGenerator extends ChunkGenerator {

    private MixinNoiseBasedChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    private MixinNoiseBasedChunkGenerator(BiomeSource biomeSource, Function<Holder<Biome>, BiomeGenerationSettings> function) {
        super(biomeSource, function);
    }

    @Inject(method = "fillFromNoise", at = @At("HEAD"))
    public void bts$fillFromNoise(Executor executor, Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess pChunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        LevelChunkSection[] sections = pChunk.getSections();
        for (int i = 0; i < sections.length; i++) {


            final LevelChunkSection section = sections[i];
            if (section == null) continue;
            LevelChunkSection$FlatBlockArray.get(section).bts$unpackForGeneration();
        }
    }

    @Inject(method = "applyCarvers", at = @At("TAIL"))
    public void bts$applyCarvers(WorldGenRegion worldGenRegion, long l, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess pChunk, GenerationStep.Carving carving, CallbackInfo ci) {
        LevelChunkSection[] sections = pChunk.getSections();
        for (int i = 0; i < sections.length; i++) {


            final LevelChunkSection section = sections[i];
            if (section == null) continue;
            LevelChunkSection$FlatBlockArray.get(section).bts$packAndFreeze();
        }
    }
}

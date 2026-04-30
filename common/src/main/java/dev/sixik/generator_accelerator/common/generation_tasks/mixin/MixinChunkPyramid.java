package dev.sixik.generator_accelerator.common.generation_tasks.mixin;

import dev.sixik.generator_accelerator.common.generation_tasks.GAChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ChunkPyramid.class)
public class MixinChunkPyramid {

    @Shadow
    @Final
    @Mutable
    public static ChunkPyramid GENERATION_PYRAMID = new ChunkPyramid.Builder()
            .step(ChunkStatus.EMPTY, p_342975_ -> p_342975_)
            .step(ChunkStatus.STRUCTURE_STARTS, p_342544_ -> p_342544_.setTask(ChunkStatusTasks::generateStructureStarts))
            .step(ChunkStatus.STRUCTURE_REFERENCES, p_345155_ -> p_345155_.addRequirement(ChunkStatus.STRUCTURE_STARTS, 8).setTask(ChunkStatusTasks::generateStructureReferences))
            .step(ChunkStatus.BIOMES, p_342684_ -> p_342684_.addRequirement(ChunkStatus.STRUCTURE_STARTS, 8).setTask(ChunkStatusTasks::generateBiomes))
            .step(
                    GAChunkStatus.TERRAIN,
                    builder -> builder
                            .addRequirement(ChunkStatus.STRUCTURE_STARTS, 8)
                            .addRequirement(ChunkStatus.BIOMES, 1)
                            .blockStateWriteRadius(0)
                            .setTask(GAChunkStatus::generateTerrain)
            )
            .step(
                    ChunkStatus.FEATURES,
                    p_345027_ -> p_345027_
                            .addRequirement(ChunkStatus.STRUCTURE_STARTS, 8)
                            .addRequirement(GAChunkStatus.TERRAIN, 1)
                            .blockStateWriteRadius(1)
                            .setTask(ChunkStatusTasks::generateFeatures)
            )
            .step(ChunkStatus.INITIALIZE_LIGHT, p_342175_ -> p_342175_.setTask(ChunkStatusTasks::initializeLight))
            .step(ChunkStatus.LIGHT, p_342930_ -> p_342930_.addRequirement(ChunkStatus.INITIALIZE_LIGHT, 1).setTask(ChunkStatusTasks::light))
            .step(ChunkStatus.SPAWN, p_345462_ -> p_345462_.addRequirement(ChunkStatus.BIOMES, 1).setTask(ChunkStatusTasks::generateSpawn))
            .step(ChunkStatus.FULL, p_343894_ -> p_343894_.setTask(ChunkStatusTasks::full))
            .build();

    @Shadow
    @Final
    @Mutable
    public static ChunkPyramid LOADING_PYRAMID = new ChunkPyramid.Builder()
            .step(ChunkStatus.EMPTY, p_342764_ -> p_342764_)
            .step(ChunkStatus.STRUCTURE_STARTS, p_345203_ -> p_345203_.setTask(ChunkStatusTasks::loadStructureStarts))
            .step(ChunkStatus.STRUCTURE_REFERENCES, p_344362_ -> p_344362_)
            .step(ChunkStatus.BIOMES, p_344572_ -> p_344572_)
            .step(GAChunkStatus.TERRAIN, builder -> builder)
            .step(ChunkStatus.FEATURES, p_343425_ -> p_343425_)
            .step(ChunkStatus.INITIALIZE_LIGHT, p_343066_ -> p_343066_.setTask(ChunkStatusTasks::initializeLight))
            .step(ChunkStatus.LIGHT, p_342741_ -> p_342741_.addRequirement(ChunkStatus.INITIALIZE_LIGHT, 1).setTask(ChunkStatusTasks::light))
            .step(ChunkStatus.SPAWN, p_342632_ -> p_342632_)
            .step(ChunkStatus.FULL, p_343704_ -> p_343704_.setTask(ChunkStatusTasks::full))
            .build();
}

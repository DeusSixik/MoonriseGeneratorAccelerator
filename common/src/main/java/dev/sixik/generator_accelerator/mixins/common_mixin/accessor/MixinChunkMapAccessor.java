package dev.sixik.generator_accelerator.mixins.common_mixin.accessor;

import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ChunkMap.class)
public interface MixinChunkMapAccessor {
    @Accessor("pendingGenerationTasks")
    List<ChunkGenerationTask> ga$getPendingGenerationTasks();

    @Accessor("worldGenContext")
    WorldGenContext ga$getWorldGenContext();
}

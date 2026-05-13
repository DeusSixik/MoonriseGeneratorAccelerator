package dev.sixik.generator_accelerator.mixins.common_mixin.accessor;

import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkGenerationTask.class)
public interface MixinChunkGenerationTaskAccessor {
    @Accessor("pos")
    ChunkPos ga$getPos();

    @Accessor("cache")
    StaticCache2D<GenerationChunkHolder> ga$getCache();

    @Accessor("markedForCancellation")
    boolean ga$isMarkedForCancellation();

    @Invoker("releaseClaim")
    void ga$releaseClaim();
}

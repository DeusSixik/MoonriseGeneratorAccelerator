package dev.sixik.generator_accelerator.common.chunks.mixin.compats.modernfix;

import net.minecraft.server.level.GenerationChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(GenerationChunkHolder.class)
public interface ModernFix$GenerationChunkHolderAccessor {
    @Accessor("futures")
    @SuppressWarnings("rawtypes")
    AtomicReferenceArray ga$getFutures();

    @Accessor("startedWork")
    @SuppressWarnings("rawtypes")
    AtomicReference ga$getStartedWork();
}

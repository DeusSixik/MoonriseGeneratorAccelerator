package dev.sixik.moonrisegeneratoraccelerator.profiler.mixin.moonrise;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sixik.moonrisegeneratoraccelerator.profiler.BtsProfilerUtils;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(NewChunkHolder.class)
public class MixinNewChunkHolder$profiler {

    private static final String SAVE_CHUNK_ZOE = "Save Chunk";

    @WrapMethod(method = "saveChunk")
    public boolean bts$saveChunk$profiler(ChunkAccess chunk, boolean unloading, Operation<Boolean> original) {
        BtsProfilerUtils.startZone(SAVE_CHUNK_ZOE);
        try {
            return original.call(chunk, unloading);
        } finally {
            BtsProfilerUtils.endZone(SAVE_CHUNK_ZOE);
        }
    }
}

package dev.sixik.generator_accelerator.common.blender.mixin.compats.c2me;

import com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.ProtoChunkExtension;
import dev.sixik.generator_accelerator.common.blender.NewBlender;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({NewBlender.class})
public class c2me$BlenderMixin {
    @Redirect(
            method = {"ofNew(Lnet/minecraft/server/level/WorldGenRegion;)Lnet/minecraft/world/level/levelgen/blending/Blender;"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/WorldGenRegion;isOldChunkAround(Lnet/minecraft/world/level/ChunkPos;I)Z"
            )
    )
    private static boolean redirectNeedsBlending(WorldGenRegion instance, ChunkPos chunkPos, int checkRadius) {
        return ((ProtoChunkExtension)instance.getChunk(chunkPos.x, chunkPos.z)).getNeedBlending();
    }
}

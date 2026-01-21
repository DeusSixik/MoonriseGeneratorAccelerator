package dev.sixik.moonrisegeneratoraccelerator.common.mixin;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseBasedChunkGeneratorOptimizeDensity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

//    @Inject(method = "loadLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;createLevels(Lnet/minecraft/server/level/progress/ChunkProgressListener;)V", shift = At.Shift.AFTER))
//    public void bts$loadLevel(CallbackInfo ci) {
//        for (ServerLevel allLevel : getAllLevels()) {
//
//            if(allLevel.getChunkSource().getGenerator() instanceof NoiseBasedChunkGeneratorOptimizeDensity chunkGenerator)
//                chunkGenerator.bts$applyDensityOptimize();
//
//        }
//    }
}

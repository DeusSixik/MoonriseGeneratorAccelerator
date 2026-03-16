package dev.sixik.generator_accelerator.common.executors.mixin;

import dev.sixik.generator_accelerator.common.executors.ChunkGenerationExecutor;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {

    @Inject(method = "runServer", at = @At("HEAD"))
    private void bts$init(CallbackInfo ci) {
        ChunkGenerationExecutor executor = ChunkGenerationExecutor.executorInstance;
        if(executor != null && !executor.isShutdown()) {
            executor.shutdown();
        } else {
            ChunkGenerationExecutor.executorInstance = new ChunkGenerationExecutor();
        }
    }

    @Inject(method = "onServerExit", at = @At("HEAD"))
    private void bts$onServerExit(CallbackInfo ci) {
        if(ChunkGenerationExecutor.executorInstance != null && !ChunkGenerationExecutor.executorInstance.isShutdown())
            ChunkGenerationExecutor.executorInstance.shutdown();
    }
}

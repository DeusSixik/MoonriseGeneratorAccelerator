package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.worldgen.parallel.GACustomChunkGraphScheduler;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {

    @Inject(method = "runServer", at = @At("HEAD"))
    public void ga$resetCustomGraphShutdown(CallbackInfo ci) {
        GACustomChunkGraphScheduler.resetShutdownRequest();
    }

    @Inject(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;buildServerStatus()Lnet/minecraft/network/protocol/status/ServerStatus;", shift = At.Shift.AFTER))
    public void bts$runServer(CallbackInfo ci) {
        FastBlockStateCache.init(GeneratorAccelerator.platform);
        FastBlockStateCache.reloadTags();
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    public void ga$beginCustomGraphShutdown(CallbackInfo ci) {
        GACustomChunkGraphScheduler.beginShutdown();
    }
}

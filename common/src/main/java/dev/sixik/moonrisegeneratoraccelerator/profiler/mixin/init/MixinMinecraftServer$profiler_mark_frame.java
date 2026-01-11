package dev.sixik.moonrisegeneratoraccelerator.profiler.mixin.init;

import dev.sdm.profiler.TracyProfiler;
import dev.sixik.moonrisegeneratoraccelerator.profiler.BtsProfilerUtils;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MixinMinecraftServer$profiler_mark_frame {

    @Inject(method = "startMetricsRecordingTick", at = @At("HEAD"))
    public void bts$startMetricsRecordingTick$inject_tick_profiler(CallbackInfo ci) {
        if(BtsProfilerUtils.isConnected())
            TracyProfiler.markFrame();
    }
}

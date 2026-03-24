package dev.sixik.generator_accelerator.common.noise_native.mixin;

import dev.sixik.generator_accelerator.math.c3.NativeRandom;
import net.minecraft.world.level.levelgen.RandomSupport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicLong;

@Mixin(RandomSupport.class)
public class MixinRandomSupport {

    @Shadow
    @Final
    private static AtomicLong SEED_UNIQUIFIER;

    @Inject(method = "generateUniqueSeed", at = @At("RETURN"))
    private static void bts$generateUniqueSeed(CallbackInfoReturnable<Long> cir) {
        NativeRandom.setGlobalXoroshiroSeed(SEED_UNIQUIFIER.get());
    }
}

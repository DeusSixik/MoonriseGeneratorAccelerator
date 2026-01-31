package dev.sixik.moonrisegeneratoraccelerator.common.mixin.tests;

import com.mojang.datafixers.util.Pair;
import dev.sixik.density_interpreter.DensityVM;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PerlinNoise.class)
public class PerlinNoiseMixinTest {

//    @Unique
//    private long bts$ptr;
//
//    @Inject(method = "<init>", at = @At("RETURN"))
//    public void bts$init(RandomSource randomSource, Pair pair, boolean bl, CallbackInfo ci) {
//        this.bts$ptr = DensityVM.createPerlinNoise((PerlinNoise)(Object) this);
//    }
}

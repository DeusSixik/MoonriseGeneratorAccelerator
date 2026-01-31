package dev.sixik.moonrisegeneratoraccelerator.common.mixin.tests;

import dev.sixik.density_interpreter.DensityVM;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ImprovedNoise.class)
public class ImprovedNoiseMixinTest {

//    @Shadow
//    @Final
//    public double xo;
//
//    @Shadow
//    @Final
//    public double yo;
//
//    @Shadow
//    @Final
//    public double zo;
//
//    @Shadow
//    @Final
//    public byte[] p;
//
//    @Unique
//    private long ptr;
//
//    @Inject(method = "<init>", at = @At("RETURN"))
//    public void bts$init(RandomSource randomSource, CallbackInfo ci) {
//        ptr = DensityVM.createNativeImprovedNoise(xo, yo, zo, p);
//    }
//
//    /**
//     * @author
//     * @reason
//     */
//    @Overwrite
//    public double noise(double d, double e, double f, double g, double h) {
//        return DensityVM.noise(ptr, d, e, f, g, h);
//    }
}

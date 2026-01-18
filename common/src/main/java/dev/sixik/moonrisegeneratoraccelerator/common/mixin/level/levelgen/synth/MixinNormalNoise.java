package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.synth;

import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NormalNoise.class)
public abstract class MixinNormalNoise {

    @Shadow
    @Final
    private PerlinNoise first;

    @Shadow
    @Final
    private PerlinNoise second;

    @Shadow
    @Final
    @Mutable
    private double valueFactor;

//    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/doubles/DoubleList;iterator()Lit/unimi/dsi/fastutil/doubles/DoubleListIterator;"), cancellable = true)
//    public void bts$init(RandomSource randomSource, NormalNoise.NoiseParameters noiseParameters, boolean bl, CallbackInfo ci, @Local(name = "doubleList") DoubleList amplitudes, @Local(name = "j") int maxIndex, @Local(name = "k") int minIndex) {
//        ci.cancel();
//
//        for (int i = 0; i < amplitudes.size(); i++) {
//            final double d = amplitudes.getDouble(i);
//            if (d == 0.0) continue;
//
//            /*
//                 nextIndex() in the iterator is essentially firstOctave + i
//                 But in the original, the logic of nextIndex depended on the implementation of the DoubleList iterator
//                 Usually, MC amplitudes are just a list, so the index i correlates with the offset
//             */
//            minIndex = Math.min(minIndex, i);
//            maxIndex = Math.max(maxIndex, i);
//        }
//
//        final double value = 0.16666666666666666 / (0.1 * (1.0 + 1.0 / (double)(maxIndex - minIndex + 1)));
//        this.valueFactor = value;
//        this.maxValue = (this.first.maxValue() + this.second.maxValue()) * value;
//    }

    /**
     * @author Sixik
     * @reason Micro Optimization
     */
    @Overwrite
    public double getValue(double x, double y, double z) {
        final double v1 = first.getValue(x, y, z);
        final double v2 = second.getValue(
                x * 1.0181268882175227,
                y * 1.0181268882175227,
                z * 1.0181268882175227
        );
        return (v1 + v2) * valueFactor;
    }
}

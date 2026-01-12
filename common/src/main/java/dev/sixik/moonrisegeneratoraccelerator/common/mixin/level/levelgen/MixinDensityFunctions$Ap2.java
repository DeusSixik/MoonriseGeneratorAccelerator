package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Ap2")
public class MixinDensityFunctions$Ap2 {

    @Shadow
    @Final private DensityFunctions.TwoArgumentSimpleFunction.Type type;
    @Shadow @Final private DensityFunction argument1;
    @Shadow @Final
    private DensityFunction argument2;

    @Unique
    private double bts$arg2Min;
    @Unique private double bts$arg2Max;

    @Unique
    private int bts$ordinal;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bts$init(
            DensityFunctions.TwoArgumentSimpleFunction.Type type,
            DensityFunction arg1,
            DensityFunction arg2,
            double minValue,
            double maxValue,
            CallbackInfo ci
    ) {
        this.bts$arg2Min = arg2.minValue();
        this.bts$arg2Max = arg2.maxValue();
        this.bts$ordinal = this.type.ordinal();
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext ctx) {
        final double d = this.argument1.compute(ctx);

        return switch (bts$ordinal) {
            case 0 -> d + this.argument2.compute(ctx);
            case 1 -> d == 0.0 ? 0.0 : d * this.argument2.compute(ctx);
            case 2 -> d < this.bts$arg2Min ? d : Math.min(d, this.argument2.compute(ctx));
            case 3 -> d > this.bts$arg2Max ? d : Math.max(d, this.argument2.compute(ctx));
            default -> throw new IllegalStateException("Unexpected value: " + bts$ordinal);
        };
    }
}

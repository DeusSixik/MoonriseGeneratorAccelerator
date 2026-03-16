package dev.sixik.generator_accelerator.mixins.density_mixin;

import com.mojang.serialization.MapCodec;
import dev.sixik.generator_accelerator.common.levelgen.density_custom.DensityCustomFunction;
import dev.sixik.generator_accelerator.common.levelgen.density_custom.basic.*;
import dev.sixik.generator_accelerator.common.levelgen.density_custom.misc.*;
import dev.sixik.generator_accelerator.common.levelgen.density_custom.noise.*;
import dev.sixik.generator_accelerator.common.levelgen.density_custom.pure.*;
import net.minecraft.core.Registry;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DensityFunctions.class)
public class MixinDensityFunctions$redirect_basic_functions_to_fast_versions {

    @Redirect(method = "bootstrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunctions$TwoArgumentSimpleFunction$Type;values()[Lnet/minecraft/world/level/levelgen/DensityFunctions$TwoArgumentSimpleFunction$Type;"))
    private static DensityFunctions.TwoArgumentSimpleFunction.Type[] bts$redirect$math_type() {
        return new DensityFunctions.TwoArgumentSimpleFunction.Type[0];
    }

    @Redirect(method = "bootstrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunctions$Mapped$Type;values()[Lnet/minecraft/world/level/levelgen/DensityFunctions$Mapped$Type;"))
    private static DensityFunctions.Mapped.Type[] bts$redirect$pure_type() {
        return new DensityFunctions.Mapped.Type[0];
    }

    @Redirect(method = "bootstrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunctions;register(Lnet/minecraft/core/Registry;Ljava/lang/String;Lnet/minecraft/util/KeyDispatchDataCodec;)Lcom/mojang/serialization/MapCodec;"
            , ordinal = 8))
    private static MapCodec<? extends DensityFunction> bts$shiftedNoise(Registry<MapCodec<? extends DensityFunction>> registry, String string, KeyDispatchDataCodec<? extends DensityFunction> keyDispatchDataCodec) {
        return DensityCustomFunction.register(registry, "shifted_noise", FastShiftedNoiseDensityFunction.CODEC);
    }

    @Redirect(method = "bootstrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunctions;register(Lnet/minecraft/core/Registry;Ljava/lang/String;Lnet/minecraft/util/KeyDispatchDataCodec;)Lcom/mojang/serialization/MapCodec;"
            , ordinal = 9))
    private static MapCodec<? extends DensityFunction> bts$rangeCoise(Registry<MapCodec<? extends DensityFunction>> registry, String string, KeyDispatchDataCodec<? extends DensityFunction> keyDispatchDataCodec) {
        return DensityCustomFunction.register(registry, "range_choice", FastRangeChoice.CODEC);
    }

    @Redirect(method = "bootstrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunctions;register(Lnet/minecraft/core/Registry;Ljava/lang/String;Lnet/minecraft/util/KeyDispatchDataCodec;)Lcom/mojang/serialization/MapCodec;"
            , ordinal = 14))
    private static MapCodec<? extends DensityFunction> bts$redirectClampFunction(Registry<MapCodec<? extends DensityFunction>> registry, String string, KeyDispatchDataCodec<? extends DensityFunction> keyDispatchDataCodec) {
        return DensityCustomFunction.register(registry, "clamp", FastClampDensityFunction.CODEC);
    }

    @Inject(method = "bootstrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunctions$TwoArgumentSimpleFunction$Type;values()[Lnet/minecraft/world/level/levelgen/DensityFunctions$TwoArgumentSimpleFunction$Type;"))
    private static void bts$init$bootstrap(Registry<MapCodec<? extends DensityFunction>> registry, CallbackInfoReturnable<MapCodec<? extends DensityFunction>> cir) {
        DensityCustomFunction.register(registry,  DensityFunctions.TwoArgumentSimpleFunction.Type.ADD.getSerializedName(), FastAddDensityFunction.codec);
        DensityCustomFunction.register(registry,  DensityFunctions.TwoArgumentSimpleFunction.Type.MUL.getSerializedName(), FastMulDensityFunction.codec);
        DensityCustomFunction.register(registry,  DensityFunctions.TwoArgumentSimpleFunction.Type.MAX.getSerializedName(), FastMaxDensityFunction.codec);
        DensityCustomFunction.register(registry,  DensityFunctions.TwoArgumentSimpleFunction.Type.MIN.getSerializedName(), FastMinDensityFunction.codec);

        DensityCustomFunction.register(registry,  DensityFunctions.Mapped.Type.ABS.getSerializedName(), FastAbsDensityFunction.codec);
        DensityCustomFunction.register(registry,  DensityFunctions.Mapped.Type.SQUARE.getSerializedName(), FastSquareDensityFunction.codec);
        DensityCustomFunction.register(registry,  DensityFunctions.Mapped.Type.CUBE.getSerializedName(), FastCubeDensityFunction.codec);
        DensityCustomFunction.register(registry,  DensityFunctions.Mapped.Type.HALF_NEGATIVE.getSerializedName(), FastHalfNegativeDensityFunction.codec);
        DensityCustomFunction.register(registry,  DensityFunctions.Mapped.Type.QUARTER_NEGATIVE.getSerializedName(), FastQuarterNegativeDensityFunction.codec);
        DensityCustomFunction.register(registry,  DensityFunctions.Mapped.Type.SQUEEZE.getSerializedName(), FastSqueezeDensityFunction.codec);
    }
}

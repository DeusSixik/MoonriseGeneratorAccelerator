package dev.sixik.generator_accelerator.mixins.common_mixin;

import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FrontAndTop.class)
public class FrontAndTopCrashFix {

    /**
     * Перехватываем вызов метода. Если top (arg2) == null, подставляем дефолтное
     * перпендикулярное направление, чтобы избежать NPE.
     */
    @Inject(method = "fromFrontAndTop", at = @At("HEAD"), cancellable = true)
    private static void onFromFrontAndTop(Direction front, Direction top, CallbackInfoReturnable<FrontAndTop> cir) {
        if (front == null) {
            cir.setReturnValue(FrontAndTop.NORTH_UP);
            return;
        }

        if (top == null) {
            Direction safeTop = (front.getAxis() == Direction.Axis.Y) ? Direction.NORTH : Direction.UP;
            cir.setReturnValue(FrontAndTop.fromFrontAndTop(front, safeTop));
        }
    }
}
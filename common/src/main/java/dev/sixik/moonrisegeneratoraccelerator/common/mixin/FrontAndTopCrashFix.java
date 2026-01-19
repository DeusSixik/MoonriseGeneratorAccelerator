package dev.sixik.moonrisegeneratoraccelerator.common.mixin;

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
        // 1. CRITICAL: Если front == null, мы не можем вычислить ориентацию.
        // Возвращаем дефолт (NORTH_UP), чтобы избежать краша и лишних вычислений.
        if (front == null) {
            cir.setReturnValue(FrontAndTop.NORTH_UP);
            return;
        }

        // 2. Если front есть, но top == null, вычисляем безопасный верх.
        if (top == null) {
            // Если смотрим вверх/вниз (Y), то верх = NORTH. Иначе верх = UP.
            // Это самая дешевая проверка без аллокаций.
            Direction safeTop = (front.getAxis() == Direction.Axis.Y) ? Direction.NORTH : Direction.UP;

            // Рекурсивный вызов безопасен, так как safeTop гарантированно не null.
            cir.setReturnValue(FrontAndTop.fromFrontAndTop(front, safeTop));
        }
    }
}
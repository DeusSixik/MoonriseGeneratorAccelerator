package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RandomState.class)
public class MixinRandomState$inject_route_optimizer {

    @Mutable
    @Shadow
    @Final
    private NoiseRouter router;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bts$onInit(CallbackInfo ci) {
//        this.router = ((NoiseRouterCustomOptimizer)(Object)this.router).bts$routeOptimize();
    }
}

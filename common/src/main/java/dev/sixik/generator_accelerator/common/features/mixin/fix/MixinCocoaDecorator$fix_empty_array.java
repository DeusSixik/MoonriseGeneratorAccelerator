package dev.sixik.generator_accelerator.common.features.mixin.fix;

import net.minecraft.world.level.levelgen.feature.treedecorators.CocoaDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CocoaDecorator.class)
public abstract class MixinCocoaDecorator$fix_empty_array extends TreeDecorator {

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    public void bts$place(Context context, CallbackInfo ci) {
        if(context.logs().isEmpty())
            ci.cancel();
    }
}

package dev.sixik.generator_accelerator.mixins.common_mixin;

import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Blocks.class)
public class MixinBlocks {

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void bts$init(CallbackInfo ci) {
//        if(GeneratorAccelerator.platform != GeneratorAccelerator.Platform.NEOFORGE)
//            FastBlockStateCache.init(GeneratorAccelerator.Platform.FABRIC);
    }
}

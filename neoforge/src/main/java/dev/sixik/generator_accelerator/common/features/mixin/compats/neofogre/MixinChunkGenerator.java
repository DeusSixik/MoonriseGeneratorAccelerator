package dev.sixik.generator_accelerator.common.features.mixin.compats.neofogre;

import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public class MixinChunkGenerator {

    /**
     * @author Sixik
     * @reason Not used!
     */
    @Inject(method = "refreshFeaturesPerStep", at = @At("HEAD"), cancellable = true)
    public void refreshFeaturesPerStep(CallbackInfo ci) {
        ci.cancel();
    }
}

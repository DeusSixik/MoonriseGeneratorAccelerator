package dev.sixik.generator_accelerator.mixins.common_mixin.accessor;

import net.minecraft.world.level.levelgen.Aquifer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Aquifer.NoiseBasedAquifer.class)
public interface MixinNoiseBasedAquiferAccessor {

    @Accessor("globalFluidPicker")
    Aquifer.FluidPicker ga$getGlobalFluidPicker();
}

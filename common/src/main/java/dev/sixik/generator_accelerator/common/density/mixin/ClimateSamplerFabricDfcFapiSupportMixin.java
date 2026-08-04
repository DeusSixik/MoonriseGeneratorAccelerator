package dev.sixik.generator_accelerator.common.density.mixin;

import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anchor class for the optional Forgified Fabric + Connector code path. Seed propagation
 * is performed in {@link RandomStateMixin}
 * via {@link dev.sixik.generator_accelerator.common.density.compiler.compat.FabricBiomeApiClimateRebind} after
 * {@code compileSampler} replaces the sampler, because it must use the pre-replace instance as
 * the {@link net.fabricmc.fabric.impl.biome.MultiNoiseSamplerHooks} source.
 */
@Mixin(Climate.Sampler.class)
public class ClimateSamplerFabricDfcFapiSupportMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void dfc$fapiSupportMarker(CallbackInfo ci) {
        // Intentionally empty; exists so this target is a documented, explicit Fabric/DFC touchpoint
        // in the same package as the rest of DFC's worldgen mixins.
    }
}

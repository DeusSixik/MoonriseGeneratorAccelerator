package dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import terrablender.worldgen.IExtendedParameterList;

@Mixin(value = NoiseBasedChunkGenerator.class, priority = 1600)
public abstract class Terrablender$MixinNoiseBasedChunkGenerator$reuse_uniqueness {

    /**
     * @author Sixik
     * @reason TerraBlender rebuilds a full uniqueness Area for every biome chunk. The Area is
     * already locked and deterministic, so sharing the cloned source's existing Area avoids
     * large per-chunk array allocation. Restore vanilla TerraBlender behaviour with
     * -Dga.terrablender.recreateUniquenessPerChunk=true if a pack depends on per-chunk caches.
     */
    @TargetHandler(
            mixin = "terrablender.mixin.MixinNoiseBasedChunkGenerator",
            name = "modifyBiomeSource"
    )
    @WrapOperation(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "INVOKE",
                    target = "Lterrablender/worldgen/IExtendedParameterList;recreateUniqueness()V"
            ),
            remap = false
    )
    private void ga$reuseUniquenessArea(IExtendedParameterList<?> parameterList, Operation<Void> original) {
        if (Boolean.getBoolean("ga.terrablender.recreateUniquenessPerChunk")) {
            original.call(parameterList);
        }
    }
}

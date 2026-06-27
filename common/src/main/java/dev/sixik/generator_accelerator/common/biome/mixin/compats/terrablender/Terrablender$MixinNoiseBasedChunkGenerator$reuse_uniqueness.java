package dev.sixik.generator_accelerator.common.biome.mixin.compats.terrablender;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sixik.generator_accelerator.api.mixin.annotation.CompatMixin;
import dev.sixik.generator_accelerator.api.mixin.annotation.MixinOnConfig;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import terrablender.core.TerraBlender;
import terrablender.worldgen.IExtendedParameterList;

@CompatMixin(mod = TerraBlender.class)
@MixinOnConfig(name = "terraBlenderRecreateUniquenessPerChunk",
        comment = """
                TerraBlender rebuilds a full uniqueness Area for every biome chunk.
                The Area is already locked and deterministic, so sharing the cloned source's existing
                Area avoids large per-chunk array allocation. Restore vanilla TerraBlender behaviour with
                If `false` a pack depends on per-chunk caches.
                """)
@Mixin(value = NoiseBasedChunkGenerator.class, priority = 1600)
public abstract class Terrablender$MixinNoiseBasedChunkGenerator$reuse_uniqueness {

    /**
     * @author DenisMasterHerobrine
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
    private void ga$reuseUniquenessArea(IExtendedParameterList<?> parameterList, Operation<Void> original) { }
}

package dev.sixik.generator_accelerator.common.surface.mixin;

import dev.sixik.generator_accelerator.common.surface.SurfaceBiomeCondition;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource")
public abstract class MixinSurfaceRules$BiomeConditionSource implements SurfaceRules.ConditionSource {

    /**
     * @author Sixik
     * @reason Redirect to cached biome getter
     */
    @Overwrite
    public SurfaceRules.Condition apply(final SurfaceRules.Context context) {
        return new SurfaceBiomeCondition(context, (SurfaceRules.BiomeConditionSource) (Object) this);
    }
}

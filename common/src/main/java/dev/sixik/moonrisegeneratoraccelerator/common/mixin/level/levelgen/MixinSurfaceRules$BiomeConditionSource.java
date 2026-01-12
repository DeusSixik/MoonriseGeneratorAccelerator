package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.SurfaceRulesConditions;
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
        return new SurfaceRulesConditions.BiomeCondition(context, (SurfaceRules.BiomeConditionSource) (Object) this);
    }
}

package dev.sixik.generator_accelerator.mixins.common_mixin.surface;

import dev.sixik.generator_accelerator.common.surface.SurfaceRulesContextBiomeGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context$TemperatureHelperCondition")
public abstract class MixinSurfaceRules$Context$TemperatureHelperCondition extends SurfaceRules.LazyYCondition {

    protected MixinSurfaceRules$Context$TemperatureHelperCondition(SurfaceRules.Context context) {
        super(context);
    }

    /**
     * @author Sixik
     * @reason Redirect to cached biome getter
     */
    @Overwrite
    public boolean compute() {
        final Biome biome = ((SurfaceRulesContextBiomeGetter)(Object)this.context).bts$getBiomeCached();
        return biome.coldEnoughToSnow(this.context.pos.set(this.context.blockX, this.context.blockY, this.context.blockZ));
    }
}

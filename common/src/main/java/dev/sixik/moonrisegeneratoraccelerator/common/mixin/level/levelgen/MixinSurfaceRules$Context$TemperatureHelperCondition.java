package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.SurfaceRulesContextBiomeGetter;
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
    protected boolean compute() {
        final Biome biome = ((SurfaceRulesContextBiomeGetter)(Object)this.context).bts$getBiomeCached();
        return biome.coldEnoughToSnow(this.context.pos.set(this.context.blockX, this.context.blockY, this.context.blockZ));
    }
}

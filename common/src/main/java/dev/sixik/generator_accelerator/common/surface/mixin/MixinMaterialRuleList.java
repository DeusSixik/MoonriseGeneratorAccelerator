package dev.sixik.generator_accelerator.common.surface.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(MaterialRuleList.class)
public abstract class MixinMaterialRuleList {

    @Shadow
    @Final
    private List<NoiseChunk.BlockStateFiller> materialRuleList;
    @Unique
    private NoiseChunk.BlockStateFiller[] ga$materialRuleArray;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ga$cacheRuleArray(List<NoiseChunk.BlockStateFiller> materialRuleList, CallbackInfo ci) {
        this.ga$materialRuleArray = materialRuleList.toArray(new NoiseChunk.BlockStateFiller[0]);
    }

    /**
     * @author Sixik
     * @reason Avoid per-block Iterator allocation in terrain surface material selection.
     */
    @Overwrite
    public BlockState calculate(DensityFunction.FunctionContext context) {
        NoiseChunk.BlockStateFiller[] rules = this.ga$materialRuleArray;
        if (rules == null) {
            rules = this.materialRuleList.toArray(new NoiseChunk.BlockStateFiller[0]);
            this.ga$materialRuleArray = rules;
        }
        for (int i = 0; i < rules.length; i++) {
            BlockState state = rules[i].calculate(context);
            if (state != null) {
                return state;
            }
        }
        return null;
    }
}

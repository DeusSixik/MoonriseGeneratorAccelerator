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
    @Unique
    private NoiseChunk.BlockStateFiller ga$rule0;
    @Unique
    private NoiseChunk.BlockStateFiller ga$rule1;
    @Unique
    private NoiseChunk.BlockStateFiller ga$rule2;
    @Unique
    private int ga$ruleCount;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ga$cacheRuleArray(List<NoiseChunk.BlockStateFiller> materialRuleList, CallbackInfo ci) {
        this.ga$initRules(materialRuleList.toArray(new NoiseChunk.BlockStateFiller[0]));
    }

    /**
     * @author Sixik
     * @reason Avoid per-block Iterator allocation in terrain surface material selection.
     */
    @Overwrite
    public BlockState calculate(DensityFunction.FunctionContext context) {
        NoiseChunk.BlockStateFiller[] rules = this.ga$materialRuleArray;
        if (rules == null) {
            this.ga$initRules(this.materialRuleList.toArray(new NoiseChunk.BlockStateFiller[0]));
            rules = this.ga$materialRuleArray;
        }

        switch (this.ga$ruleCount) {
            case 0:
                return null;
            case 1:
                return this.ga$rule0.calculate(context);
            case 2: {
                BlockState state = this.ga$rule0.calculate(context);
                return state != null ? state : this.ga$rule1.calculate(context);
            }
            case 3: {
                BlockState state = this.ga$rule0.calculate(context);
                if (state != null) {
                    return state;
                }
                state = this.ga$rule1.calculate(context);
                return state != null ? state : this.ga$rule2.calculate(context);
            }
            default:
                break;
        }
        for (int i = 0; i < rules.length; i++) {
            BlockState state = rules[i].calculate(context);
            if (state != null) {
                return state;
            }
        }
        return null;
    }

    @Unique
    private void ga$initRules(NoiseChunk.BlockStateFiller[] rules) {
        this.ga$materialRuleArray = rules;
        this.ga$ruleCount = rules.length;
        this.ga$rule0 = rules.length > 0 ? rules[0] : null;
        this.ga$rule1 = rules.length > 1 ? rules[1] : null;
        this.ga$rule2 = rules.length > 2 ? rules[2] : null;
    }
}

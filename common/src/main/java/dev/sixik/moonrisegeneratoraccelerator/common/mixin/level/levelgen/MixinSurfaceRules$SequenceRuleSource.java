package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.SequenceRulePrimitive;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.SequenceRuleSourcePrimitive;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.List;

@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRuleSource")
public class MixinSurfaceRules$SequenceRuleSource implements SequenceRuleSourcePrimitive {

    @Unique
    private static final List<SurfaceRules.SurfaceRule> bts$emepty_list = List.of();

    @Unique
    private SurfaceRules.RuleSource[] bts$primitiveArray;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(List<SurfaceRules.RuleSource> list, CallbackInfo ci) {
        final int size = list.size();
        if(size == 0) return;

        bts$primitiveArray = new SurfaceRules.RuleSource[size];
        for (int i = 0; i < size; i++) {
            bts$primitiveArray[i] = list.get(i);
        }
    }

    @Inject(method = "sequence", at = @At("HEAD"), cancellable = true)
    public void bts$sequence(CallbackInfoReturnable<List<SurfaceRules.RuleSource>> cir) {
        cir.setReturnValue(Arrays.asList(bts$primitiveArray));
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        final int l = bts$primitiveArray.length;

        if(l == 1) return bts$primitiveArray[0].apply(context);

        final SurfaceRules.SurfaceRule[] rules = new SurfaceRules.SurfaceRule[l];
        for (int i = 0; i < l; i++) {
            rules[i] = bts$primitiveArray[i].apply(context);
        }

        final SurfaceRules.SequenceRule rule =
                new SurfaceRules.SequenceRule(bts$emepty_list);
        ((SequenceRulePrimitive)(Object)rule).bts$setArray(rules);
        return rule;
    }

    @Override
    public void bts$setArray(SurfaceRules.RuleSource[] array) {
        this.bts$primitiveArray = array;
    }

    @Override
    public @NotNull SurfaceRules.RuleSource[] bts$getArray() {
        return bts$primitiveArray;
    }
}

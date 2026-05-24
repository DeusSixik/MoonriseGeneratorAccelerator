package dev.sixik.generator_accelerator.common.surface.mixin;

import dev.sixik.generator_accelerator.common.surface.SequenceRulePrimitive;
import dev.sixik.generator_accelerator.common.surface.SequenceRuleSourcePrimitive;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
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
import java.util.Collections;
import java.util.List;

@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRuleSource")
public class MixinSurfaceRules$SequenceRuleSource implements SequenceRuleSourcePrimitive {

    @Unique
    private static final List<SurfaceRules.SurfaceRule> bts$empty_rule_list = ObjectLists.emptyList();

    @Unique
    private static final List<SurfaceRules.RuleSource> bts$empty_source_list = ObjectLists.emptyList();

    @Unique
    private static final SurfaceRules.RuleSource[] bts$empty_source_array = new SurfaceRules.RuleSource[0];

    @Unique
    private SurfaceRules.RuleSource[] bts$primitiveArray = bts$empty_source_array;

    @Unique
    private List<SurfaceRules.RuleSource> bts$primitiveList = bts$empty_source_list;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(List<SurfaceRules.RuleSource> list, CallbackInfo ci) {
        final int size = list.size();
        if (size == 0) {
            this.bts$primitiveArray = bts$empty_source_array;
            this.bts$primitiveList = bts$empty_source_list;
            return;
        }

        bts$primitiveArray = new SurfaceRules.RuleSource[size];
        for (int i = 0; i < size; i++) {
            bts$primitiveArray[i] = list.get(i);
        }
        bts$primitiveList = new ObjectArrayList<>(bts$primitiveArray);
    }

    @Inject(method = "sequence", at = @At("HEAD"), cancellable = true)
    public void bts$sequence(CallbackInfoReturnable<List<SurfaceRules.RuleSource>> cir) {
        cir.setReturnValue(bts$primitiveList);
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        final int l = bts$primitiveArray.length;

        if (l == 1) return bts$primitiveArray[0].apply(context);

        final SurfaceRules.SurfaceRule[] rules = new SurfaceRules.SurfaceRule[l];
        for (int i = 0; i < l; i++) {
            rules[i] = bts$primitiveArray[i].apply(context);
        }

        final SurfaceRules.SequenceRule rule =
                new SurfaceRules.SequenceRule(bts$empty_rule_list);
        ((SequenceRulePrimitive)(Object)rule).bts$setArray(rules);
        return rule;
    }

    @Override
    public void bts$setArray(SurfaceRules.RuleSource[] array) {
        this.bts$primitiveArray = array == null ? bts$empty_source_array : array;
        this.bts$primitiveList = this.bts$primitiveArray.length == 0
                ? bts$empty_source_list
                : new ObjectArrayList<>(this.bts$primitiveArray);
    }

    @Override
    public @NotNull SurfaceRules.RuleSource[] bts$getArray() {
        return bts$primitiveArray;
    }
}

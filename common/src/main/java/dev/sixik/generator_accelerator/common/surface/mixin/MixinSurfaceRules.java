package dev.sixik.generator_accelerator.common.surface.mixin;

import dev.sixik.generator_accelerator.common.surface.SequenceRuleSourcePrimitive;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;

@Mixin(SurfaceRules.class)
public class MixinSurfaceRules {

    @Unique
    private static final List<SurfaceRules.RuleSource> bts$empty_list = Collections.emptyList();

    @Inject(method = "sequence", at = @At("HEAD"), cancellable = true)
    private static void bts$sequence(SurfaceRules.RuleSource[] ruleSources, CallbackInfoReturnable<SurfaceRules.RuleSource> cir) {
        if (ruleSources.length == 0)
            throw new IllegalArgumentException("Need at least 1 rule for a sequence");

        final SurfaceRules.SequenceRuleSource source =
                new SurfaceRules.SequenceRuleSource(bts$empty_list);
        ((SequenceRuleSourcePrimitive)(Object)source).bts$setArray(ruleSources);
        cir.setReturnValue(source);
    }
}

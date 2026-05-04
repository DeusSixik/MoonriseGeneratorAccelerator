package dev.sixik.generator_accelerator.mixins.common_mixin.surface;

import dev.sixik.generator_accelerator.common.surface.SequenceRulePrimitive;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(SurfaceRules.SequenceRule.class)
public class MixinSurfaceRules$SequenceRule implements SequenceRulePrimitive {

    @Unique
    private SurfaceRules.SurfaceRule[] bts$primitiveArray;

    @Override
    public void bts$setArray(SurfaceRules.SurfaceRule[] array) {
        this.bts$primitiveArray = array;
    }

    @Override
    public @NotNull SurfaceRules.SurfaceRule[] bts$getArray() {
        return bts$primitiveArray;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(List<SurfaceRules.SurfaceRule> list, CallbackInfo ci) {
        this.bts$primitiveArray = list.toArray(new SurfaceRules.SurfaceRule[0]);
    }

    @Inject(method = "rules", at = @At("HEAD"), cancellable = true)
    public void bts$rules(CallbackInfoReturnable<List<SurfaceRules.SurfaceRule>> cir) {
        cir.setReturnValue(List.of(this.bts$primitiveArray));
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public @Nullable BlockState tryApply(int x, int y, int z) {
        final SurfaceRules.SurfaceRule[] array = bts$primitiveArray;
        for (int i = 0; i < array.length; i++) {
           final BlockState blockState = array[i].tryApply(x, y, z);
           if(blockState != null) return blockState;
        }

        return null;
    }
}

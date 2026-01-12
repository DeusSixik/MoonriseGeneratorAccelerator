package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.SequenceRulePrimitive;
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

import java.util.Arrays;
import java.util.List;

@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRule")
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
        final int size = list.size();
        if(size == 0) return;

        bts$primitiveArray = new SurfaceRules.SurfaceRule[size];
        for (int i = 0; i < size; i++) {
            bts$primitiveArray[i] = list.get(i);
        }
    }

    @Inject(method = "rules", at = @At("HEAD"), cancellable = true)
    public void bts$rules(CallbackInfoReturnable<List<SurfaceRules.SurfaceRule>> cir) {
        cir.setReturnValue(Arrays.asList(bts$primitiveArray));
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

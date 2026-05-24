package dev.sixik.generator_accelerator.common.surface.mixin;

import dev.sixik.generator_accelerator.common.surface.SequenceRulePrimitive;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollections;
import it.unimi.dsi.fastutil.objects.ObjectLists;
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
import java.util.Collections;
import java.util.List;

@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRule")
public class MixinSurfaceRules$SequenceRule implements SequenceRulePrimitive {

    @Unique
    private static final SurfaceRules.SurfaceRule[] bts$empty_rule_array = new SurfaceRules.SurfaceRule[0];

    @Unique
    private static final List<SurfaceRules.SurfaceRule> bts$empty_rule_list = ObjectLists.emptyList();

    @Unique
    private SurfaceRules.SurfaceRule[] bts$primitiveArray = bts$empty_rule_array;

    @Unique
    private List<SurfaceRules.SurfaceRule> bts$primitiveList = bts$empty_rule_list;

    @Override
    public void bts$setArray(SurfaceRules.SurfaceRule[] array) {
        this.bts$primitiveArray = array == null ? bts$empty_rule_array : array;
        this.bts$primitiveList = this.bts$primitiveArray.length == 0
                ? bts$empty_rule_list
                : new ObjectArrayList<>(this.bts$primitiveArray);
    }

    @Override
    public @NotNull SurfaceRules.SurfaceRule[] bts$getArray() {
        return bts$primitiveArray;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(List<SurfaceRules.SurfaceRule> list, CallbackInfo ci) {
        int size = list.size();
        if (size == 0) {
            this.bts$primitiveArray = bts$empty_rule_array;
            this.bts$primitiveList = bts$empty_rule_list;
            return;
        }
        this.bts$primitiveArray = list.toArray(new SurfaceRules.SurfaceRule[size]);
        this.bts$primitiveList = new ObjectArrayList<>(this.bts$primitiveArray);;
    }

    @Inject(method = "rules", at = @At("HEAD"), cancellable = true)
    public void bts$rules(CallbackInfoReturnable<List<SurfaceRules.SurfaceRule>> cir) {
        cir.setReturnValue(this.bts$primitiveList);
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

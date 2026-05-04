package dev.sixik.generator_accelerator.mixins.common_mixin.features;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

@Mixin(HolderSet.Direct.class)
public class FastHolderSetMixin<T> {

    @Shadow
    private Set<Holder<T>> contentsSet;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInitFastSet(List<Holder<T>> list, CallbackInfo ci) {
        this.contentsSet = new ObjectOpenHashSet<>(list);
    }
}
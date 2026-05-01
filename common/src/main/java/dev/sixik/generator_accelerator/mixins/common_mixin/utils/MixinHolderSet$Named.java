package dev.sixik.generator_accelerator.mixins.common_mixin.utils;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.HolderSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.List;

@Mixin(HolderSet.Named.class)
public class MixinHolderSet$Named {

    @Redirect(method = "bind", at = @At(value = "INVOKE", target = "Ljava/util/List;copyOf(Ljava/util/Collection;)Ljava/util/List;"))
    <E> List<E> bts$bind(Collection<? extends E> coll) {
        return new ObjectArrayList<>(coll);
    }
}

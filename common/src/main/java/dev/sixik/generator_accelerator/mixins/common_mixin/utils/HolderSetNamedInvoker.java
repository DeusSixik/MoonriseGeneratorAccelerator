package dev.sixik.generator_accelerator.mixins.common_mixin.utils;

import net.minecraft.core.HolderSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(HolderSet.Named.class)
public interface HolderSetNamedInvoker<T> {

    @Invoker("contents")
    List<T> ga$contents();
}

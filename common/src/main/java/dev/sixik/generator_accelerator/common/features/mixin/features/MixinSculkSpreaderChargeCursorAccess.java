package dev.sixik.generator_accelerator.common.features.mixin.features;

import net.minecraft.world.level.block.SculkSpreader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SculkSpreader.ChargeCursor.class)
public interface MixinSculkSpreaderChargeCursorAccess {
    @Invoker("mergeWith")
    void ga$mergeWith(SculkSpreader.ChargeCursor cursor);
}

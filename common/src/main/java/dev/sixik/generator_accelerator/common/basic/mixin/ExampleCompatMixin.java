package dev.sixik.generator_accelerator.common.basic.mixin;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.annotation.CompatMixin;
import dev.sixik.generator_accelerator.api.mixin.annotation.DisableMixins;
import dev.sixik.generator_accelerator.api.mixin.annotation.DissableMixinRegister;
import dev.sixik.generator_accelerator.api.mixin.annotation.MixinOnConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MinecraftServer.class)
@CompatMixin(
        mod = GeneratorAccelerator.class
)
@DisableMixins(valueByClass = ExampleCompatTest2Mixin.class)
@MixinOnConfig(name = "enableExampleCompatMixin")
@DissableMixinRegister
public abstract class ExampleCompatMixin {
}

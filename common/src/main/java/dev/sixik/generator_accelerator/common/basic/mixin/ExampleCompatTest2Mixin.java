package dev.sixik.generator_accelerator.common.basic.mixin;

import dev.sixik.generator_accelerator.api.mixin.annotation.DissableMixinRegister;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MinecraftServer.class)
@DissableMixinRegister
public abstract class ExampleCompatTest2Mixin {
}

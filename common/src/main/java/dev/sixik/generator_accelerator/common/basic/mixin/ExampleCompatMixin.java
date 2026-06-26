package dev.sixik.generator_accelerator.common.basic.mixin;

import com.ishland.c2me.base.C2MEBaseMod;
import dev.sixik.generator_accelerator.api.mixin.annotation.CompatMixin;
import dev.sixik.generator_accelerator.api.mixin.annotation.MixinOnConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MinecraftServer.class)
@CompatMixin(
        mod = C2MEBaseMod.class,
        disable = {
                "dev.sixik.generator_accelerator.common.basic.mixin.ExampleCompatTest2Mixin"
        }
)
@MixinOnConfig(name = "enableExampleCompatMixin")
public abstract class ExampleCompatMixin {
}

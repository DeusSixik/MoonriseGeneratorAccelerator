package dev.sixik.generator_accelerator.common.basic.mixin;

import dev.sixik.generator_accelerator.api.mixin.annotation.CompatMixin;
import dev.sixik.generator_accelerator.api.mixin.annotation.MixinOnConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

// Empty demo mixin that exists only to show the generated config wiring.
@Mixin(MinecraftServer.class)
@CompatMixin(
        mode = CompatMixin.MatchMode.ANY,
        mod = MinecraftServer.class,
        disable = {
                "example.foreign.mixin.PlaceholderMixinA",
                "example.foreign.mixin.PlaceholderMixinB"
        }
)
@MixinOnConfig(
        name = "enableBasicExampleMixinTest",
        defaultValue = false,
        comment = """
                Example toggle generated from @MixinOnConfig.
                Enable it to allow ExampleGeneratedToggleMixin to load.
                """
)
public abstract class ExampleGeneratedToggleMixin {
}

package dev.sixik.generator_accelerator.common.density.mixin;

import dev.sixik.generator_accelerator.api.mixin.annotation.CompatMixin;
import dev.sixik.generator_accelerator.api.mixin.annotation.DissableMixinRegister;
import dev.sixik.generator_accelerator.api.mixin.annotation.DisableMixins;
import dev.sixik.generator_accelerator.api.mixin.annotation.MixinOnConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MinecraftServer.class)
@DissableMixinRegister
@CompatMixin(modClassName = "com.ishland.c2me.opts.dfc.common.ast.McToAst")
@DisableMixins(value = {
        "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSampler",
        "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSampler1",
        "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerCache2D",
        "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerCacheOnce",
        "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerCellCache",
        "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerDensityInterpolator",
        "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerFlatCache",
        "com.ishland.c2me.opts.dfc.mixin.MixinDFTBinaryOperation",
        "com.ishland.c2me.opts.dfc.mixin.MixinDFTWrapping",
        "com.ishland.c2me.opts.dfc.mixin.MixinNoiseConfig",
        "com.ishland.c2me.opts.dfc.mixin.MixinSplineImplementation"
})
@MixinOnConfig(
        name = "enableDensityCompilerPatch",
        comment = """
                Enables the Density Function Compiler mixin module.
                Also disables conflicting C2ME DFC mixins when their compat classes are present.
                """
)
public class DensityMixinDissable {
}

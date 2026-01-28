package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.density.NoiseBasedChunkGeneratorOptimizeDensity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerLevel.class)
public class MixinServerLevel$optimize_density {

    @Redirect(method = "<init>*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/dimension/LevelStem;generator()Lnet/minecraft/world/level/chunk/ChunkGenerator;"))
    public ChunkGenerator bts$init(LevelStem instance) {

        final var generator = instance.generator();
        if(generator instanceof NoiseBasedChunkGeneratorOptimizeDensity optimizeDensity)
            optimizeDensity.bts$applyDensityOptimize();;

        return generator;
    }
}

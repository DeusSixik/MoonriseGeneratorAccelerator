package dev.sixik.moonrisegeneratoraccelerator.common.mixin.tests;

import dev.sixik.density_interpreter.tests.NoiseChunkInterface;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NoiseChunk.class)
public class NoiseChunkMixinTest implements NoiseChunkInterface {

    @Shadow
    @Final
    private NoiseChunk.BlockStateFiller blockStateRule;

    @Override
    public NoiseChunk.BlockStateFiller bts$getRules() {
        return blockStateRule;
    }
}

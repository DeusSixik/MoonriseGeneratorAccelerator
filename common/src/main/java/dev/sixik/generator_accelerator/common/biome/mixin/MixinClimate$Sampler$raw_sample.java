package dev.sixik.generator_accelerator.common.biome.mixin;

import dev.sixik.generator_accelerator.common.biome.ClimateSamplerRaw;
import dev.sixik.generator_accelerator.common.biome.MutableDensityFunctionContext;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Climate.Sampler.class)
public abstract class MixinClimate$Sampler$raw_sample implements ClimateSamplerRaw {
    @Shadow
    @Final
    private DensityFunction temperature;
    @Shadow
    @Final
    private DensityFunction humidity;
    @Shadow
    @Final
    private DensityFunction continentalness;
    @Shadow
    @Final
    private DensityFunction erosion;
    @Shadow
    @Final
    private DensityFunction depth;
    @Shadow
    @Final
    private DensityFunction weirdness;

    @Unique
    private static final ThreadLocal<MutableDensityFunctionContext> GA$RAW_CONTEXT =
            ThreadLocal.withInitial(MutableDensityFunctionContext::new);

    /**
     * @author Sixik
     * @reason Reuse a mutable density context for vanilla biome sampling and avoid
     * allocating DensityFunction.SinglePointContext on every BiomeManager query.
     */
    @Overwrite
    public Climate.TargetPoint sample(int quartX, int quartY, int quartZ) {
        MutableDensityFunctionContext context = GA$RAW_CONTEXT.get().set(
                QuartPos.toBlock(quartX),
                QuartPos.toBlock(quartY),
                QuartPos.toBlock(quartZ)
        );
        return Climate.target(
                (float) this.temperature.compute(context),
                (float) this.humidity.compute(context),
                (float) this.continentalness.compute(context),
                (float) this.erosion.compute(context),
                (float) this.depth.compute(context),
                (float) this.weirdness.compute(context)
        );
    }

    @Override
    public void ga$sampleRaw(int quartX, int quartY, int quartZ, long[] out) {
        MutableDensityFunctionContext context = GA$RAW_CONTEXT.get().set(
                QuartPos.toBlock(quartX),
                QuartPos.toBlock(quartY),
                QuartPos.toBlock(quartZ)
        );
        out[0] = Climate.quantizeCoord((float) temperature.compute(context));
        out[1] = Climate.quantizeCoord((float) humidity.compute(context));
        out[2] = Climate.quantizeCoord((float) continentalness.compute(context));
        out[3] = Climate.quantizeCoord((float) erosion.compute(context));
        out[4] = Climate.quantizeCoord((float) depth.compute(context));
        out[5] = Climate.quantizeCoord((float) weirdness.compute(context));
    }
}

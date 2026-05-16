package dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender;

import dev.sixik.generator_accelerator.common.biome.ClimateSamplerRaw;
import dev.sixik.generator_accelerator.common.biome.GARawBiomeResolver;
import dev.sixik.generator_accelerator.common.biome.TerraBlenderClimateSearch;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import terrablender.mixin.MultiNoiseBiomeSourceAccess;
import terrablender.worldgen.IExtendedParameterList;

@Mixin(value = MultiNoiseBiomeSource.class, priority = 1600)
public abstract class Terrablender$MixinMultiNoiseBiomeSource$raw_biome_resolver implements GARawBiomeResolver {
    @Unique
    private IExtendedParameterList<?> ga$parameterList;

    @Override
    public boolean ga$hasRawBiomeLookup(Climate.Sampler sampler) {
        return (Object) sampler instanceof ClimateSamplerRaw
                && this.ga$getTerraBlenderParameterList() instanceof IExtendedParameterList<?> parameterList
                && parameterList.isInitialized();
    }

    @Override
    public Holder<Biome> ga$getRawNoiseBiome(int x, int y, int z, Climate.Sampler sampler, long[] scratchTarget) {
        IExtendedParameterList<?> parameterList = this.ga$parameterList;
        ((ClimateSamplerRaw) (Object) sampler).ga$sampleRaw(x, y, z, scratchTarget);
        return TerraBlenderClimateSearch.findRaw(parameterList, scratchTarget, x, y, z);
    }

    @Unique
    private IExtendedParameterList<?> ga$getTerraBlenderParameterList() {
        if (this.ga$parameterList == null) {
            Object parameters = ((MultiNoiseBiomeSourceAccess) this).getParameters().left().orElse(null);
            if (parameters instanceof IExtendedParameterList<?> parameterList) {
                this.ga$parameterList = parameterList;
            }
        }
        return this.ga$parameterList;
    }
}

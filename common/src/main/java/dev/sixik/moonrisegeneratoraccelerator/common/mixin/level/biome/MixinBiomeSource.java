package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.biome;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

@Mixin(BiomeSource.class)
public abstract class MixinBiomeSource {

    @Shadow
    public abstract Holder<Biome> getNoiseBiome(int i, int j, int k, Climate.Sampler sampler);

    /**
     * @author Sixik
     * @reason Avoid allocating HashSet and use fast iteration.
     */
    @Overwrite
    public Set<Holder<Biome>> getBiomesWithin(int x, int y, int z, int radius, Climate.Sampler sampler) {
        final int minX = QuartPos.fromBlock(x - radius);
        final int minY = QuartPos.fromBlock(y - radius);
        final int minZ = QuartPos.fromBlock(z - radius);
        final int maxX = QuartPos.fromBlock(x + radius);
        final int maxY = QuartPos.fromBlock(y + radius);
        final int maxZ = QuartPos.fromBlock(z + radius);

        final int sizeX = maxX - minX + 1;
        final int sizeY = maxY - minY + 1;
        final int sizeZ = maxZ - minZ + 1;

        final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Holder<Biome>> set =
                new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();

        for(int k = 0; k < sizeZ; ++k) {
            for(int j = 0; j < sizeX; ++j) {
                for(int i = 0; i < sizeY; ++i) {
                    final int qX = minX + j;
                    final int qY = minY + i;
                    final int qZ = minZ + k;
                    set.add(this.getNoiseBiome(qX, qY, qZ, sampler));
                }
            }
        }

        return set;
    }
}

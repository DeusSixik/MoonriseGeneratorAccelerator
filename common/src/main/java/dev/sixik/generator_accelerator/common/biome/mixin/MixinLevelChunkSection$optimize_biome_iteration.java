package dev.sixik.generator_accelerator.common.biome.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$SingleValuePaletteMutator;
import dev.sixik.generator_accelerator.common.biome.GARawBiomeResolver;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;

@Mixin(LevelChunkSection.class)
public class MixinLevelChunkSection$optimize_biome_iteration {

    @Shadow
    private PalettedContainerRO<Holder<Biome>> biomes;

    /**
     * ThreadLocal buffer to avoid allocating Holder[64] on every call.
     * Buffer is used ONLY when we detect non-uniform biomes.
     */
    @Unique
    private static final ThreadLocal<Holder<Biome>[]> BTS_BIOME_BUFFER = ThreadLocal.withInitial(() -> new Holder[64]);

    @Unique
    private static final ThreadLocal<long[]> BTS_RAW_BIOME_TARGET = ThreadLocal.withInitial(() -> new long[6]);


    /**
     * @author Sixik
     * @see <a href="https://github.com/Steveplays28/noisium/blob/c640041c8c932b36753c0ccf43902ac8b0bd252d/common/src/main/java/io/github/steveplays28/noisium/mixin/ChunkSectionMixin.java#L23-L40">
     *      Original Noisium implementation</a> for performance comparison baseline
     * @reason
     * Optimized biome processing implementation with significant performance improvements
     * over the original Noisium-based solution.
     *
     * <p>This implementation employs several key optimizations:
     * <ul>
     *   <li><b>Lazy array allocation</b> - The 64-element array is only allocated when
     *       the first biome mismatch is detected, avoiding unnecessary memory overhead
     *       for uniform biome distributions.</li>
     *   <li><b>Fast-path for uniform data</b> - When all 64 biomes are identical, the method
     *       takes an optimized path that reconstructs the container with a single value,
     *       minimizing computation and memory usage.</li>
     *   <li><b>Efficient non-uniform handling</b> - For non-uniform distributions, remaining
     *       values are populated and written into a reconstructed container in a single pass.</li>
     * </ul>
     *
     */
    @Overwrite
    public void fillBiomesFromNoise(BiomeResolver biomeResolver, Climate.Sampler climateSampler, int x, int y, int z) {
        final GARawBiomeResolver rawResolver = biomeResolver instanceof GARawBiomeResolver fastResolver
                && fastResolver.ga$hasRawBiomeLookup(climateSampler)
                ? fastResolver
                : null;
        final long[] rawTarget = rawResolver == null ? null : BTS_RAW_BIOME_TARGET.get();
        final Holder<Biome> firstBiome = rawResolver != null
                ? rawResolver.ga$getRawNoiseBiome(x, y, z, climateSampler, rawTarget)
                : biomeResolver.getNoiseBiome(x, y, z, climateSampler);

        boolean isUniform = true;

        /*
            Lazy buffer: allocated ONLY if we detect a mismatch.
         */
        Holder<Biome>[] buffer = null;

        /*
            Keep the same storage index, but sample Y values next to each other for a
            fixed X/Z column. TerraBlender's uniqueness is X/Z-only and climate lookups
            warm-start better when only depth changes between adjacent queries.
         */
        for (int pZ = 0; pZ < 4; pZ++) {
            for (int pX = 0; pX < 4; pX++) {
                for (int pY = 0; pY < 4; pY++) {
                    if ((pX | pY | pZ) == 0) {
                        continue;
                    }
                    final int index = (pY << 4) | (pZ << 2) | pX;
                    final Holder<Biome> biome = rawResolver != null
                            ? rawResolver.ga$getRawNoiseBiome(x + pX, y + pY, z + pZ, climateSampler, rawTarget)
                            : biomeResolver.getNoiseBiome(x + pX, y + pY, z + pZ, climateSampler);

                    if (isUniform) {
                        if (biome != firstBiome) {
                            isUniform = false;
                            buffer = BTS_BIOME_BUFFER.get();
                            Arrays.fill(buffer, firstBiome);
                            buffer[index] = biome;
                        }
                    } else {
                        buffer[index] = biome;
                    }
                }
            }
        }

        /*
            FAST PATH: all 64 samples identical
         */
        if (isUniform) {
            final PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) this.biomes;
            if (biomes.data.storage().getBits() == 0
                    && biomes.data.palette instanceof GA$SingleValuePaletteMutator<?> mutator) {
                ((GA$SingleValuePaletteMutator<Holder<Biome>>) mutator).ga$setSingleValue(firstBiome);
                return;
            }

            /*
                Build a "single value" container (no 64 writes)
             */
            this.biomes = new PalettedContainer<>(biomes.registry, firstBiome, biomes.strategy);
            return;
        }

        /*
            SLOW PATH: we have mixed biomes; fill remaining values (after mismatch point)
            Important: positions before mismatch were backfilled already, but buffer[0] must be set too.
         */
//        buffer[0] = firstBiome;

        /*
            Recreate container and write values
         */
        final PalettedContainer<Holder<Biome>> container = this.biomes.recreate();

        for (int i = 0; i < 64; i++) {

            /*
                Direct internal write
             */
            final int paletteId = container.data.palette.idFor(buffer[i]);
            container.data.storage().set(i, paletteId);
        }

        this.biomes = container;
    }
}

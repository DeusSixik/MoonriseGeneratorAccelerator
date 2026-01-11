package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.chunk.storage;

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
        final Holder<Biome> firstBiome = biomeResolver.getNoiseBiome(x, y, z, climateSampler);

        boolean isUniform = true;

        /*
            Lazy buffer: allocated ONLY if we detect a mismatch.
         */
        Holder<Biome>[] buffer = null;

        /*
            We start from i = 1 because i = 0 is (x, y, z) already sampled.
         */
        for (int i = 1; i < 64; i++) {
            /*
                i = (pY << 4) | (pZ << 2) | pX
             */
            final int pX = i & 3;
            final int pZ = (i >> 2) & 3;
            final int pY = (i >> 4);

            final Holder<Biome> biome = biomeResolver.getNoiseBiome(x + pX, y + pY, z + pZ, climateSampler);

            if (isUniform) {
                /*
                    Still uniform so far: check mismatch.
                 */
                if (biome != firstBiome) {
                    isUniform = false;

                    /*
                     Allocate buffer only now, and backfill already-visited positions with firstBiome.
                     This makes the uniform path pay 0 extra allocations.
                    */
                    buffer = BTS_BIOME_BUFFER.get();
                    buffer[0] = firstBiome;
                    for (int j = 1; j < i; j++) buffer[j] = firstBiome;
                    buffer[i] = biome;
                }
            } else {
                /*
                    Already non-uniform -> just store.
                 */
                buffer[i] = biome;
            }
        }

        /*
            FAST PATH: all 64 samples identical
         */
        if (isUniform) {
            final PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) this.biomes;

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

        buffer[0] = firstBiome;

        /*
            Recreate container and write values
         */
        final PalettedContainer<Holder<Biome>> container = this.biomes.recreate();

        for (int i = 0; i < 64; i++) {
            final int pX = i & 3;
            final int pZ = (i >> 2) & 3;
            final int pY = (i >> 4);

            /*
                Direct internal write
             */
            final int paletteId = container.data.palette.idFor(buffer[i]);
            container.data.storage().set(container.strategy.getIndex(pX, pY, pZ), paletteId);
        }

        this.biomes = container;
    }
}

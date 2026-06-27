package dev.sixik.generator_accelerator.common.biome.mixin;

import com.google.common.hash.Hashing;
import dev.sixik.generator_accelerator.common.biome.structs.GA$SeedCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.*;

@Mixin(BiomeManager.class)
public class MixinBiomeManager$_better_biome_getting {

    @Final
    @Shadow
    private BiomeManager.NoiseBiomeSource noiseBiomeSource;
    @Final
    @Shadow
    private long biomeZoomSeed;

    @Unique
    private static final long LCG_MUL = 6364136223846793005L;

    @Unique
    private static final long LCG_ADD = 1442695040888963407L;

    @Unique
    private static final ThreadLocal<GA$SeedCache> ga$seed_cache = ThreadLocal.withInitial(GA$SeedCache::new);

    /**
     * @author Sixik
     * @reason WorldGenRegion rebuilds BiomeManager for many chunk steps with the
     * same world seed. Cache the pure SHA result and avoid per-region hasher allocation.
     */
    @Overwrite
    public static long obfuscateSeed(final long seed) {
        final GA$SeedCache cache = ga$seed_cache.get();
        if(cache.hasValue && cache.seed == seed) {
            return cache.obfuscated;
        }

        final long obfuscated = Hashing.sha256().hashLong(seed).asLong();
        cache.seed = seed;
        cache.obfuscated = obfuscated;
        cache.hasValue = true;
        return obfuscated;
    }

    /**
     * @author Sixik
     * @reason Optimized LCG math (inline) and removed Math.floorMod (bitwise AND).
     */
    @Overwrite
    public Holder<Biome> getBiome(BlockPos pos) {
        final int x = pos.getX() - 2;
        final int y = pos.getY() - 2;
        final int z = pos.getZ() - 2;

        /*
            Bit shifts instead of division
         */
        final int quartX = x >> 2;
        final int quartY = y >> 2;
        final int quartZ = z >> 2;

        /*
            Pre-calculate fractions
         */
        final double fracX = (double) (x & 3) * 0.25D;
        final double fracY = (double) (y & 3) * 0.25D;
        final double fracZ = (double) (z & 3) * 0.25D;

        final int[] cX = { quartX, quartX + 1 };
        final int[] cY = { quartY, quartY + 1 };
        final int[] cZ = { quartZ, quartZ + 1 };

        final double[] oX = { fracX, fracX - 1.0D };
        final double[] oY = { fracY, fracY - 1.0D };
        final double[] oZ = { fracZ, fracZ - 1.0D };

        int bestIndex = 0;
        double minDistance = Double.POSITIVE_INFINITY;
        final long baseSeed = this.biomeZoomSeed;

        for (int p = 0; p < 8; ++p) {
            final int ix = (p >> 2) & 1;
            final int iy = (p >> 1) & 1;
            final int iz = p & 1;

            long m = baseSeed * LCG_MUL + LCG_ADD + cX[ix];
            m = m * LCG_MUL + LCG_ADD + cY[iy];
            m = m * LCG_MUL + LCG_ADD + cZ[iz];

            m = m * LCG_MUL + LCG_ADD + cX[ix];
            m = m * LCG_MUL + LCG_ADD + cY[iy];
            m = m * LCG_MUL + LCG_ADD + cZ[iz];

            final double fidX = bts$getFiddle(m);
            m = m * LCG_MUL + LCG_ADD + baseSeed;
            final double fidY = bts$getFiddle(m);
            m = m * LCG_MUL + LCG_ADD + baseSeed;
            final double fidZ = bts$getFiddle(m);

            final double dX = oX[ix] + fidX;
            final double dY = oY[iy] + fidY;
            final double dZ = oZ[iz] + fidZ;
            final double dist = (dX * dX) + (dY * dY) + (dZ * dZ);

            if (dist < minDistance) {
                bestIndex = p;
                minDistance = dist;
            }
        }

        return this.noiseBiomeSource.getNoiseBiome(
                cX[(bestIndex >> 2) & 1],
                cY[(bestIndex >> 1) & 1],
                cZ[bestIndex & 1]
        );
    }

    /**
     * Optimized Fiddle calculation.
     * Replaces Math.floorMod(x, 1024) with (x & 1023).
     * Works for negative numbers because 1024 is a power of 2.
     */
    @Unique
    private static double bts$getFiddle(long l) {
        return (((double) ((int) (l >> 24) & 1023) * 0.0009765625D) - 0.5D) * 0.9D;
    }
}

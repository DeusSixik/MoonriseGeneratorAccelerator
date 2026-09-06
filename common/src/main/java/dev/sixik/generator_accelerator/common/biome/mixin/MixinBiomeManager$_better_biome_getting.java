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
        if (cache.hasValue && cache.seed == seed) {
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

        final int qx0 = x >> 2;
        final int qy0 = y >> 2;
        final int qz0 = z >> 2;
        final int qx1 = qx0 + 1;
        final int qy1 = qy0 + 1;
        final int qz1 = qz0 + 1;

        final double ox0 = (double) (x & 3) * 0.25D;
        final double oy0 = (double) (y & 3) * 0.25D;
        final double oz0 = (double) (z & 3) * 0.25D;
        final double ox1 = ox0 - 1.0D;
        final double oy1 = oy0 - 1.0D;
        final double oz1 = oz0 - 1.0D;

        final long baseSeed = this.biomeZoomSeed;

        int bestX = qx0;
        int bestY = qy0;
        int bestZ = qz0;
        double minDistance;

        // Step for X0
        final long mX0 = baseSeed * LCG_MUL + LCG_ADD + qx0;
        {
            // Y0
            final long mXY00 = mX0 * LCG_MUL + LCG_ADD + qy0;
            {
                // Z0: point (0, 0, 0)
                long m = (mXY00 * LCG_MUL + LCG_ADD + qz0) * LCG_MUL + LCG_ADD + qx0;
                m = (m * LCG_MUL + LCG_ADD + qy0) * LCG_MUL + LCG_ADD + qz0;
                double dX = ox0 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                double dY = oy0 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                double dZ = oz0 + bts$getFiddle(m);
                minDistance = (dX * dX) + (dY * dY) + (dZ * dZ);

                // Z1: point (0, 0, 1)
                m = (mXY00 * LCG_MUL + LCG_ADD + qz1) * LCG_MUL + LCG_ADD + qx0;
                m = (m * LCG_MUL + LCG_ADD + qy0) * LCG_MUL + LCG_ADD + qz1;
                dX = ox0 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                dY = oy0 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                dZ = oz1 + bts$getFiddle(m);
                double dist = (dX * dX) + (dY * dY) + (dZ * dZ);
                if (dist < minDistance) {
                    minDistance = dist;
                    bestZ = qz1;
                }
            }
            // Y1
            final long mXY01 = mX0 * LCG_MUL + LCG_ADD + qy1;
            {
                // Z0: point (0, 1, 0)
                long m = (mXY01 * LCG_MUL + LCG_ADD + qz0) * LCG_MUL + LCG_ADD + qx0;
                m = (m * LCG_MUL + LCG_ADD + qy1) * LCG_MUL + LCG_ADD + qz0;
                double dX = ox0 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                double dY = oy1 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                double dZ = oz0 + bts$getFiddle(m);
                double dist = (dX * dX) + (dY * dY) + (dZ * dZ);
                if (dist < minDistance) {
                    minDistance = dist;
                    bestY = qy1;
                    bestZ = qz0;
                }

                // Z1: point (0, 1, 1)
                m = (mXY01 * LCG_MUL + LCG_ADD + qz1) * LCG_MUL + LCG_ADD + qx0;
                m = (m * LCG_MUL + LCG_ADD + qy1) * LCG_MUL + LCG_ADD + qz1;
                dX = ox0 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                dY = oy1 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                dZ = oz1 + bts$getFiddle(m);
                dist = (dX * dX) + (dY * dY) + (dZ * dZ);
                if (dist < minDistance) {
                    minDistance = dist;
                    bestY = qy1;
                    bestZ = qz1;
                }
            }
        }

        // Step for X1
        final long mX1 = baseSeed * LCG_MUL + LCG_ADD + qx1;
        {
            // Y0
            final long mXY10 = mX1 * LCG_MUL + LCG_ADD + qy0;
            {
                // Z0: point (1, 0, 0)
                long m = (mXY10 * LCG_MUL + LCG_ADD + qz0) * LCG_MUL + LCG_ADD + qx1;
                m = (m * LCG_MUL + LCG_ADD + qy0) * LCG_MUL + LCG_ADD + qz0;
                double dX = ox1 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                double dY = oy0 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                double dZ = oz0 + bts$getFiddle(m);
                double dist = (dX * dX) + (dY * dY) + (dZ * dZ);
                if (dist < minDistance) {
                    minDistance = dist;
                    bestX = qx1;
                    bestY = qy0;
                    bestZ = qz0;
                }

                // Z1: point (1, 0, 1)
                m = (mXY10 * LCG_MUL + LCG_ADD + qz1) * LCG_MUL + LCG_ADD + qx1;
                m = (m * LCG_MUL + LCG_ADD + qy0) * LCG_MUL + LCG_ADD + qz1;
                dX = ox1 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                dY = oy0 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                dZ = oz1 + bts$getFiddle(m);
                dist = (dX * dX) + (dY * dY) + (dZ * dZ);
                if (dist < minDistance) {
                    minDistance = dist;
                    bestX = qx1;
                    bestY = qy0;
                    bestZ = qz1;
                }
            }
            // Y1
            final long mXY11 = mX1 * LCG_MUL + LCG_ADD + qy1;
            {
                // Z0: point (1, 1, 0)
                long m = (mXY11 * LCG_MUL + LCG_ADD + qz0) * LCG_MUL + LCG_ADD + qx1;
                m = (m * LCG_MUL + LCG_ADD + qy1) * LCG_MUL + LCG_ADD + qz0;
                double dX = ox1 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                double dY = oy1 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                double dZ = oz0 + bts$getFiddle(m);
                double dist = (dX * dX) + (dY * dY) + (dZ * dZ);
                if (dist < minDistance) {
                    minDistance = dist;
                    bestX = qx1;
                    bestY = qy1;
                    bestZ = qz0;
                }

                // Z1: point (1, 1, 1)
                m = (mXY11 * LCG_MUL + LCG_ADD + qz1) * LCG_MUL + LCG_ADD + qx1;
                m = (m * LCG_MUL + LCG_ADD + qy1) * LCG_MUL + LCG_ADD + qz1;
                dX = ox1 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                dY = oy1 + bts$getFiddle(m);
                m = m * LCG_MUL + LCG_ADD + baseSeed;
                dZ = oz1 + bts$getFiddle(m);
                dist = (dX * dX) + (dY * dY) + (dZ * dZ);
                if (dist < minDistance) {
                    bestX = qx1;
                    bestY = qy1;
                    bestZ = qz1;
                }
            }
        }

        return this.noiseBiomeSource.getNoiseBiome(bestX, bestY, bestZ);
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

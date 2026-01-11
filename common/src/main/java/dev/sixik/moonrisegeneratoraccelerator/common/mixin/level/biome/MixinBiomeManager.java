package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.biome;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.*;

@Mixin(BiomeManager.class)
public class MixinBiomeManager {

    @Final
    @Shadow private BiomeManager.NoiseBiomeSource noiseBiomeSource;
    @Final
    @Shadow
    private long biomeZoomSeed;

    // Константы LCG (из LinearCongruentialGenerator)
    private static final long LCG_MUL = 6364136223846793005L;
    private static final long LCG_ADD = 1442695040888963407L;

    /**
     * @author Sixik
     * @reason Optimized LCG math (inline) and removed Math.floorMod (bitwise AND).
     */
    @Overwrite
    public Holder<Biome> getBiome(BlockPos pos) {
        int x = pos.getX() - 2;
        int y = pos.getY() - 2;
        int z = pos.getZ() - 2;

        // Bit shifts instead of division
        int quartX = x >> 2;
        int quartY = y >> 2;
        int quartZ = z >> 2;

        // Pre-calculate fractions
        double fracX = (double)(x & 3) / 4.0D;
        double fracY = (double)(y & 3) / 4.0D;
        double fracZ = (double)(z & 3) / 4.0D;

        int bestCornerIndex = 0;
        double minDistance = Double.POSITIVE_INFINITY;

        // Unrolled loop or optimized iteration
        // p - это индекс угла (0..7)
        for (int p = 0; p < 8; ++p) {
            // Разворачиваем булевы флаги в битовые операции
            boolean isX = (p & 4) == 0;
            boolean isY = (p & 2) == 0;
            boolean isZ = (p & 1) == 0;

            int cx = isX ? quartX : quartX + 1;
            int cy = isY ? quartY : quartY + 1;
            int cz = isZ ? quartZ : quartZ + 1;

            double offX = isX ? fracX : fracX - 1.0D;
            double offY = isY ? fracY : fracY - 1.0D;
            double offZ = isZ ? fracZ : fracZ - 1.0D;

            // INLINED getFiddledDistance
            // 1. LCG Chain: next(seed, x) -> next(m, y) -> next(m, z) -> ...
            long l = this.biomeZoomSeed;

            // Step 1: Mix Seed + Coords
            long m = l * LCG_MUL + LCG_ADD + cx;
            m = m * LCG_MUL + LCG_ADD + cy;
            m = m * LCG_MUL + LCG_ADD + cz;

            // Step 2: Mix Coords again
            m = m * LCG_MUL + LCG_ADD + cx;
            m = m * LCG_MUL + LCG_ADD + cy;
            m = m * LCG_MUL + LCG_ADD + cz;

            // Step 3: Calculate offsets (Fiddles)
            // Replace Math.floorMod(l >> 24, 1024) with (l >> 24) & 1023
            double fX = bts$getFiddle(m);

            m = m * LCG_MUL + LCG_ADD + l;
            double fY = bts$getFiddle(m);

            m = m * LCG_MUL + LCG_ADD + l;
            double fZ = bts$getFiddle(m);

            // Final Distance
            double dist = bts$sq(offZ + fZ) + bts$sq(offY + fY) + bts$sq(offX + fX);

            if (minDistance > dist) {
                bestCornerIndex = p;
                minDistance = dist;
            }
        }

        int finalX = (bestCornerIndex & 4) == 0 ? quartX : quartX + 1;
        int finalY = (bestCornerIndex & 2) == 0 ? quartY : quartY + 1;
        int finalZ = (bestCornerIndex & 1) == 0 ? quartZ : quartZ + 1;

        return this.noiseBiomeSource.getNoiseBiome(finalX, finalY, finalZ);
    }

    /**
     * Optimized Fiddle calculation.
     * Replaces Math.floorMod(x, 1024) with (x & 1023).
     * Works for negative numbers because 1024 is a power of 2.
     */
    @Unique
    private static double bts$getFiddle(long l) {
        double d = (double)((int)(l >> 24) & 1023) / 1024.0D;
        return (d - 0.5D) * 0.9D;
    }

    @Unique
    private static double bts$sq(double d) {
        return d * d;
    }
}

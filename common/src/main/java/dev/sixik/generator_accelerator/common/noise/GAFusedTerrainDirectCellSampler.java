package dev.sixik.generator_accelerator.common.noise;

/**
 * Pure decision helper for the direct cell-density terrain path. Kept outside
 * mixins so the risky shortcut has unit-testable semantics.
 */
public final class GAFusedTerrainDirectCellSampler {
    public static final int ORE_VEIN_MIN_Y = -60;
    public static final int COPPER_MIN_Y = 0;
    public static final int IRON_MAX_Y = -8;
    public static final int ORE_VEIN_MAX_Y = 50;

    private GAFusedTerrainDirectCellSampler() {
    }

    public static long samplePacked(
            double[] densityValues,
            int defaultBlockId,
            int airBlockId,
            int blockY,
            int cellValueIndex,
            boolean hasOreVeinRule,
            boolean skipOreVeins,
            boolean airForNonSolid
    ) {
        if (densityValues == null) {
            return GAFusedTerrainNoiseChunkAccess.ga$packFallback(
                    GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_REASON_UNAVAILABLE
            );
        }
        if (cellValueIndex < 0 || cellValueIndex >= densityValues.length) {
            return GAFusedTerrainNoiseChunkAccess.ga$packFallback(
                    GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_REASON_OUT_OF_BOUNDS
            );
        }

        double density = densityValues[cellValueIndex];
        if (density > 0.0D) {
            if (hasOreVeinRule && !skipOreVeins && oreVeinCanReplaceAt(blockY)) {
                return GAFusedTerrainNoiseChunkAccess.ga$packFallback(
                        GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_REASON_ORE_VEIN_RANGE
                );
            }
            return GAFusedTerrainNoiseChunkAccess.ga$packFusedTerrain(defaultBlockId, false);
        }

        if (airForNonSolid) {
            return GAFusedTerrainNoiseChunkAccess.ga$packFusedTerrain(airBlockId, false);
        }
        return GAFusedTerrainNoiseChunkAccess.ga$packFallback(
                GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_REASON_NON_SOLID
        );
    }

    public static boolean oreVeinCanReplaceAt(int y) {
        return y >= ORE_VEIN_MIN_Y
                && y <= ORE_VEIN_MAX_Y
                && (y <= IRON_MAX_Y || y >= COPPER_MIN_Y);
    }

    public static boolean cellCanUseDefaultSolid(
            double[] densityValues,
            int minBlockY,
            int cellHeight,
            boolean hasOreVeinRule,
            boolean skipOreVeins
    ) {
        if (densityValues == null || densityValues.length == 0) {
            return false;
        }
        if (hasOreVeinRule && !skipOreVeins) {
            int maxBlockY = minBlockY + cellHeight - 1;
            for (int y = minBlockY; y <= maxBlockY; y++) {
                if (oreVeinCanReplaceAt(y)) {
                    return false;
                }
            }
        }
        for (double density : densityValues) {
            if (density <= 0.0D) {
                return false;
            }
        }
        return true;
    }
}

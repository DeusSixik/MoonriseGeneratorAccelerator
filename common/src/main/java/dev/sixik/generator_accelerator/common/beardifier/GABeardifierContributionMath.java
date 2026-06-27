package dev.sixik.generator_accelerator.common.beardifier;

public final class GABeardifierContributionMath {

    private static final int GA$HALF_SCALED_LIMIT = 12;
    private static final int GA$BURY_DX_LIMIT = 6;
    private static final int GA$BURY_DZ_LIMIT = 6;
    private static final double GA$INV_SIX = 0.16666666666666666D;
    private static final double GA$INV_TWELVE = 0.08333333333333333D;
    private static final float[] GA$BURY_TABLE = ga$buildBuryTable();
    private static final float[] GA$BURY_HALF_SCALED_TABLE = ga$buildHalfScaledTable();

    private GABeardifierContributionMath() {
    }

    public static double bury(int dx, int verticalOffset, int dz) {
        int absVerticalOffset = Math.abs(verticalOffset);
        if (dx >= GA$BURY_DX_LIMIT || absVerticalOffset >= GA$HALF_SCALED_LIMIT || dz >= GA$BURY_DZ_LIMIT) {
            return 0.0D;
        }
        return GA$BURY_TABLE[ga$buryIndex(dx, absVerticalOffset, dz)];
    }

    public static double buryHalfScaled(int dx, int yDistance, int dz) {
        if (dx >= GA$HALF_SCALED_LIMIT || yDistance >= GA$HALF_SCALED_LIMIT || dz >= GA$HALF_SCALED_LIMIT) {
            return 0.0D;
        }
        return GA$BURY_HALF_SCALED_TABLE[ga$halfScaledIndex(dx, yDistance, dz)];
    }

    public static double buryDistance(double x, double y, double z) {
        double distanceSquared = x * x + y * y + z * z;
        if (distanceSquared > 36.0D) {
            return 0.0D;
        }
        return 1.0D - Math.sqrt(distanceSquared) * GA$INV_SIX;
    }

    public static int boxYDistance(int blockY, int groundY, int maxY) {
        return Math.max(0, Math.max(groundY - blockY, blockY - maxY));
    }

    public static int encapsulateYDistance(int blockY, int minY, int maxY) {
        return Math.max(0, Math.max(minY - blockY, blockY - maxY));
    }

    private static float[] ga$buildBuryTable() {
        float[] table = new float[GA$BURY_DX_LIMIT * GA$HALF_SCALED_LIMIT * GA$BURY_DZ_LIMIT];
        for (int dx = 0; dx < GA$BURY_DX_LIMIT; dx++) {
            for (int dy = 0; dy < GA$HALF_SCALED_LIMIT; dy++) {
                for (int dz = 0; dz < GA$BURY_DZ_LIMIT; dz++) {
                    int scaledDistanceSquared = (dx * dx << 2) + dy * dy + (dz * dz << 2);
                    table[ga$buryIndex(dx, dy, dz)] = scaledDistanceSquared > 144
                            ? 0.0F
                            : (float) (1.0D - Math.sqrt((double) scaledDistanceSquared) * GA$INV_TWELVE);
                }
            }
        }
        return table;
    }

    private static float[] ga$buildHalfScaledTable() {
        float[] table = new float[GA$HALF_SCALED_LIMIT * GA$HALF_SCALED_LIMIT * GA$HALF_SCALED_LIMIT];
        for (int dx = 0; dx < GA$HALF_SCALED_LIMIT; dx++) {
            for (int dy = 0; dy < GA$HALF_SCALED_LIMIT; dy++) {
                for (int dz = 0; dz < GA$HALF_SCALED_LIMIT; dz++) {
                    int distanceSquared = dx * dx + dy * dy + dz * dz;
                    table[ga$halfScaledIndex(dx, dy, dz)] = distanceSquared > 144
                            ? 0.0F
                            : (float) (1.0D - Math.sqrt((double) distanceSquared) * GA$INV_TWELVE);
                }
            }
        }
        return table;
    }

    private static int ga$buryIndex(int dx, int dy, int dz) {
        return (dx * GA$HALF_SCALED_LIMIT + dy) * GA$BURY_DZ_LIMIT + dz;
    }

    private static int ga$halfScaledIndex(int dx, int dy, int dz) {
        return (dx * GA$HALF_SCALED_LIMIT + dy) * GA$HALF_SCALED_LIMIT + dz;
    }

}

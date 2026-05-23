package dev.sixik.generator_accelerator.common.beardifier;

public final class GABeardifierContributionMath {

    private GABeardifierContributionMath() {
    }

    public static double bury(int dx, int verticalOffset, int dz) {
        return buryDistance(dx, (double) verticalOffset * 0.5d, dz);
    }

    public static double buryHalfScaled(int dx, int yDistance, int dz) {
        return buryDistance((double) dx * 0.5d, (double) yDistance * 0.5d, (double) dz * 0.5d);
    }

    public static double buryDistance(double x, double y, double z) {
        double distanceSquared = x * x + y * y + z * z;
        if (distanceSquared > 36.0D) {
            return 0.0D;
        }
        return 1.0D - Math.sqrt(distanceSquared) * 0.16666666666666666D;
    }

    public static int boxYDistance(int blockY, int groundY, int maxY) {
        return Math.max(0, Math.max(groundY - blockY, blockY - maxY));
    }

    public static int encapsulateYDistance(int blockY, int minY, int maxY) {
        return Math.max(0, Math.max(minY - blockY, blockY - maxY));
    }

}

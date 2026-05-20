package dev.sixik.generator_accelerator.common.aquifer;

/**
 * Primitive aquifer sample-point grid detached from Minecraft classes.
 */
public final class GAAquiferGrid {
    private final int gridSizeX;
    private final int gridSizeZ;
    private final int minGridX;
    private final int minGridY;
    private final int minGridZ;
    private final int[] sampleX;
    private final int[] sampleY;
    private final int[] sampleZ;

    public GAAquiferGrid(
            int gridSizeX,
            int gridSizeZ,
            int minGridX,
            int minGridY,
            int minGridZ,
            int[] sampleX,
            int[] sampleY,
            int[] sampleZ
    ) {
        this.gridSizeX = gridSizeX;
        this.gridSizeZ = gridSizeZ;
        this.minGridX = minGridX;
        this.minGridY = minGridY;
        this.minGridZ = minGridZ;
        this.sampleX = sampleX;
        this.sampleY = sampleY;
        this.sampleZ = sampleZ;
    }

    public void nearest(int x, int y, int z, GAAquiferNearest out) {
        int gx = (x - 5) >> 4;
        int gy = floorDiv12(y + 1);
        int gz = (z - 5) >> 4;
        int strideY = this.gridSizeZ * this.gridSizeX;
        int strideZ = this.gridSizeX;
        int baseIndexY = (((gy - 1 - this.minGridY) * this.gridSizeZ + (gz - this.minGridZ)) * this.gridSizeX)
                + (gx - this.minGridX);
        int[] xs = this.sampleX;
        int[] ys = this.sampleY;
        int[] zs = this.sampleZ;
        out.reset();
        for (int offX = 0; offX <= 1; offX++) {
            int baseIndexX = baseIndexY + offX;
            for (int offY = -1; offY <= 1; offY++) {
                int baseIndexZ = baseIndexX;
                for (int offZ = 0; offZ <= 1; offZ++) {
                    accept(out, baseIndexZ, xs, ys, zs, x, y, z);
                    baseIndexZ += strideZ;
                }
                baseIndexX += strideY;
            }
        }
    }

    public void nearestColumnBand(int x, int y, int z, GAAquiferColumnBandNearest band, GAAquiferNearest out) {
        int gridY = floorDiv12(y + 1);
        if (!band.matches(x, z, gridY)) {
            fillColumnBand(x, z, gridY, band);
        }
        band.resolve(y, out);
    }

    public int size() {
        return this.sampleX.length;
    }

    public int sampleX(int index) {
        return this.sampleX[index];
    }

    public int sampleY(int index) {
        return this.sampleY[index];
    }

    public int sampleZ(int index) {
        return this.sampleZ[index];
    }

    private void fillColumnBand(int x, int z, int gridY, GAAquiferColumnBandNearest band) {
        int gx = (x - 5) >> 4;
        int gz = (z - 5) >> 4;
        int strideY = this.gridSizeZ * this.gridSizeX;
        int strideZ = this.gridSizeX;
        int baseIndexY = (((gridY - 1 - this.minGridY) * this.gridSizeZ + (gz - this.minGridZ)) * this.gridSizeX)
                + (gx - this.minGridX);
        int[] indices = band.indices;
        int[] sampleY = band.sampleY;
        int[] horizontalDistance = band.horizontalDistance;
        int[] xs = this.sampleX;
        int[] ys = this.sampleY;
        int[] zs = this.sampleZ;
        int candidate = 0;
        for (int offX = 0; offX <= 1; offX++) {
            int baseIndexX = baseIndexY + offX;
            for (int offY = -1; offY <= 1; offY++) {
                int baseIndexZ = baseIndexX;
                for (int offZ = 0; offZ <= 1; offZ++) {
                    candidate = fillCandidate(candidate, baseIndexZ, x, z, xs, ys, zs, indices, sampleY, horizontalDistance);
                    baseIndexZ += strideZ;
                }
                baseIndexX += strideY;
            }
        }
        band.reset(x, z, gridY);
    }

    public static int floorDiv12(int value) {
        return value >= 0 ? value / 12 : -((-value + 11) / 12);
    }

    private static void accept(
            GAAquiferNearest out,
            int index,
            int[] xs,
            int[] ys,
            int[] zs,
            int x,
            int y,
            int z
    ) {
        int dx = xs[index] - x;
        int dy = ys[index] - y;
        int dz = zs[index] - z;
        out.accept(index, dx * dx + dy * dy + dz * dz);
    }

    private static int fillCandidate(
            int candidate,
            int index,
            int x,
            int z,
            int[] xs,
            int[] ys,
            int[] zs,
            int[] indices,
            int[] sampleY,
            int[] horizontalDistance
    ) {
        int dx = xs[index] - x;
        int dz = zs[index] - z;
        indices[candidate] = index;
        sampleY[candidate] = ys[index];
        horizontalDistance[candidate] = dx * dx + dz * dz;
        return candidate + 1;
    }
}

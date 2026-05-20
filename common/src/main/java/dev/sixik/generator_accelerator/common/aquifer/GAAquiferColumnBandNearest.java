package dev.sixik.generator_accelerator.common.aquifer;

/**
 * Reusable nearest scratch for one fixed X/Z column and one aquifer Y band.
 * Candidate indices and horizontal distances stay stable while Y walks inside
 * the same {@code floorDiv12(y + 1)} band.
 */
public final class GAAquiferColumnBandNearest {
    final int[] indices = new int[12];
    final int[] sampleY = new int[12];
    final int[] horizontalDistance = new int[12];

    private int x;
    private int z;
    private int gridY;
    private boolean valid;

    public void invalidate() {
        this.valid = false;
    }

    boolean matches(int x, int z, int gridY) {
        return this.valid && this.x == x && this.z == z && this.gridY == gridY;
    }

    void reset(int x, int z, int gridY) {
        this.x = x;
        this.z = z;
        this.gridY = gridY;
        this.valid = true;
    }

    void resolve(int y, GAAquiferNearest out) {
        out.reset();
        int[] indices = this.indices;
        int[] sampleY = this.sampleY;
        int[] horizontalDistance = this.horizontalDistance;
        for (int i = 0; i < 12; i++) {
            int dy = sampleY[i] - y;
            out.accept(indices[i], horizontalDistance[i] + dy * dy);
        }
    }
}

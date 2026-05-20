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
        int dist1 = Integer.MAX_VALUE;
        int dist2 = Integer.MAX_VALUE;
        int dist3 = Integer.MAX_VALUE;
        int idx1 = 0;
        int idx2 = 0;
        int idx3 = 0;
        int[] indices = this.indices;
        int[] sampleY = this.sampleY;
        int[] horizontalDistance = this.horizontalDistance;
        for (int i = 0; i < 12; i++) {
            int dy = sampleY[i] - y;
            int distance = horizontalDistance[i] + dy * dy;
            if (dist3 >= distance) {
                int index = indices[i];
                if (dist2 >= distance) {
                    dist3 = dist2;
                    idx3 = idx2;
                    if (dist1 >= distance) {
                        dist2 = dist1;
                        idx2 = idx1;
                        dist1 = distance;
                        idx1 = index;
                    } else {
                        dist2 = distance;
                        idx2 = index;
                    }
                } else {
                    dist3 = distance;
                    idx3 = index;
                }
            }
        }
        out.dist1 = dist1;
        out.dist2 = dist2;
        out.dist3 = dist3;
        out.idx1 = idx1;
        out.idx2 = idx2;
        out.idx3 = idx3;
    }
}

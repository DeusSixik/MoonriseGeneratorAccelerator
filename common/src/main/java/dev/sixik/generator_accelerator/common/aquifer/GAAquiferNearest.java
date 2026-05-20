package dev.sixik.generator_accelerator.common.aquifer;

/**
 * Reusable nearest-aquifer scratch. Kept mutable to avoid hot-path allocation.
 */
public final class GAAquiferNearest {
    public int dist1;
    public int dist2;
    public int dist3;
    public int idx1;
    public int idx2;
    public int idx3;

    void reset() {
        this.dist1 = Integer.MAX_VALUE;
        this.dist2 = Integer.MAX_VALUE;
        this.dist3 = Integer.MAX_VALUE;
        this.idx1 = 0;
        this.idx2 = 0;
        this.idx3 = 0;
    }

    void accept(int index, int distance) {
        if (this.dist3 >= distance) {
            if (this.dist2 >= distance) {
                this.dist3 = this.dist2;
                this.idx3 = this.idx2;
                if (this.dist1 >= distance) {
                    this.dist2 = this.dist1;
                    this.idx2 = this.idx1;
                    this.dist1 = distance;
                    this.idx1 = index;
                } else {
                    this.dist2 = distance;
                    this.idx2 = index;
                }
            } else {
                this.dist3 = distance;
                this.idx3 = index;
            }
        }
    }
}

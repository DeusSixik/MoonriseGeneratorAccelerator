package dev.sixik.generator_accelerator.common.beardifier;

public final class GABeardifierCellScratch {
    private int[] pieces = new int[32];
    private int[] junctions = new int[32];

    public int[] pieces(int required) {
        if (required > this.pieces.length) {
            this.pieces = new int[Math.max(required, this.pieces.length << 1)];
        }
        return this.pieces;
    }

    public int[] junctions(int required) {
        if (required > this.junctions.length) {
            this.junctions = new int[Math.max(required, this.junctions.length << 1)];
        }
        return this.junctions;
    }
}

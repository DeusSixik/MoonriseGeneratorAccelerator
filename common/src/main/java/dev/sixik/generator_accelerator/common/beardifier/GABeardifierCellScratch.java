package dev.sixik.generator_accelerator.common.beardifier;

public final class GABeardifierCellScratch {
    private int[] pieces = new int[32];
    private int[] junctions = new int[32];
    private int[] pieceOrder = new int[32];
    private final int[] terrainCounts = new int[4];
    private int[] pieceDx = new int[32];
    private int[] pieceDz = new int[32];
    private int[] junctionDx = new int[32];
    private int[] junctionDz = new int[32];

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

    public int[] pieceOrder(int required) {
        if (required > this.pieceOrder.length) {
            this.pieceOrder = new int[Math.max(required, this.pieceOrder.length << 1)];
        }
        return this.pieceOrder;
    }

    public int[] terrainCounts() {
        return this.terrainCounts;
    }

    public int[] pieceDx(int required) {
        if (required > this.pieceDx.length) {
            this.pieceDx = new int[Math.max(required, this.pieceDx.length << 1)];
        }
        return this.pieceDx;
    }

    public int[] pieceDz(int required) {
        if (required > this.pieceDz.length) {
            this.pieceDz = new int[Math.max(required, this.pieceDz.length << 1)];
        }
        return this.pieceDz;
    }

    public int[] junctionDx(int required) {
        if (required > this.junctionDx.length) {
            this.junctionDx = new int[Math.max(required, this.junctionDx.length << 1)];
        }
        return this.junctionDx;
    }

    public int[] junctionDz(int required) {
        if (required > this.junctionDz.length) {
            this.junctionDz = new int[Math.max(required, this.junctionDz.length << 1)];
        }
        return this.junctionDz;
    }
}

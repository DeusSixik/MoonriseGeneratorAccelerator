package dev.sixik.generator_accelerator.common.beardifier;

public final class GABeardifierCellScratch {
    int[] buryPieces = new int[16];
    int[] thinPieces = new int[16];
    int[] boxPieces = new int[16];
    int[] encapsulatePieces = new int[16];
    int[] junctions = new int[32];
    int buryCount;
    int thinCount;
    int boxCount;
    int encapsulateCount;
    int junctionCount;

    private int[] seenStamps;
    private int collectStamp;

    void beginCollect() {
        this.buryCount = 0;
        this.thinCount = 0;
        this.boxCount = 0;
        this.encapsulateCount = 0;
        this.junctionCount = 0;
    }

    void addPiece(int index, byte kind) {
        switch (kind) {
            case GABeardifierKernel.KIND_BURY -> {
                this.buryPieces = ensure(this.buryPieces, this.buryCount + 1);
                this.buryPieces[this.buryCount++] = index;
            }
            case GABeardifierKernel.KIND_BEARD_THIN -> {
                this.thinPieces = ensure(this.thinPieces, this.thinCount + 1);
                this.thinPieces[this.thinCount++] = index;
            }
            case GABeardifierKernel.KIND_BEARD_BOX -> {
                this.boxPieces = ensure(this.boxPieces, this.boxCount + 1);
                this.boxPieces[this.boxCount++] = index;
            }
            case GABeardifierKernel.KIND_ENCAPSULATE -> {
                this.encapsulatePieces = ensure(this.encapsulatePieces, this.encapsulateCount + 1);
                this.encapsulatePieces[this.encapsulateCount++] = index;
            }
            default -> {
            }
        }
    }

    void addJunction(int index) {
        this.junctions = ensure(this.junctions, this.junctionCount + 1);
        this.junctions[this.junctionCount++] = index;
    }

    void nextCollectStamp(int required) {
        if (required <= 0) {
            return;
        }
        if (this.seenStamps == null || this.seenStamps.length < required) {
            this.seenStamps = new int[required];
            this.collectStamp = 1;
            return;
        }
        int next = this.collectStamp + 1;
        if (next == 0) {
            java.util.Arrays.fill(this.seenStamps, 0);
            next = 1;
        }
        this.collectStamp = next;
    }

    boolean markSeen(int contributorId) {
        int[] stamps = this.seenStamps;
        int stamp = this.collectStamp;
        if (stamps[contributorId] == stamp) {
            return false;
        }
        stamps[contributorId] = stamp;
        return true;
    }

    boolean empty() {
        return this.buryCount == 0
                && this.thinCount == 0
                && this.boxCount == 0
                && this.encapsulateCount == 0
                && this.junctionCount == 0;
    }

    private static int[] ensure(int[] array, int required) {
        if (required > array.length) {
            int newLength = array.length;
            while (newLength < required) {
                newLength <<= 1;
            }
            return new int[newLength];
        }
        return array;
    }
}

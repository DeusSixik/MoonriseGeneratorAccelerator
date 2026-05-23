package dev.sixik.generator_accelerator.common.beardifier;

public final class GABeardifierTerrainRanges {

    private GABeardifierTerrainRanges() {
    }

    public static void fillEnds(int buryCount, int thinCount, int boxCount, int encapsulateCount, int[] endsOut) {
        endsOut[0] = buryCount;
        endsOut[1] = buryCount + thinCount;
        endsOut[2] = buryCount + thinCount + boxCount;
        endsOut[3] = buryCount + thinCount + boxCount + encapsulateCount;
    }
}

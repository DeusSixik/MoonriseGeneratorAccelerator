package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

/**
 * Helpers for optional spline search accelerators.
 *
 * <p>The current LUT path is exact: the table only predicts a likely segment for the
 * interior coordinate, then a tiny fix-up loop walks to the true segment before the
 * generated code evaluates the cubic interpolation for that segment.
 */
public final class DfcSplineSupport {

    public static final int NOT_FOUND = -1;

    private DfcSplineSupport() {
    }

    public static SegmentLut buildSegmentLut(float[] locations, int bucketCount) {
        if (locations == null || locations.length < 2) {
            throw new IllegalArgumentException("locations must have at least 2 points");
        }
        int clampedBuckets = Math.max(8, bucketCount);
        float minLocation = locations[0];
        float maxLocation = locations[locations.length - 1];
        float span = maxLocation - minLocation;
        if (!(span > 0.0f)) {
            throw new IllegalArgumentException("locations must have positive span");
        }

        int[] segments = new int[clampedBuckets];
        float bucketWidth = span / clampedBuckets;
        int segment = 0;
        int lastSegment = locations.length - 2;
        for (int bucket = 0; bucket < clampedBuckets; bucket++) {
            float bucketStart = minLocation + bucketWidth * bucket;
            while (segment < lastSegment && bucketStart >= locations[segment + 1]) {
                segment++;
            }
            segments[bucket] = segment;
        }
        return new SegmentLut(minLocation, clampedBuckets / span, locations.clone(), segments);
    }

    public static int selectSegment(SegmentLut lut, float coordinate) {
        int[] segments = lut.segments();
        int bucket = (int) ((coordinate - lut.minLocation()) * lut.bucketScale());
        if (bucket < 0) {
            bucket = 0;
        } else if (bucket >= segments.length) {
            bucket = segments.length - 1;
        }

        int segment = segments[bucket];
        float[] locations = lut.locations();
        int lastSegment = locations.length - 2;
        while (segment > 0 && coordinate < locations[segment]) {
            segment--;
        }
        while (segment < lastSegment && coordinate >= locations[segment + 1]) {
            segment++;
        }
        return segment;
    }

    public static int selectSegmentBinary(float[] locations, float coordinate) {
        if (locations == null || locations.length < 2) {
            return NOT_FOUND;
        }

        int segmentCount = locations.length - 1;
        if (coordinate < locations[0] || coordinate >= locations[segmentCount]) {
            return NOT_FOUND;
        }

        int lo = 0;
        int hi = segmentCount - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (coordinate < locations[mid + 1]) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    public record SegmentLut(float minLocation, float bucketScale, float[] locations, int[] segments) {
    }
}

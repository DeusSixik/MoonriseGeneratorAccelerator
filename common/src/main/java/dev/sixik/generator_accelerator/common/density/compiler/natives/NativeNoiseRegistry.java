package dev.sixik.generator_accelerator.common.density.compiler.natives;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import dev.sixik.generator_accelerator.common.density.mixin.noise.ImprovedNoiseAccessor;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

import java.lang.ref.Cleaner;
import java.util.Arrays;
import java.util.List;

/**
 * Builds and owns opaque native handles for {@link NoiseSpec} / {@link BlendedNoiseSpec} data.
 * Handles are stored in {@code NativeNoiseRegistry.HandleSet} on each compiled instance (see
 * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction}).
 */
public final class NativeNoiseRegistry {
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final double[] EMPTY_DOUBLES = new double[0];

    private NativeNoiseRegistry() {}

    /**
     * @return handle set of length {@code noiseSpecCount + blendedNoiseSpecCount}; entries are
     *         {@code 0} when natives are unavailable or allocation fails.
     */
    public static HandleSet buildHandleSet(List<NoiseSpec> noiseSpecs, List<BlendedNoiseSpec> blendedSpecs) {
        int nn = noiseSpecs.size();
        int nb = blendedSpecs.size();
        long[] out = new long[nn + nb];
        if (!CodegenNativeNoise.enabled() || !DfcNativeBridge.isAvailable()) {
            return new HandleSet(out, nn);
        }
        for (int i = 0; i < nn; i++) {
            out[i] = allocNormal(noiseSpecs.get(i));
        }
        for (int j = 0; j < nb; j++) {
            out[nn + j] = allocBlended(blendedSpecs.get(j));
        }
        return new HandleSet(out, nn);
    }

    /**
     * Frees all non-zero entries: first {@code noiseSpecCount} as normal stacks, remaining as blended.
     */
    public static void releaseAllTyped(long[] handles, int noiseSpecCount) {
        if (handles == null || !DfcNativeBridge.isAvailable()) {
            return;
        }
        for (int i = 0; i < handles.length; i++) {
            long h = handles[i];
            if (h == 0L) continue;
            if (i < noiseSpecCount) {
                DfcNativeBridge.releaseNormalNoiseStack(h);
            } else {
                DfcNativeBridge.releaseBlendedSpec(h);
            }
        }
    }

    public static void clearAll() {
        // Do not close live handles here. Generated density-function classes copy handles into
        // final long fields, so explicit lifecycle frees can leave still-live compiled functions
        // with dangling native pointers. The Cleaner releases native memory when the owning
        // HandleSet becomes unreachable with its compiled density function.
    }

    public static final class HandleSet implements AutoCloseable {
        private static final Cleaner CLEANER = Cleaner.create();

        private final long[] handles;
        private final State state;
        private final Cleaner.Cleanable cleanable;
        private volatile boolean closed;

        private HandleSet(long[] handles, int noiseSpecCount) {
            this.handles = handles == null ? new long[0] : handles;
            this.state = new State(this.handles, noiseSpecCount);
            this.cleanable = CLEANER.register(this, this.state);
        }

        public long handle(int index) {
            return index >= 0 && index < this.handles.length ? this.handles[index] : 0L;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.cleanable.clean();
        }
    }

    private record State(long[] handles, int noiseSpecCount) implements Runnable {
        @Override
        public void run() {
            releaseAllTyped(this.handles, this.noiseSpecCount);
            if (this.handles != null) {
                Arrays.fill(this.handles, 0L);
            }
        }
    }

    private static long allocNormal(NoiseSpec spec) {
        var first = spec.first();
        var second = spec.second();
        int n0 = first.activeOctaves().length;
        int n1 = second.activeOctaves().length;
        byte[] p0 = flattenPerms(first.activeOctaves(), n0);
        byte[] p1 = flattenPerms(second.activeOctaves(), n1);
        double[] o0 = flattenOrigins(first.activeOctaves(), n0);
        double[] o1 = flattenOrigins(second.activeOctaves(), n1);
        if ((n0 > 0 && (p0 == null || o0 == null)) || (n1 > 0 && (p1 == null || o1 == null))) {
            return 0L;
        }
        try {
            return DfcNativeBridge.allocNormalNoiseStack(
                    spec.valueFactor(),
                    n0,
                    first.inputCoordScale(),
                    first.inputFactors(),
                    first.ampValueFactors(),
                    p0,
                    o0,
                    n1,
                    second.inputCoordScale(),
                    second.inputFactors(),
                    second.ampValueFactors(),
                    p1,
                    o1);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static byte[] flattenPerms(ImprovedNoise[] octaves, int n) {
        if (n == 0) {
            return EMPTY_BYTES;
        }
        byte[] out = new byte[n * 256];
        for (int i = 0; i < n; i++) {
            ImprovedNoiseAccessor acc = (ImprovedNoiseAccessor) (Object) octaves[i];
            byte[] p = acc.dfc$getPermutation();
            if (p == null || p.length < 256) {
                return null;
            }
            System.arraycopy(p, 0, out, i * 256, 256);
        }
        return out;
    }

    private static double[] flattenOrigins(ImprovedNoise[] octaves, int n) {
        if (n == 0) {
            return EMPTY_DOUBLES;
        }
        double[] o = new double[n * 3];
        for (int i = 0; i < n; i++) {
            ImprovedNoiseAccessor acc = (ImprovedNoiseAccessor) (Object) octaves[i];
            o[i * 3] = acc.dfc$getXo();
            o[i * 3 + 1] = acc.dfc$getYo();
            o[i * 3 + 2] = acc.dfc$getZo();
        }
        return o;
    }

    private static long allocBlended(BlendedNoiseSpec s) {
        double[] d6 = new double[] {
            s.xzMultiplier(),
            s.yMultiplier(),
            s.xzFactor(),
            s.yFactor(),
            s.smearScaleMultiplier(),
            s.maxValue()
        };
        byte[] mp = new byte[8 * 256];
        byte[] np = new byte[16 * 256];
        byte[] xp = new byte[16 * 256];
        double[] mo = new double[8 * 3];
        double[] no = new double[16 * 3];
        double[] xo = new double[16 * 3];
        byte[] mpr = new byte[8];
        byte[] npr = new byte[16];
        byte[] xpr = new byte[16];
        if (!fillBlendedSection(s.mainOctaves(), mp, mo, mpr, 8)
                || !fillBlendedSection(s.minLimitOctaves(), np, no, npr, 16)
                || !fillBlendedSection(s.maxLimitOctaves(), xp, xo, xpr, 16)) {
            return 0L;
        }
        try {
            return DfcNativeBridge.allocBlendedSpec(d6, mp, mo, np, no, xp, xo, mpr, npr, xpr);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static boolean fillBlendedSection(ImprovedNoise[] oct, byte[] permFlat, double[] origFlat,
                                              byte[] present, int max) {
        for (int i = 0; i < max; i++) {
            ImprovedNoise n = oct[i];
            if (n == null) {
                present[i] = 0;
                continue;
            }
            present[i] = 1;
            ImprovedNoiseAccessor acc = (ImprovedNoiseAccessor) (Object) n;
            byte[] p = acc.dfc$getPermutation();
            if (p == null || p.length < 256) {
                return false;
            }
            System.arraycopy(p, 0, permFlat, i * 256, 256);
            origFlat[i * 3] = acc.dfc$getXo();
            origFlat[i * 3 + 1] = acc.dfc$getYo();
            origFlat[i * 3 + 2] = acc.dfc$getZo();
        }
        return true;
    }
}

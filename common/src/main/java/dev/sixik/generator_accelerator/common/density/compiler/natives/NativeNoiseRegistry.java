package dev.sixik.generator_accelerator.common.density.compiler.natives;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;

import java.util.Arrays;
import java.util.List;

/**
 * Java-only native handle registry. It preserves the compiled-function constructor shape while
 * guaranteeing no JNI descriptors or off-heap allocations are created.
 */
public final class NativeNoiseRegistry {
    private NativeNoiseRegistry() {}

    /**
     * @return zero-filled handle set; native density kernels are quarantined.
     */
    public static HandleSet buildHandleSet(List<NoiseSpec> noiseSpecs, List<BlendedNoiseSpec> blendedSpecs) {
        int nn = noiseSpecs == null ? 0 : noiseSpecs.size();
        int nb = blendedSpecs == null ? 0 : blendedSpecs.size();
        return new HandleSet(new long[nn + nb]);
    }

    /** Clears any legacy non-zero handle array defensively without calling native release hooks. */
    public static void releaseAllTyped(long[] handles, int noiseSpecCount) {
        if (handles != null) {
            Arrays.fill(handles, 0L);
        }
    }

    public static void clearAll() {
        // No native descriptors are allocated in the Java-only runtime.
    }

    public static final class HandleSet implements AutoCloseable {
        private final long[] handles;
        private volatile boolean closed;

        private HandleSet(long[] handles) {
            this.handles = handles == null ? new long[0] : handles;
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
            Arrays.fill(this.handles, 0L);
        }
    }
}

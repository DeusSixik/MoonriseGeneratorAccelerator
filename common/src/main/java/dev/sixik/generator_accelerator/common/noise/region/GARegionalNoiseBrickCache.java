package dev.sixik.generator_accelerator.common.noise.region;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpecCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.runtime.Runtime;
import dev.sixik.generator_accelerator.common.noise.GAUnifiedRegionPacketAccess;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exact immutable regional brick cache for reusable per-octave noise contributions.
 */
public final class GARegionalNoiseBrickCache {
    public static final int REGION_CHUNK_SHIFT = 2;
    public static final int REGION_BLOCK_SHIFT = REGION_CHUNK_SHIFT + 4;
    public static final int REGION_BLOCK_SIZE = 1 << REGION_BLOCK_SHIFT;

    private static final int BRICK_XZ = 4;
    private static final int BRICK_Y = 8;
    private static final int MAIN_BLENDED_OCTAVES = 8;
    private static final int LIMIT_BLENDED_OCTAVES = 16;

    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty(
            "ga.noise.regionalNoiseBrickCache.enabled",
            "false"
    ));
    private static final int MAX_ENTRIES = Math.max(
            32,
            Integer.getInteger("ga.noise.regionalNoiseBrickCache.maxEntries", 384)
    );
    private static final ThreadLocal<Boolean> BUILD_GUARD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final ConcurrentHashMap<BrickKey, Future<BrickEntry>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<EvictionEntry> INSERTION_ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger ENTRY_COUNT = new AtomicInteger();

    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong BUILDS = new AtomicLong();
    private static final AtomicLong EVICTIONS = new AtomicLong();
    private static final AtomicLong WAITS = new AtomicLong();

    private GARegionalNoiseBrickCache() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static View view(int chunkMinX, int chunkMinZ) {
        if (!ENABLED) {
            return null;
        }
        return new View(chunkMinX >> REGION_BLOCK_SHIFT, chunkMinZ >> REGION_BLOCK_SHIFT);
    }

    public static double samplePlainNormalNoise(
            NormalNoise noise,
            DensityFunction.FunctionContext context,
            double xzScale,
            double yScale
    ) {
        if (noise == null) {
            return 0.0D;
        }
        if (!ENABLED) {
            return noise.getValue(
                    context.blockX() * xzScale,
                    context.blockY() * yScale,
                    context.blockZ() * xzScale
            );
        }
        if (context instanceof GAUnifiedRegionPacketAccess access && access.ga$unifiedRegionPacket() != null) {
            View view = access.ga$unifiedRegionPacket().noiseBrickView();
            if (view != null) {
                return view.sampleNormalNoise(noise, context, xzScale, yScale);
            }
        }
        return noise.getValue(
                context.blockX() * xzScale,
                context.blockY() * yScale,
                context.blockZ() * xzScale
        );
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("maxEntries", MAX_ENTRIES);
        out.put("entries", ENTRY_COUNT.get());
        out.put("hits", HITS.get());
        out.put("misses", MISSES.get());
        out.put("builds", BUILDS.get());
        out.put("evictions", EVICTIONS.get());
        out.put("waits", WAITS.get());

        long approxBytes = 0L;
        Map<String, Object> byKind = new LinkedHashMap<>();
        long normal3d = 0L;
        long blended3d = 0L;
        for (Future<BrickEntry> future : CACHE.values()) {
            if (!future.isDone()) {
                continue;
            }
            try {
                BrickEntry entry = future.get();
                long bytes = entry.approximateHeapBytes();
                approxBytes += bytes;
                if (entry.kind == Kind.NORMAL_3D) {
                    normal3d += bytes;
                } else if (entry.kind == Kind.BLENDED_3D) {
                    blended3d += bytes;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ignored) {
            }
        }
        byKind.put("normal3dApproximateHeapBytes", normal3d);
        byKind.put("blended3dApproximateHeapBytes", blended3d);
        out.put("byKind", byKind);
        out.put("approximateHeapBytes", approxBytes);
        return out;
    }

    static void clearForTests() {
        CACHE.clear();
        INSERTION_ORDER.clear();
        ENTRY_COUNT.set(0);
        HITS.set(0L);
        MISSES.set(0L);
        BUILDS.set(0L);
        EVICTIONS.set(0L);
        WAITS.set(0L);
    }

    public static final class View {
        private final int regionX;
        private final int regionZ;

        private View(int regionX, int regionZ) {
            this.regionX = regionX;
            this.regionZ = regionZ;
        }

        public double sampleNormalNoise(
                NormalNoise noise,
                DensityFunction.FunctionContext context,
                double xzScale,
                double yScale
        ) {
            if (!ENABLED || noise == null || context == null || BUILD_GUARD.get()) {
                return noise == null
                        ? 0.0D
                        : noise.getValue(
                                context.blockX() * xzScale,
                                context.blockY() * yScale,
                                context.blockZ() * xzScale
                        );
            }
            int blockX = context.blockX();
            int blockY = context.blockY();
            int blockZ = context.blockZ();
            if (!this.contains(blockX, blockZ)) {
                return noise.getValue(blockX * xzScale, blockY * yScale, blockZ * xzScale);
            }

            NoiseSpec spec = NoiseSpecCache.specFor(noise);
            if (spec == null) {
                return noise.getValue(blockX * xzScale, blockY * yScale, blockZ * xzScale);
            }

            int brickMinX = floorTo(blockX, BRICK_XZ);
            int brickMinY = floorTo(blockY, BRICK_Y);
            int brickMinZ = floorTo(blockZ, BRICK_XZ);
            BrickKey key = new BrickKey(
                    noise,
                    Kind.NORMAL_3D,
                    this.regionX,
                    this.regionZ,
                    brickMinX,
                    brickMinY,
                    brickMinZ,
                    Double.doubleToLongBits(xzScale),
                    Double.doubleToLongBits(yScale)
            );
            BrickEntry entry = values(key, () -> buildNormalBrick(spec, brickMinX, brickMinY, brickMinZ, xzScale, yScale));
            int sampleIndex = index3d(blockX - brickMinX, blockY - brickMinY, blockZ - brickMinZ);
            double first = sumGroup(entry, 0, entry.group0Count, sampleIndex);
            double second = sumGroup(entry, entry.group0Count, entry.group1Count, sampleIndex);
            return (first + second) * entry.outputFactor;
        }

        public double sampleBlendedNoise(BlendedNoise noise, DensityFunction.FunctionContext context) {
            if (!ENABLED || noise == null || context == null || BUILD_GUARD.get()) {
                return Double.NaN;
            }
            int blockX = context.blockX();
            int blockY = context.blockY();
            int blockZ = context.blockZ();
            if (!this.contains(blockX, blockZ)) {
                return Double.NaN;
            }

            BlendedNoiseSpec spec = BlendedNoiseSpecCache.specFor(noise);
            if (spec == null) {
                return Double.NaN;
            }

            int brickMinX = floorTo(blockX, BRICK_XZ);
            int brickMinY = floorTo(blockY, BRICK_Y);
            int brickMinZ = floorTo(blockZ, BRICK_XZ);
            BrickKey key = new BrickKey(noise, Kind.BLENDED_3D, this.regionX, this.regionZ, brickMinX, brickMinY, brickMinZ, 0L, 0L);
            BrickEntry entry = values(key, () -> buildBlendedBrick(spec, brickMinX, brickMinY, brickMinZ));
            int sampleIndex = index3d(blockX - brickMinX, blockY - brickMinY, blockZ - brickMinZ);

            double main = sumGroup(entry, 0, entry.group0Count, sampleIndex);
            double q = (main / 10.0D + 1.0D) * 0.5D;

            double min = q >= 1.0D ? 0.0D : sumGroup(entry, entry.group0Count, entry.group1Count, sampleIndex);
            double max = q <= 0.0D ? 0.0D : sumGroup(entry, entry.group0Count + entry.group1Count, entry.group2Count, sampleIndex);
            return Mth.clampedLerp(min * 0.001953125D, max * 0.001953125D, q) * 0.0078125D;
        }

        private boolean contains(int blockX, int blockZ) {
            return (blockX >> REGION_BLOCK_SHIFT) == this.regionX
                    && (blockZ >> REGION_BLOCK_SHIFT) == this.regionZ;
        }
    }

    private static BrickEntry buildNormalBrick(
            NoiseSpec spec,
            int brickMinX,
            int brickMinY,
            int brickMinZ,
            double xzScale,
            double yScale
    ) {
        return guardedBuild(() -> {
            int sampleCount = BRICK_XZ * BRICK_Y * BRICK_XZ;
            int firstCount = spec.first().activeOctaves().length;
            int secondCount = spec.second().activeOctaves().length;
            double[] contributions = new double[(firstCount + secondCount) * sampleCount];

            for (int localY = 0; localY < BRICK_Y; localY++) {
                int blockY = brickMinY + localY;
                for (int localZ = 0; localZ < BRICK_XZ; localZ++) {
                    int blockZ = brickMinZ + localZ;
                    for (int localX = 0; localX < BRICK_XZ; localX++) {
                        int blockX = brickMinX + localX;
                        int sampleIndex = index3d(localX, localY, localZ);

                        double x = blockX * xzScale;
                        double y = blockY * yScale;
                        double z = blockZ * xzScale;

                        writePerlinBranch(spec.first(), contributions, 0, sampleCount, sampleIndex, x, y, z);
                        writePerlinBranch(
                                spec.second(),
                                contributions,
                                firstCount,
                                sampleCount,
                                sampleIndex,
                                x * spec.second().inputCoordScale(),
                                y * spec.second().inputCoordScale(),
                                z * spec.second().inputCoordScale()
                        );
                    }
                }
            }

            return new BrickEntry(Kind.NORMAL_3D, sampleCount, firstCount, secondCount, 0, spec.valueFactor(), contributions);
        });
    }

    private static BrickEntry buildBlendedBrick(
            BlendedNoiseSpec spec,
            int brickMinX,
            int brickMinY,
            int brickMinZ
    ) {
        return guardedBuild(() -> {
            int sampleCount = BRICK_XZ * BRICK_Y * BRICK_XZ;
            double[] contributions = new double[(MAIN_BLENDED_OCTAVES + LIMIT_BLENDED_OCTAVES + LIMIT_BLENDED_OCTAVES) * sampleCount];

            for (int localY = 0; localY < BRICK_Y; localY++) {
                int blockY = brickMinY + localY;
                for (int localZ = 0; localZ < BRICK_XZ; localZ++) {
                    int blockZ = brickMinZ + localZ;
                    for (int localX = 0; localX < BRICK_XZ; localX++) {
                        int blockX = brickMinX + localX;
                        int sampleIndex = index3d(localX, localY, localZ);
                        writeBlendedContributions(spec, contributions, sampleCount, sampleIndex, blockX, blockY, blockZ);
                    }
                }
            }

            return new BrickEntry(
                    Kind.BLENDED_3D,
                    sampleCount,
                    MAIN_BLENDED_OCTAVES,
                    LIMIT_BLENDED_OCTAVES,
                    LIMIT_BLENDED_OCTAVES,
                    1.0D,
                    contributions
            );
        });
    }

    private static void writePerlinBranch(
            NoiseSpec.PerlinSpec branch,
            double[] contributions,
            int octaveBase,
            int sampleCount,
            int sampleIndex,
            double x,
            double y,
            double z
    ) {
        ImprovedNoise[] octaves = branch.activeOctaves();
        double[] inputFactors = branch.inputFactors();
        double[] ampValueFactors = branch.ampValueFactors();
        for (int i = 0; i < octaves.length; i++) {
            ImprovedNoise octave = octaves[i];
            double factor = inputFactors[i];
            contributions[(octaveBase + i) * sampleCount + sampleIndex] = ampValueFactors[i] * octave.noise(
                    Runtime.wrapAxis(x * factor),
                    Runtime.wrapAxis(y * factor),
                    Runtime.wrapAxis(z * factor)
            );
        }
    }

    private static void writeBlendedContributions(
            BlendedNoiseSpec spec,
            double[] contributions,
            int sampleCount,
            int sampleIndex,
            int blockX,
            int blockY,
            int blockZ
    ) {
        double x = blockX * spec.xzMultiplier();
        double y = blockY * spec.yMultiplier();
        double z = blockZ * spec.xzMultiplier();

        double mainX = x / spec.xzFactor();
        double mainY = y / spec.yFactor();
        double mainZ = z / spec.xzFactor();
        double smearScale = spec.yMultiplier() * spec.smearScaleMultiplier();
        double mainNoiseScaleY = smearScale / spec.yFactor();

        double freq = 1.0D;
        for (int i = 0; i < MAIN_BLENDED_OCTAVES; i++) {
            ImprovedNoise octave = spec.mainOctaves()[i];
            if (octave != null) {
                contributions[i * sampleCount + sampleIndex] = (1.0D / freq) * octave.noise(
                        Runtime.wrapAxis(mainX * freq),
                        Runtime.wrapAxis(mainY * freq),
                        Runtime.wrapAxis(mainZ * freq),
                        mainNoiseScaleY * freq,
                        mainY * freq
                );
            }
            freq *= 0.5D;
        }

        freq = 1.0D;
        int minBase = MAIN_BLENDED_OCTAVES;
        int maxBase = MAIN_BLENDED_OCTAVES + LIMIT_BLENDED_OCTAVES;
        for (int i = 0; i < LIMIT_BLENDED_OCTAVES; i++) {
            ImprovedNoise minOctave = spec.minLimitOctaves()[i];
            if (minOctave != null) {
                contributions[(minBase + i) * sampleCount + sampleIndex] = (1.0D / freq) * minOctave.noise(
                        Runtime.wrapAxis(x * freq),
                        Runtime.wrapAxis(y * freq),
                        Runtime.wrapAxis(z * freq),
                        smearScale * freq,
                        y * freq
                );
            }

            ImprovedNoise maxOctave = spec.maxLimitOctaves()[i];
            if (maxOctave != null) {
                contributions[(maxBase + i) * sampleCount + sampleIndex] = (1.0D / freq) * maxOctave.noise(
                        Runtime.wrapAxis(x * freq),
                        Runtime.wrapAxis(y * freq),
                        Runtime.wrapAxis(z * freq),
                        smearScale * freq,
                        y * freq
                );
            }
            freq *= 0.5D;
        }
    }

    private static double sumGroup(BrickEntry entry, int groupOffset, int groupCount, int sampleIndex) {
        double sum = 0.0D;
        int sampleCount = entry.sampleCount;
        for (int i = 0; i < groupCount; i++) {
            sum += entry.contributions[(groupOffset + i) * sampleCount + sampleIndex];
        }
        return sum;
    }

    private static <T> T guardedBuild(java.util.concurrent.Callable<T> callable) {
        boolean previous = BUILD_GUARD.get();
        BUILD_GUARD.set(Boolean.TRUE);
        try {
            return callable.call();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception exception) {
            throw new IllegalStateException("regional noise brick build failed", exception);
        } finally {
            BUILD_GUARD.set(previous);
        }
    }

    private static BrickEntry values(BrickKey key, java.util.function.Supplier<BrickEntry> builder) {
        Future<BrickEntry> future = CACHE.get(key);
        if (future == null) {
            MISSES.incrementAndGet();
            FutureTask<BrickEntry> task = new FutureTask<>(() -> {
                BUILDS.incrementAndGet();
                return builder.get();
            });
            Future<BrickEntry> existing = CACHE.putIfAbsent(key, task);
            if (existing == null) {
                future = task;
                INSERTION_ORDER.offer(new EvictionEntry(key, task));
                ENTRY_COUNT.incrementAndGet();
                task.run();
                evictIfNeeded();
            } else {
                future = existing;
            }
        } else {
            HITS.incrementAndGet();
        }
        try {
            WAITS.incrementAndGet();
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (CACHE.remove(key, future)) {
                ENTRY_COUNT.decrementAndGet();
            }
            throw new IllegalStateException("regional noise brick interrupted", interrupted);
        } catch (ExecutionException failure) {
            if (CACHE.remove(key, future)) {
                ENTRY_COUNT.decrementAndGet();
            }
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("regional noise brick failed", cause);
        }
    }

    private static void evictIfNeeded() {
        while (ENTRY_COUNT.get() > MAX_ENTRIES) {
            EvictionEntry oldest = INSERTION_ORDER.poll();
            if (oldest == null) {
                return;
            }
            if (CACHE.remove(oldest.key(), oldest.future())) {
                ENTRY_COUNT.decrementAndGet();
                EVICTIONS.incrementAndGet();
            }
        }
    }

    private static int floorTo(int value, int size) {
        return Mth.floor((double) value / (double) size) * size;
    }

    private static int index3d(int localX, int localY, int localZ) {
        return (localY * BRICK_XZ * BRICK_XZ) + (localZ * BRICK_XZ) + localX;
    }

    private enum Kind {
        NORMAL_3D,
        BLENDED_3D
    }

    private static final class BrickEntry {
        private final Kind kind;
        private final int sampleCount;
        private final int group0Count;
        private final int group1Count;
        private final int group2Count;
        private final double outputFactor;
        private final double[] contributions;

        private BrickEntry(
                Kind kind,
                int sampleCount,
                int group0Count,
                int group1Count,
                int group2Count,
                double outputFactor,
                double[] contributions
        ) {
            this.kind = kind;
            this.sampleCount = sampleCount;
            this.group0Count = group0Count;
            this.group1Count = group1Count;
            this.group2Count = group2Count;
            this.outputFactor = outputFactor;
            this.contributions = contributions;
        }

        private long approximateHeapBytes() {
            return 32L + (long) this.contributions.length * Double.BYTES;
        }
    }

    private record EvictionEntry(BrickKey key, Future<BrickEntry> future) {
    }

    private static final class BrickKey {
        private final Object owner;
        private final Kind kind;
        private final int regionX;
        private final int regionZ;
        private final int brickMinX;
        private final int brickMinY;
        private final int brickMinZ;
        private final long paramA;
        private final long paramB;
        private final int hash;

        private BrickKey(
                Object owner,
                Kind kind,
                int regionX,
                int regionZ,
                int brickMinX,
                int brickMinY,
                int brickMinZ,
                long paramA,
                long paramB
        ) {
            this.owner = owner;
            this.kind = kind;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.brickMinX = brickMinX;
            this.brickMinY = brickMinY;
            this.brickMinZ = brickMinZ;
            this.paramA = paramA;
            this.paramB = paramB;

            int result = System.identityHashCode(owner);
            result = 31 * result + kind.hashCode();
            result = 31 * result + regionX;
            result = 31 * result + regionZ;
            result = 31 * result + brickMinX;
            result = 31 * result + brickMinY;
            result = 31 * result + brickMinZ;
            result = 31 * result + Long.hashCode(paramA);
            result = 31 * result + Long.hashCode(paramB);
            this.hash = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof BrickKey that)) {
                return false;
            }
            return this.owner == that.owner
                    && this.kind == that.kind
                    && this.regionX == that.regionX
                    && this.regionZ == that.regionZ
                    && this.brickMinX == that.brickMinX
                    && this.brickMinY == that.brickMinY
                    && this.brickMinZ == that.brickMinZ
                    && this.paramA == that.paramA
                    && this.paramB == that.paramB;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}

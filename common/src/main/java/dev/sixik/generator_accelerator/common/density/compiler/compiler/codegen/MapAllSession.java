package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-call memoizing visitor wrapper installed by {@link CompiledDensityFunction#mapAll}
 * to deduplicate work across the (typically deep) tree of compiled density functions
 * that {@code NoiseChunk} traverses on every chunk init.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code NoiseChunk}'s constructor calls {@code router.mapAll(this::wrap)} which
 * descends into every {@link CompiledDensityFunction} in the wired router. Two things
 * make the naive per-node walk expensive:
 *
 * <ul>
 *   <li><b>Sibling sharing.</b> A single noise router shares many sub-trees by identity
 *       — e.g. {@code shift_x}/{@code shift_z} markers feed every shifted-noise field
 *       (temperature, vegetation, continents, erosion, depth, ridges). Without
 *       memoization each field walks the same sub-tree from scratch, allocating fresh
 *       {@code FlatCache}/{@code NoiseInterpolator} wrappers every time. The first
 *       cache wraps the noise filler and runs {@code compute} per cell-corner — that's
 *       the {@code 2.45%} slice for {@code FlatCache.<init>} in the {@code init_biomes}
 *       profile, multiplied by the number of duplicate wraps we let through.</li>
 *
 *   <li><b>Vanilla {@code Marker.mapAll} re-allocation.</b>
 *       {@code MarkerOrMarked.mapAll} unconditionally returns
 *       {@code new Marker(type, wrapped.mapAll(visitor))}, even when {@code wrapped.mapAll}
 *       returned the same instance. The fresh marker is a different object identity (and,
 *       since {@code wrapped} differs across siblings without memoization, structurally
 *       unequal too), so {@code NoiseChunk}'s {@code wrapped.computeIfAbsent} dedup misses
 *       and a new cache wrapper is spun up. {@link CompiledDensityFunction#mapAll} bypasses
 *       this for {@code Marker} externs by reusing the original marker instance whenever
 *       the inner result is reference-identical.</li>
 * </ul>
 *
 * <h2>Memoization scope</h2>
 *
 * <p>One session wraps the user visitor for the duration of a single top-level
 * {@code CompiledDensityFunction.mapAll} call (and all of its recursive descents).
 * Re-entrant calls — {@code marker.wrapped().mapAll(session)} hitting another
 * compiled DF — recognise the wrapper via {@code instanceof} and reuse the existing
 * memo. Once the outer call returns, the session goes out of scope and the maps are
 * eligible for GC.
 *
 * <p>The memo is single-thread per call (each chunk init runs on a single thread),
 * so a plain {@link IdentityHashMap} is fine — the cheaper hash and lower constant
 * factor matter more than the cross-thread guarantees we don't need.
 *
 * <h2>Visitor contract preservation</h2>
 *
 * <p>{@link #apply} forwards to the user visitor without memoization: the user
 * (typically {@code NoiseChunk::wrap}) has its own dedup HashMap and may legitimately
 * have side effects we shouldn't suppress. Our memoization caches only full
 * {@code mapAll} results ({@link #mapAllMemo}), which is idempotent given a stable
 * visitor.
 */
public final class MapAllSession implements DensityFunction.Visitor {

    private static final LongAdder SESSIONS_CREATED = new LongAdder();
    private static final LongAdder MEMO_HITS = new LongAdder();
    private static final LongAdder MEMO_MISSES = new LongAdder();
    private static final AtomicInteger MAX_MEMO_SIZE = new AtomicInteger();

    private final DensityFunction.Visitor user;

    /**
     * Identity-keyed cache of {@code densityFunction.mapAll(this)} results for the
     * duration of this session. Keys include vanilla nodes (shared {@code BlendDensity},
     * router subgraph roots, etc.) and {@link CompiledDensityFunction} instances.
     *
     * <p>Without this, every compiled extern slot that references the same vanilla
     * subgraph by identity would run a full {@code DensityFunctions.*.mapAll} walk
     * again — {@code init_biomes} does that hundreds of times per router.
     */
    final IdentityHashMap<DensityFunction, DensityFunction> mapAllMemo =
            new IdentityHashMap<>();

    public MapAllSession(DensityFunction.Visitor user) {
        SESSIONS_CREATED.increment();
        this.user = user;
    }

    public record Stats(long sessionsCreated, long memoHits, long memoMisses, int maxMemoSize) {}

    public static Stats snapshotStats() {
        return new Stats(SESSIONS_CREATED.sum(), MEMO_HITS.sum(), MEMO_MISSES.sum(),
                MAX_MEMO_SIZE.get());
    }

    public static void resetStats() {
        SESSIONS_CREATED.reset();
        MEMO_HITS.reset();
        MEMO_MISSES.reset();
        MAX_MEMO_SIZE.set(0);
    }

    DensityFunction memoGet(DensityFunction key) {
        DensityFunction value = mapAllMemo.get(key);
        if (value == null) {
            MEMO_MISSES.increment();
        } else {
            MEMO_HITS.increment();
        }
        return value;
    }

    void memoPut(DensityFunction key, DensityFunction value) {
        mapAllMemo.put(key, value);
        updateMaxMemoSize(mapAllMemo.size());
    }

    private static void updateMaxMemoSize(int size) {
        int prev;
        do {
            prev = MAX_MEMO_SIZE.get();
            if (size <= prev) {
                return;
            }
        } while (!MAX_MEMO_SIZE.compareAndSet(prev, size));
    }

    /** The original user visitor this session forwards {@code apply} / {@code visitNoise} to. */
    public DensityFunction.Visitor user() {
        return user;
    }

    @Override
    public DensityFunction apply(DensityFunction df) {
        return user.apply(df);
    }

    @Override
    public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
        return user.visitNoise(holder);
    }
}

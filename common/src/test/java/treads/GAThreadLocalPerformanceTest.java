package treads;

import dev.sixik.generator_accelerator.common.treads.GAThread;
import dev.sixik.generator_accelerator.common.treads.GAThreadLocal;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests для GAThreadLocal vs ThreadLocal.
 */
@Tag("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GAThreadLocalPerformanceTest {

    private static final long ITERATIONS = 10_000_000L;
    private static final int RUNS = 5;

    /**
     * Каждый бенчмарк запускается ВНУТРИ GAThread, потому что иначе
     * GAThreadLocal провалится в getSlow() и сравнение будет нечестным.
     * <p>
     * Этот метод выполняет переданный код на отдельном GAThread и
     * пробрасывает Result обратно.
     */
    private Bench.Result runOnGAThread(Runnable setup, java.util.function.Supplier<Bench.Result> measurement)
            throws InterruptedException {
        AtomicReference<Bench.Result> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        GAThread t = new GAThread(() -> {
            try {
                if (setup != null) setup.run();
                resultRef.set(measurement.get());
            } catch (Throwable e) {
                errorRef.set(e);
            } finally {
                done.countDown();
            }
        }, "perf-test-ga");
        t.start();

        boolean finished = done.await(60, TimeUnit.SECONDS);
        assertTrue(finished, "GAThread бенчмарк не завершился за 60 секунд");
        if (errorRef.get() != null) throw new AssertionError(errorRef.get());

        return resultRef.get();
    }

    @Test
    @Order(1)
    @DisplayName("Sanity: GAThreadLocal на GAThread действительно идёт по fast-path")
    void sanityCheck() throws InterruptedException {
        AtomicReference<Boolean> isOnGAThread = new AtomicReference<>(false);
        CountDownLatch done = new CountDownLatch(1);

        GAThread t = new GAThread(() -> {
            isOnGAThread.set(Thread.currentThread() instanceof GAThread);
            done.countDown();
        }, "sanity");
        t.start();
        done.await();

        assertTrue(isOnGAThread.get(),
                "GAThread не распознан как GAThread — что-то не так с classloader или иерархией");
    }

    @Test
    @Order(2)
    @DisplayName("get() — чистый микро-вызов")
    void benchmarkPureGet() throws InterruptedException {
        ThreadLocal<long[]> stdTl = ThreadLocal.withInitial(() -> new long[16]);
        GAThreadLocal<long[]> gaTl = GAThreadLocal.withInitial(() -> new long[16]);

        long[] stdCounter = new long[1];
        long[] gaCounter = new long[1];

        Bench.Result stdResult = runOnGAThread(null, () ->
                Bench.measure("ThreadLocal.get()", ITERATIONS, RUNS, () -> {
                    long[] arr = stdTl.get();
                    arr[0] = ++stdCounter[0];
                    return arr[0];
                })
        );
        Bench.Result gaResult = runOnGAThread(null, () ->
                Bench.measure("GAThreadLocal.get()", ITERATIONS, RUNS, () -> {
                    long[] arr = gaTl.get();
                    arr[0] = ++gaCounter[0];
                    return arr[0];
                })
        );

        printComparison("Pure get()", stdResult, gaResult);
        assertTrue(gaResult.medianNs <= stdResult.medianNs * 1.5,
                "GAThreadLocal оказался существенно медленнее — что-то сломано. " +
                        "std=" + stdResult.medianNs + " ga=" + gaResult.medianNs);
    }

    @Test
    @Order(3)
    @DisplayName("get() + use — реалистичный buffer reuse pattern")
    void benchmarkGetAndUse() throws InterruptedException {
        ThreadLocal<long[]> stdTl = ThreadLocal.withInitial(() -> new long[16]);
        GAThreadLocal<long[]> gaTl = GAThreadLocal.withInitial(() -> new long[16]);

        Bench.Result stdResult = runOnGAThread(null, () ->
                Bench.measure("std getAndUse", ITERATIONS, RUNS, () -> {
                    long[] arr = stdTl.get();
                    arr[0]++;
                    return arr[0];
                })
        );
        Bench.Result gaResult = runOnGAThread(null, () ->
                Bench.measure("ga getAndUse", ITERATIONS, RUNS, () -> {
                    long[] arr = gaTl.get();
                    arr[0]++;
                    return arr[0];
                })
        );

        printComparison("get() + use", stdResult, gaResult);
    }

    @Test
    @Order(4)
    @DisplayName("Множественные ThreadLocal в hot path (8 шт — типично для chunk gen)")
    void benchmarkMultipleLocals() throws InterruptedException {
        @SuppressWarnings("unchecked")
        ThreadLocal<long[]>[] stdLocals = new ThreadLocal[8];
        @SuppressWarnings("unchecked")
        GAThreadLocal<long[]>[] gaLocals = new GAThreadLocal[8];

        for (int i = 0; i < 8; i++) {
            final int idx = i;
            stdLocals[i] = ThreadLocal.withInitial(() -> new long[]{idx});
            gaLocals[i] = GAThreadLocal.withInitial(() -> new long[]{idx});
        }

        Bench.Result stdResult = runOnGAThread(null, () ->
                Bench.measure("std multi(8)", ITERATIONS / 8, RUNS, () -> {
                    long sum = 0;
                    for (int i = 0; i < 8; i++) sum += stdLocals[i].get()[0];
                    return sum;
                })
        );
        Bench.Result gaResult = runOnGAThread(null, () ->
                Bench.measure("ga multi(8)", ITERATIONS / 8, RUNS, () -> {
                    long sum = 0;
                    for (int i = 0; i < 8; i++) sum += gaLocals[i].get()[0];
                    return sum;
                })
        );

        printComparison("Multi-local (8 ThreadLocal)", stdResult, gaResult);
    }

    @Test
    @Order(5)
    @DisplayName("Симуляция chunk generation (имитация рабочей нагрузки)")
    void benchmarkChunkGenSim() throws InterruptedException {
        ThreadLocal<GenContext> stdCtx = ThreadLocal.withInitial(GenContext::new);
        GAThreadLocal<GenContext> gaCtx = GAThreadLocal.withInitial(GenContext::new);

        long iters = ITERATIONS / 100; // тяжёлая работа, меньше итераций

        Bench.Result stdResult = runOnGAThread(null, () ->
                Bench.measure("std chunkSim", iters, RUNS, () -> {
                    GenContext c = stdCtx.get();
                    c.simulate();
                    return c.heightmap[0];
                })
        );
        Bench.Result gaResult = runOnGAThread(null, () ->
                Bench.measure("ga chunkSim", iters, RUNS, () -> {
                    GenContext c = gaCtx.get();
                    c.simulate();
                    return c.heightmap[0];
                })
        );

        printComparison("Chunk gen simulation", stdResult, gaResult);
    }

    @Test
    @Order(6)
    @DisplayName("Проверка что fallback (slow path) тоже работает")
    void benchmarkFallbackPath() {
        GAThreadLocal<long[]> gaTl = GAThreadLocal.withInitial(() -> new long[16]);
        ThreadLocal<long[]> stdTl = ThreadLocal.withInitial(() -> new long[16]);

        assertFalse(Thread.currentThread() instanceof GAThread,
                "Тест должен запускаться на обычном потоке");

        long[] gaCounter = new long[1];
        long[] stdCounter = new long[1];

        Bench.Result fallbackResult = Bench.measure(
                "ga fallback", ITERATIONS, RUNS, () -> {
                    long[] arr = gaTl.get();
                    arr[0] = ++gaCounter[0];
                    return arr[0];
                });
        Bench.Result stdResult = Bench.measure(
                "std baseline", ITERATIONS, RUNS, () -> {
                    long[] arr = stdTl.get();
                    arr[0] = ++stdCounter[0];
                    return arr[0];
                });

        System.out.println("\n=== Fallback path (когда не на GAThread) ===");
        System.out.println(fallbackResult);
        System.out.println(stdResult);
        System.out.println("Fallback должен быть в 1.5-3x медленнее std " +
                "(это нормально — двойной lookup через jdk ThreadLocal).");
    }

    private void printComparison(String label, Bench.Result std, Bench.Result ga) {
        System.out.println("\n=== " + label + " ===");
        System.out.println(std);
        System.out.println(ga);
        double speedup = (double) std.medianNs / Math.max(ga.medianNs, 1);
        System.out.printf("→ Speedup: %.2fx%n", speedup);

        if (!std.isReliable() || !ga.isReliable()) {
            System.out.println("⚠ ВНИМАНИЕ: высокий разброс измерений (>30%). " +
                    "Прогон ненадёжен. Закрой все приложения, отключи turbo boost, повтори.");
        }
    }

    static class GenContext {
        final int[] heightmap = new int[256];
        int seed = 0;

        void simulate() {
            seed++;
            for (int i = 0; i < heightmap.length; i++) {
                heightmap[i] = (seed * 31 + i) & 0xFF;
            }
        }
    }
}



package treads;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Минимальный harness для бенчмарков.
 * <p>
 * Защищается от типичных проблем самописных бенчмарков: <br>
 *   1. Dead code elimination — все результаты складываются в Blackhole. <br>
 *   2. JIT cold start — обязательный warmup перед измерением. <br>
 *   3. Outliers — несколько прогонов, отчёт по медиане + min/max. <br>
 *   4. GC шум — System.gc() между прогонами + предупреждение если разброс большой. <br>
 * <p>
 * НЕ защищается от: <br>
 *   - Профильного загрязнения между разными бенчмарками в одном JVM <br>
 *     (если вызвать testA() потом testB(), JIT-профиль testA повлияет на testB). <br>
 *     Для устранения — запускать каждый тест в отдельном JVM (-fork). <br>
 *     В JUnit это сложно. Если нужно — изолируй тесты через @Isolated и <br>
 *     -XX:-TieredCompilation.
 */
public final class Bench {

    /** Не private static, чтобы JIT не схлопнул в константу. */
    public static final AtomicLong BLACKHOLE = new AtomicLong();

    /** Считать прогон валидным, если разброс между min и median < этого %. */
    private static final double VARIANCE_WARN_THRESHOLD = 0.30; // 30%

    public static class Result {
        public final String name;
        public final long iterations;
        public final long[] timesNs;       // время каждого прогона (в нс на ВСЕ итерации)
        public final long medianNs;        // медианное время на одну операцию
        public final long minNs;
        public final long maxNs;
        public final double variancePct;   // (max - min) / median

        Result(String name, long iterations, long[] timesNs) {
            this.name = name;
            this.iterations = iterations;
            this.timesNs = timesNs;

            long[] sorted = timesNs.clone();
            Arrays.sort(sorted);
            this.minNs = sorted[0] / iterations;
            this.maxNs = sorted[sorted.length - 1] / iterations;
            this.medianNs = sorted[sorted.length / 2] / iterations;
            this.variancePct = (double) (maxNs - minNs) / Math.max(medianNs, 1);
        }

        public boolean isReliable() {
            return variancePct < VARIANCE_WARN_THRESHOLD;
        }

        @Override
        public String toString() {
            String reliability = isReliable() ? "OK" : "NOISY";
            return String.format("%-30s median=%4d ns/op  min=%4d  max=%4d  variance=%5.1f%%  [%s]",
                    name, medianNs, minNs, maxNs, variancePct * 100, reliability);
        }
    }

    /**
     * Замеряет работу, выполняя её iterationsPerRun раз, повторяя runs раз.
     * Возвращает Result с медианой и min/max.
     */
    public static Result measure(String name, long iterationsPerRun, int runs, LongSupplier work) {
        // === Warmup ===
        // 5 раз прогоняем полный workload, чтобы JIT всё скомпилировал.
        for (int i = 0; i < 5; i++) {
            long acc = 0;
            for (long j = 0; j < iterationsPerRun; j++) {
                acc += work.getAsLong();
            }
            BLACKHOLE.lazySet(acc);
        }

        // Просим GC не стрелять в середине замера
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        // === Measurement ===
        long[] times = new long[runs];
        for (int run = 0; run < runs; run++) {
            long acc = 0;
            long start = System.nanoTime();
            for (long j = 0; j < iterationsPerRun; j++) {
                acc += work.getAsLong();
            }
            long elapsed = System.nanoTime() - start;
            BLACKHOLE.lazySet(acc); // защита от DCE
            times[run] = elapsed;
        }

        return new Result(name, iterationsPerRun, times);
    }
}


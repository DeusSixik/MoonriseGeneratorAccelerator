package dev.sixik.generator_accelerator.common.levelgen.density_custom;

public class DensityThreadLocalData {

    // Храним пул массивов для каждого потока (максимальная глубина дерева обычно не больше 16-32)
    private static final ThreadLocal<double[][]> ARRAY_POOL = ThreadLocal.withInitial(() -> new double[32][]);
    // Храним текущую глубину рекурсии для потока
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    public static double[] acquire(int length) {
        int depth = DEPTH.get();
        DEPTH.set(depth + 1); // Увеличиваем глубину для следующих вложенных вызовов

        double[][] pool = ARRAY_POOL.get();

        // Защита от слишком глубоких деревьев (динамическое расширение)
        if (depth >= pool.length) {
            pool = java.util.Arrays.copyOf(pool, pool.length * 2);
            ARRAY_POOL.set(pool);
        }

        double[] ds = pool[depth];
        // Если массива на этом уровне еще нет или он слишком мал — создаем
        if (ds == null || ds.length < length) {
            ds = new double[length];
            pool[depth] = ds;
        }

        return ds;
    }

    public static void release() {
        // Обязательно освобождаем уровень после завершения вычислений
        DEPTH.set(DEPTH.get() - 1);
    }
}

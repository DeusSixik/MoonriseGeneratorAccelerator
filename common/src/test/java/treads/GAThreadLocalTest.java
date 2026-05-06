package treads;

import dev.sixik.generator_accelerator.common.treads.GAThread;
import dev.sixik.generator_accelerator.common.treads.GAThreadLocal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GAThreadLocalTest {

    private static final int ITERATIONS = 50_000_000; // 50 млн итераций для точности
    private static final int WARMUP_ITERATIONS = 10_000_000;

    private static final Supplier<Integer> INITIAL_VALUE = () -> 42;
    private static final ThreadLocal<Integer> jdkLocal = ThreadLocal.withInitial(INITIAL_VALUE);
    private static final GAThreadLocal<Integer> gaLocal = GAThreadLocal.withInitial(INITIAL_VALUE);

    @Test
    @DisplayName("Сравнение производительности: JDK vs GA Fast Path vs GA Fallback")
    void performanceComparison() throws InterruptedException {
        System.out.println("Запуск тестов производительности (" + ITERATIONS + " итераций)...");

        double jdkTime = runInThread(new Thread(() -> {
            warmup(jdkLocal::get);
            measure(jdkLocal::get);
        }), "JDK ThreadLocal");

        double gaFastTime = runInThread(new GAThread(() -> {
            warmup(gaLocal::get);
            measure(gaLocal::get);
        }, "GA-Fast-Worker"), "GAThreadLocal (Fast Path)");

        double gaSlowTime = runInThread(new Thread(() -> {
            warmup(gaLocal::get);
            measure(gaLocal::get);
        }), "GAThreadLocal (Fallback)");

        System.out.println("\n--- Результаты (среднее время на операцию) ---");
        System.out.printf("JDK ThreadLocal:        %.2f ns\n", jdkTime);
        System.out.printf("GA Fast Path:           %.2f ns (Ускорение: %.1fx)\n", gaFastTime, jdkTime / gaFastTime);
        System.out.printf("GA Fallback:           %.2f ns\n", gaSlowTime);
    }

    @Test
    @DisplayName("Проверка корректности: значения должны сохраняться")
    void correctnessTest() throws InterruptedException {
        GAThreadLocal<String> localStr = GAThreadLocal.withInitial(() -> "default");

        runInThread(new GAThread(() -> {
            assertEquals("default", localStr.get());
            localStr.set("custom");
            assertEquals("custom", localStr.get());
            localStr.remove();
            assertEquals("default", localStr.get());
        }, "GA-Correctness"), "Correctness-Check");
    }

    private void warmup(Runnable task) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            task.run();
        }
    }

    private double measure(Supplier<Integer> task) {
        long start = System.nanoTime();
        long checksum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            checksum += task.get();
        }
        long end = System.nanoTime();

        if (checksum == 0) System.out.print("");

        return (double) (end - start) / ITERATIONS;
    }

    private double runInThread(Thread t, String label) throws InterruptedException {
        final double[] result = new double[1];
        Thread runner = new Thread(() -> {
            if (t instanceof GAThread gaThread) {
                try {
                    t.start();
                    t.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } else {
                t.start();
                try {
                    t.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        long start = System.currentTimeMillis();
        t.start();
        t.join();
        return System.currentTimeMillis() - start;
    }
}

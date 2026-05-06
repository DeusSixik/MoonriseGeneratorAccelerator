package treads;

import dev.sixik.generator_accelerator.common.treads.GAThread;
import dev.sixik.generator_accelerator.common.treads.GAThreadLocal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты корректности GAThreadLocal.
 * <p>
 * Эти тесты НЕ замеряют производительность — они проверяют что код
 * работает правильно. Прежде чем оптимизировать, надо убедиться в корректности.
 */
class GAThreadLocalCorrectnessTest {

    @Test
    @DisplayName("Базовый get() возвращает supplier value")
    void basicGet() throws InterruptedException {
        GAThreadLocal<String> tl = GAThreadLocal.withInitial(() -> "hello");

        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new GAThread(() -> {
            result.set(tl.get());
            done.countDown();
        }, "test").start();
        done.await();

        assertEquals("hello", result.get());
    }

    @Test
    @DisplayName("set() и get() возвращают то что положили")
    void setAndGet() throws InterruptedException {
        GAThreadLocal<String> tl = GAThreadLocal.withInitial(() -> "default");

        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new GAThread(() -> {
            tl.set("custom");
            result.set(tl.get());
            done.countDown();
        }, "test").start();
        done.await();

        assertEquals("custom", result.get());
    }

    @Test
    @DisplayName("null как валидное значение поддерживается через NULL_SENTINEL")
    void nullValue() throws InterruptedException {
        GAThreadLocal<String> tl = GAThreadLocal.withInitial(() -> "initial");
        AtomicInteger supplierCallCount = new AtomicInteger();
        GAThreadLocal<String> tlNullable = GAThreadLocal.withInitial(() -> {
            supplierCallCount.incrementAndGet();
            return null;
        });

        CountDownLatch done = new CountDownLatch(1);
        new GAThread(() -> {
            // Сначала установим null явно
            tl.set(null);
            assertNull(tl.get(), "После set(null) get() должен вернуть null");

            // Через supplier
            assertNull(tlNullable.get());
            assertNull(tlNullable.get());
            assertNull(tlNullable.get());

            done.countDown();
        }, "test").start();
        done.await();

        // Если NULL_SENTINEL работает, supplier вызовется только 1 раз для tlNullable
        assertEquals(1, supplierCallCount.get(),
                "Supplier должен вызываться только при первом get(), null должен кешироваться");
    }

    @Test
    @DisplayName("Каждый поток имеет независимое значение")
    void threadIsolation() throws InterruptedException {
        GAThreadLocal<Integer> tl = GAThreadLocal.withInitial(() -> 0);

        int threadCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new GAThread(() -> {
                try {
                    start.await();
                    tl.set(threadId);
                    // Даём другим потокам шанс перезаписать (если бы они могли)
                    Thread.sleep(10);
                    if (tl.get() != threadId) {
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "iso-" + i).start();
        }

        start.countDown();
        done.await();
        assertEquals(0, errors.get(), "Потоки видели чужие значения!");
    }

    @Test
    @DisplayName("remove() очищает значение, supplier вызывается заново")
    void removeAndReinit() throws InterruptedException {
        AtomicInteger supplierCalls = new AtomicInteger();
        GAThreadLocal<Integer> tl = GAThreadLocal.withInitial(() -> {
            return supplierCalls.incrementAndGet();
        });

        CountDownLatch done = new CountDownLatch(1);
        new GAThread(() -> {
            assertEquals(1, tl.get());
            assertEquals(1, tl.get()); // кешировано
            tl.remove();
            assertEquals(2, tl.get()); // supplier вызван заново
            done.countDown();
        }, "test").start();
        done.await();
    }

    @Test
    @DisplayName("Работа на обычном Thread (fallback path)")
    void worksOnRegularThread() {
        GAThreadLocal<String> tl = GAThreadLocal.withInitial(() -> "fallback");

        // Текущий поток (JUnit) — не GAThread
        assertFalse(Thread.currentThread() instanceof GAThread);

        // Но всё должно работать через fallback
        assertEquals("fallback", tl.get());
        tl.set("modified");
        assertEquals("modified", tl.get());
        tl.remove();
        assertEquals("fallback", tl.get());
    }

    @Test
    @DisplayName("Stress test: множественный set/get на 16 потоках")
    void stressTest() throws InterruptedException {
        GAThreadLocal<Integer> tl = GAThreadLocal.withInitial(() -> -1);

        int threadCount = 16;
        int iterations = 100_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int seed = i * 1_000_000;
            new GAThread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        int value = seed + j;
                        tl.set(value);
                        if (tl.get() != value) {
                            errors.incrementAndGet();
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "stress-" + i).start();
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        assertTrue(finished, "Stress test не завершился за 30 сек");
        assertEquals(0, errors.get(),
                "Обнаружены race conditions или потерянные значения");
    }
}


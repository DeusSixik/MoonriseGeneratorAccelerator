package dev.sixik.generator_accelerator.common.executors;

import com.google.common.collect.Lists;
import dev.sixik.generator_accelerator.GeneratorAccelerator;
import org.jctools.queues.MpmcArrayQueue;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Оптимизированный пул потоков для выполнения задач с минимальными накладными расходами.
 * Использует ConcurrentLinkedQueue для минимизации блокировок и Semaphore для управления потоками.
 * Поддерживает отлов ошибок через ErrorThreadingDetector.
 */
public class ChunkGenerationExecutor implements ExecutorService {

    public static ChunkGenerationExecutor executorInstance;

    private final MpmcArrayQueue<Runnable> runnableMpscLinkedQueue;
    private final Thread[] threadWorkers;
    private final AtomicBoolean isShutdown;
    private final Semaphore taskSemaphore;
    private ExecuteCondition executeCondition = () -> false;

    /**
     * Останавливает главный пул потоков, если он был инициализирован.
     */
    public static void shutdownInstance() {
        GeneratorAccelerator.LOGGER.info("ChunkGenerationExecutor::shutdownInstance");
        if (executorInstance != null) {
            executorInstance.shutdown();
            GeneratorAccelerator.LOGGER.info("ChunkGenerationExecutor#executorInstance:shutdown");
        }
    }


    /**
     * Получает ссылку на главный пул потоков. Создает новый, если он не инициализирован или завершен.
     */
    public static ChunkGenerationExecutor getInstance() {
        return executorInstance;
    }

    public static ChunkGenerationExecutor createInstanceExecutor(int numThreads) {
        if(executorInstance == null || executorInstance.isShutdown.get()) {
            executorInstance = new ChunkGenerationExecutor(numThreads, 1024, "Chunk Generation Worker");
        }

        return executorInstance;
    }

    public static ChunkGenerationExecutor createExecutor(int numThreads) {
        return new ChunkGenerationExecutor(numThreads, 1024, "Chunk Generation Worker");
    }

    public ChunkGenerationExecutor(int numThreads, int maxQueueSize, String name) {
//        this.concurrentTasks = new ConcurrentLinkedQueue<>();
        this.isShutdown = new AtomicBoolean(false);
        this.taskSemaphore = new Semaphore(0);
        this.runnableMpscLinkedQueue = new MpmcArrayQueue<>(maxQueueSize);
        this.threadWorkers = new Thread[numThreads];

        for (int i = 0; i < numThreads; i++) {
            threadWorkers[i] = new Thread(new Worker(),  name + ": " + i);
            threadWorkers[i].setDaemon(true); // Потоки фоновые для упрощения завершения JVM
            threadWorkers[i].start();
        }

        GeneratorAccelerator.LOGGER.info("Create Chunk Generator Executor");
    }

    public void setExecuteCondition(ExecuteCondition executeCondition) {
        this.executeCondition = executeCondition;
    }

    @SuppressWarnings("unchecked")
    public <T extends ExecuteCondition> T getExecuteCondition() {
        return (T) executeCondition;
    }

    public ChunkGenerationExecutor() {
        this(Math.max(1, Runtime.getRuntime().availableProcessors() / 4), 1024, "Chunk Generation Worker");
    }

    @Override
    public void execute(Runnable command) {
        if (isShutdown.get()) {
            return;
        }
        if (command == null) {
            return;
        }

        runnableMpscLinkedQueue.offer(BExecutorTask.createVoid(command));
        taskSemaphore.release(); // Уведомляем потоки о новой задаче
    }

    /**
     * Отправляет задачу без результата и возвращает CompletableFuture для отслеживания выполнения.
     */
    public CompletableFuture<Void> submit(Runnable runnable) {
        BExecutorTask<Void> task = BExecutorTask.createVoid(runnable);
        execute(task);
        return task.getFuture();
    }

    /**
     * Отправляет задачу с результатом и возвращает CompletableFuture для отслеживания выполнения.
     */
    public <T> CompletableFuture<T> submit(Supplier<T> supplier) {
        BExecutorTask<T> task = new BExecutorTask<>(supplier);
        execute(task);
        return task.getFuture();
    }

    @Override
    public <T> Future<T> submit(@NotNull Callable<T> task) {
        return submit((Supplier<T>) () -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public <T> Future<T> submit(@NotNull Runnable task, T result) {
        return submit((Supplier<T>) () -> {
            task.run();
            return result;
        });
    }

    @Override
    public void shutdown() {
        GeneratorAccelerator.LOGGER.info("Start shutdown ChunkGenerationExecutor");
        if (stopThreads()) {
            isShutdown.set(true);
            ChunkGenerationExecutor.executorInstance = null;
        }
        GeneratorAccelerator.LOGGER.info("Shutdown ChunkGenerationExecutor");
    }

    @Override
    public @NotNull List<Runnable> shutdownNow() {
        runnableMpscLinkedQueue.clear(); // Очищаем очередь для немедленной остановки
        shutdown();
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return isShutdown.get();
    }

    @Override
    public boolean isTerminated() {
        return isShutdown.get() && runnableMpscLinkedQueue.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;
        for (Thread thread : threadWorkers) {
            if (thread.isAlive()) {
                thread.join(Math.max(nanos / 1_000_000, 1));
                nanos = deadline - System.nanoTime();
                if (nanos <= 0) {
                    return false;
                }
            }
        }
        return isTerminated();
    }

    @Override
    public @NotNull <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks) throws InterruptedException {
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(submit(task));
        }
        for (Future<T> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        return futures;
    }

    @Override
    public @NotNull <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks, long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        List<Future<T>> futures = new ArrayList<>();
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;
        for (Callable<T> task : tasks) {
            futures.add(submit(task));
        }
        for (Future<T> future : futures) {
            try {
                future.get(Math.max(nanos / 1_000_000, 1), TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // Игнорируем исключения, так как задача могла быть выполнена частично
            }
            nanos = deadline - System.nanoTime();
            if (nanos <= 0) {
                break;
            }
        }
        return futures;
    }

    @Override
    public @NotNull <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(submit(task));
        }
        for (Future<T> future : futures) {
            if (future.isDone() && !future.isCancelled()) {
                return future.get();
            }
        }
        throw new ExecutionException("No tasks completed successfully", null);
    }

    @Override
    public <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks, long timeout, @NotNull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        List<Future<T>> futures = new ArrayList<>();
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;
        for (Callable<T> task : tasks) {
            futures.add(submit(task));
        }
        for (Future<T> future : futures) {
            try {
                if (!future.isDone()) {
                    return future.get(Math.max(nanos / 1_000_000, 1), TimeUnit.MILLISECONDS);
                }
                if (!future.isCancelled()) {
                    return future.get();
                }
            } catch (TimeoutException e) {
                throw e;
            } catch (ExecutionException e) {
                // Продолжаем проверять другие задачи
            }
            nanos = deadline - System.nanoTime();
            if (nanos <= 0) {
                throw new TimeoutException();
            }
        }
        throw new ExecutionException("No tasks completed successfully", null);
    }

    public ChunkGenerationExecutor shutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        return this;
    }

    private boolean stopThreads() {
        if (isShutdown.compareAndSet(false, true)) {
            for (Thread thread : threadWorkers) {
                thread.interrupt();
            }
            for (Thread thread : threadWorkers) {
                try {
                    thread.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        }
        return false;
    }

    private class Worker implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted() && !isShutdown.get()) {
                try {
                    taskSemaphore.acquire(); // Ожидаем задачу
                    if(executeCondition.canExecuteTask()) continue;
                    Runnable task = runnableMpscLinkedQueue.poll();
                    if (task != null) {
                        try {
                            task.run();
                        } catch (Exception e) {
                            GeneratorAccelerator.LOGGER.error(e.getMessage(), e);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Восстанавливаем флаг прерывания
                    break;
                }
            }
        }
    }
}

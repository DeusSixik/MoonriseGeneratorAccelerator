package dev.sixik.generator_accelerator.common.executors;

import dev.sixik.generator_accelerator.GeneratorAccelerator;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class BExecutorTask<T> implements Runnable {

    private final CompletableFuture<T> future;
    private final Supplier<T> runnable;

    public BExecutorTask(Supplier<T> runnable) {
        this(new CompletableFuture<>(), runnable);
    }

    public BExecutorTask(CompletableFuture<T> future, Supplier<T> runnable) {
        this.future = future;
        this.runnable = runnable;
    }

    public static BExecutorTask<Void> createVoid(Runnable runnable) {
        return new BExecutorTask<>(() -> {
            runnable.run();
            return null;
        });
    }

    public static <T> BExecutorTask<T> createTask(Supplier<T> supplier) {
        return new BExecutorTask<>(supplier);
    }

    public CompletableFuture<T> getFuture() {
        return future;
    }

    @Override
    public void run() {
        try {
            future.complete(runnable.get());
        } catch (Throwable t) {
            future.completeExceptionally(t);
            GeneratorAccelerator.LOGGER.error("Task execution failed", t);
        }
    }

    public void runWithExceptionHandling() {
        try {
            T result = future.get();
            future.complete(result);
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }
}

package dev.sixik.generator_accelerator.common.worldgen.transaction;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs one selected unknown unit against a detached transaction context.
 */
public final class GATransactionLaneRunner {
    private static final AtomicLong STARTED = new AtomicLong();
    private static final AtomicLong SEALED = new AtomicLong();
    private static final AtomicLong DOWNGRADED = new AtomicLong();
    private static final AtomicLong ABORTED = new AtomicLong();
    private static final AtomicLong ASYNC_STARTED = new AtomicLong();

    private GATransactionLaneRunner() {
    }

    public static GATransactionRunResult run(String unitId, GATransactionSandboxUnit unit) {
        Objects.requireNonNull(unit, "unit");
        STARTED.incrementAndGet();
        GATransactionSandboxWriter writer = new GATransactionSandboxWriter();
        GATransactionSandboxContext context = new GATransactionSandboxContext(unitId, writer);

        try {
            unit.run(context);
            if (writer.journal().open()) {
                writer.seal();
            }
        } catch (Exception exception) {
            writer.fail(exception);
        }

        GATransactionSnapshot snapshot = writer.snapshot();
        recordSnapshot(snapshot);
        return new GATransactionRunResult(
                context.unitId(),
                snapshot,
                GATransactionHandoffMetadata.fromSnapshot(context.unitId(), snapshot)
        );
    }

    public static CompletableFuture<GATransactionRunResult> runAsync(String unitId, GATransactionSandboxUnit unit) {
        ASYNC_STARTED.incrementAndGet();
        return GAScheduler.supplyAsync(GAScheduler.Lane.TRANSACTIONAL, () -> run(unitId, unit));
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", STARTED.get());
        out.put("sealed", SEALED.get());
        out.put("downgraded", DOWNGRADED.get());
        out.put("aborted", ABORTED.get());
        out.put("asyncStarted", ASYNC_STARTED.get());
        return out;
    }

    public static void resetMetricsForTests() {
        STARTED.set(0L);
        SEALED.set(0L);
        DOWNGRADED.set(0L);
        ABORTED.set(0L);
        ASYNC_STARTED.set(0L);
    }

    private static void recordSnapshot(GATransactionSnapshot snapshot) {
        switch (snapshot.state()) {
            case SEALED -> SEALED.incrementAndGet();
            case DOWNGRADED -> DOWNGRADED.incrementAndGet();
            case ABORTED -> ABORTED.incrementAndGet();
            case OPEN -> {
            }
        }
    }
}

package dev.sixik.generator_accelerator.common.worldgen.transaction;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.common.worldgen.commit.GABlockPosition;
import dev.sixik.generator_accelerator.common.worldgen.commit.GABlockWriteValue;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitBatch;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCollisionPolicy;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitCommand;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitEngine;
import dev.sixik.generator_accelerator.common.worldgen.commit.GACommitOrderKey;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime adapter for selected unknown/modded units that can run against the
 * transaction sandbox and then commit a deterministic block journal.
 */
public final class GATransactionRuntimeDispatcher {
    private static final GAConfig CONFIG = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
    private static final boolean ENABLED = booleanProperty(
            "ga.transactionSandbox.runtime.enabled",
            CONFIG.enableTransactionSandboxRuntime
    );
    private static final AtomicLong DISPATCHED = new AtomicLong();
    private static final AtomicLong COMMITTED = new AtomicLong();
    private static final AtomicLong SERIAL_FALLBACK = new AtomicLong();
    private static final AtomicLong QUARANTINED = new AtomicLong();
    private static final AtomicLong COMMIT_FAILURES = new AtomicLong();

    private GATransactionRuntimeDispatcher() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static DispatchResult dispatchAndCommit(
            String unitId,
            ChunkAccess ownerChunk,
            GACommitOrderKey baseOrderKey,
            GATransactionSandboxUnit unit
    ) {
        if (!ENABLED) {
            SERIAL_FALLBACK.incrementAndGet();
            return DispatchResult.disabled(unitId);
        }
        if (ownerChunk == null) {
            throw new NullPointerException("ownerChunk");
        }
        if (baseOrderKey == null) {
            throw new NullPointerException("baseOrderKey");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        DISPATCHED.incrementAndGet();
        GATransactionRunResult run = GATransactionLaneRunner.run(unitId, unit);
        if (!run.success()) {
            recordFallback(run);
            return DispatchResult.fallback(run);
        }

        try {
            GACommitEngine.GACommitExecution<GABlockWriteValue> execution = commit(ownerChunk, run.snapshot(), baseOrderKey);
            if (!execution.failures().isEmpty()) {
                COMMIT_FAILURES.addAndGet(execution.failures().size());
                SERIAL_FALLBACK.incrementAndGet();
                return DispatchResult.commitFailure(run, execution.failures().size());
            }
            COMMITTED.addAndGet(execution.metrics().acceptedCount());
            return DispatchResult.committed(run, execution.metrics().acceptedCount());
        } catch (RuntimeException failure) {
            COMMIT_FAILURES.incrementAndGet();
            SERIAL_FALLBACK.incrementAndGet();
            return DispatchResult.exception(run, failure);
        }
    }

    public static CompletableFuture<DispatchResult> dispatchAndCommitAsync(
            String unitId,
            ChunkAccess ownerChunk,
            GACommitOrderKey baseOrderKey,
            GATransactionSandboxUnit unit
    ) {
        return GAScheduler.supplyAsync(
                GAScheduler.Lane.TRANSACTIONAL,
                () -> dispatchAndCommit(unitId, ownerChunk, baseOrderKey, unit)
        );
    }

    public static DispatchResult dispatchAndCommitBlocking(
            String unitId,
            ChunkAccess ownerChunk,
            GACommitOrderKey baseOrderKey,
            GATransactionSandboxUnit unit
    ) throws InterruptedException, ExecutionException {
        return dispatchAndCommitAsync(unitId, ownerChunk, baseOrderKey, unit).get();
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("dispatched", DISPATCHED.get());
        out.put("committed", COMMITTED.get());
        out.put("serialFallback", SERIAL_FALLBACK.get());
        out.put("quarantined", QUARANTINED.get());
        out.put("commitFailures", COMMIT_FAILURES.get());
        out.put("laneRunner", GATransactionLaneRunner.snapshot());
        return out;
    }

    public static void resetForTests() {
        DISPATCHED.set(0L);
        COMMITTED.set(0L);
        SERIAL_FALLBACK.set(0L);
        QUARANTINED.set(0L);
        COMMIT_FAILURES.set(0L);
        GATransactionLaneRunner.resetMetricsForTests();
    }

    private static GACommitEngine.GACommitExecution<GABlockWriteValue> commit(
            ChunkAccess ownerChunk,
            GATransactionSnapshot snapshot,
            GACommitOrderKey baseOrderKey
    ) {
        List<GACommitCommand<Object>> rawCommands = GATransactionCommitBridge.blockWriteCommands(snapshot, baseOrderKey);
        List<GACommitCommand<GABlockWriteValue>> commands = new java.util.ArrayList<>(rawCommands.size());
        for (GACommitCommand<Object> command : rawCommands) {
            commands.add(castBlockWriteCommand(command));
        }
        return GACommitEngine.execute(
                GACommitBatch.of(commands),
                GACommitCollisionPolicy.FIRST_WRITE_WINS,
                command -> applyBlockWrite(ownerChunk, command)
        );
    }

    private static void applyBlockWrite(ChunkAccess ownerChunk, GACommitCommand<GABlockWriteValue> command) {
        Object state = command.value().state();
        if (!(state instanceof BlockState blockState)) {
            throw new IllegalArgumentException("transaction block state is not a BlockState: " + state);
        }
        GABlockPosition position = command.position();
        ownerChunk.setBlockState(new BlockPos(position.x(), position.y(), position.z()), blockState, false);
    }

    @SuppressWarnings("unchecked")
    private static GACommitCommand<GABlockWriteValue> castBlockWriteCommand(GACommitCommand<Object> command) {
        if (!(command.value() instanceof GABlockWriteValue value)) {
            throw new IllegalArgumentException("transaction command is not a block write: " + command.value());
        }
        return new GACommitCommand<>(command.position(), command.orderKey(), value);
    }

    private static void recordFallback(GATransactionRunResult run) {
        SERIAL_FALLBACK.incrementAndGet();
        if (run.handoff().requiresQuarantine()) {
            QUARANTINED.incrementAndGet();
        }
    }

    private static boolean booleanProperty(String property, boolean fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public record DispatchResult(
            String unitId,
            boolean committed,
            boolean serialFallback,
            boolean quarantine,
            int acceptedWrites,
            String reason,
            GATransactionRunResult run
    ) {
        private static DispatchResult disabled(String unitId) {
            return new DispatchResult(unitId, false, true, false, 0, "transaction sandbox runtime disabled", null);
        }

        private static DispatchResult fallback(GATransactionRunResult run) {
            return new DispatchResult(
                    run.unitId(),
                    false,
                    true,
                    run.handoff().requiresQuarantine(),
                    0,
                    run.handoff().reason(),
                    run
            );
        }

        private static DispatchResult committed(GATransactionRunResult run, int acceptedWrites) {
            return new DispatchResult(run.unitId(), true, false, false, acceptedWrites, null, run);
        }

        private static DispatchResult commitFailure(GATransactionRunResult run, int failures) {
            return new DispatchResult(
                    run.unitId(),
                    false,
                    true,
                    false,
                    0,
                    "commit failures: " + failures,
                    run
            );
        }

        private static DispatchResult exception(GATransactionRunResult run, RuntimeException failure) {
            return new DispatchResult(
                    run.unitId(),
                    false,
                    true,
                    false,
                    0,
                    failure.getClass().getName() + ": " + failure.getMessage(),
                    run
            );
        }
    }
}

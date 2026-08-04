package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import dev.sixik.generator_accelerator.config.GAConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class GASchedulerV2CoreTest {
    private static boolean bootstrapped;

    @BeforeAll
    static synchronized void bootstrapMinecraft() {
        if (bootstrapped) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bootstrapped = true;
    }

    @Test
    void workHandleRoundTripsGenerationArenaNodeAndFlags() {
        long handle = GAWorkHandle.encode(0x1_FFFF_FFFFL, 17, 12345, GAWorkHandle.FLAG_URGENT | GAWorkHandle.FLAG_RESUME);

        assertEquals(17, GAWorkHandle.arenaIndex(handle));
        assertEquals(12345, GAWorkHandle.nodeIndex(handle));
        assertEquals((int) 0xFFFF_FFFFL, GAWorkHandle.generationLow32(handle));
        assertTrue(GAWorkHandle.urgent(handle));
        assertTrue(GAWorkHandle.resume(handle));
    }

    @Test
    void readyQueueRingPreservesOrderAndRejectsOverflow() {
        GAReadyQueue.LongRing ring = new GAReadyQueue.LongRing(2);

        assertTrue(ring.offer(11L));
        assertTrue(ring.offer(12L));
        assertFalse(ring.offer(13L));
        assertEquals(11L, ring.poll());
        assertEquals(12L, ring.poll());
        assertEquals(GAWorkHandle.NULL_HANDLE, ring.poll());
    }

    @Test
    void ownerRoutingKeepsSameFourByFourShardOnSameOwner() {
        GAWorkerTopology topology = new GAWorkerTopology(8);

        int owner = topology.owner(3, 32, -16);
        assertEquals(owner, topology.owner(3, 35, -13));
        assertTrue(owner >= 0 && owner < 8);
    }

    @Test
    void runtimeRunsSubmittedWorkOnV2WorkerAndShutsDown() throws Exception {
        GASchedulerRuntime runtime = new GASchedulerRuntime(config(2));
        runtime.start();
        try {
            CompletableFuture<String> threadName = runtime.submit(
                    GATaskClass.CPU_NOISE,
                    new GAChunkWorkKey(1, 0, 0, (byte) 1),
                    () -> Thread.currentThread().getName(),
                    false
            );

            assertTrue(threadName.get(10, TimeUnit.SECONDS).startsWith("GA-V2-"));
            assertTrue(runtime.snapshot().containsKey("taskClasses"));
        } finally {
            runtime.shutdown(true);
        }
    }

    @Test
    void arenaDependencyPublishesChildAfterParentCompletes() throws Exception {
        GASchedulerRuntime runtime = new GASchedulerRuntime(config(1));
        runtime.start();
        try {
            GAGenerationBatch batch = GAGenerationBatch.create(runtime);
            List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
            CompletableFuture<Void> childDone = new CompletableFuture<>();

            long parent = batch.addNode(new GAChunkWorkKey(1, 0, 0, (byte) 1), GATaskClass.CPU_NOISE, context -> {
                order.add("parent");
                return GAChunkGraphArena.RunState.COMPLETE;
            });
            long child = batch.addNode(new GAChunkWorkKey(1, 0, 0, (byte) 2), GATaskClass.CPU_NOISE, context -> {
                order.add("child");
                childDone.complete(null);
                return GAChunkGraphArena.RunState.COMPLETE;
            });
            batch.addDependency(parent, child);
            assertEquals(1, batch.submit());

            childDone.get(10, TimeUnit.SECONDS);
            assertEquals(List.of("parent", "child"), order);
        } finally {
            runtime.shutdown(true);
        }
    }

    @Test
    void workTableCoalescesDuplicateWorkAndCompletesEveryWaiter() throws Exception {
        GAMetrics metrics = new GAMetrics();
        GAChunkWorkTable table = new GAChunkWorkTable(16, metrics);
        GAChunkWorkKey key = new GAChunkWorkKey(1, 4, 8, (byte) 1);
        AtomicInteger starts = new AtomicInteger();
        CompletableFuture<String> source = new CompletableFuture<>();

        CompletableFuture<String> first = table.coalesce(key, () -> {
            starts.incrementAndGet();
            return source;
        });
        CompletableFuture<String> second = table.coalesce(key, () -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture("wrong");
        });

        source.complete("ok");

        assertEquals(1, starts.get());
        assertEquals("ok", first.get(10, TimeUnit.SECONDS));
        assertEquals("ok", second.get(10, TimeUnit.SECONDS));
        assertEquals(0, table.inFlight());
    }

    @Test
    void workTableRemovesCompletedEntriesAndAllowsNewWork() throws Exception {
        GAMetrics metrics = new GAMetrics();
        GAChunkWorkTable table = new GAChunkWorkTable(16, metrics);
        GAChunkWorkKey key = new GAChunkWorkKey(1, 2, 3, (byte) 1);

        CompletableFuture<String> first = table.coalesce(key, () -> CompletableFuture.completedFuture("done"));
        assertEquals("done", first.get(10, TimeUnit.SECONDS));

        CompletableFuture<String> late = table.coalesce(key, () -> CompletableFuture.completedFuture("late"));
        assertEquals("late", late.get(10, TimeUnit.SECONDS));
        assertEquals(0, table.inFlight());
    }

    @Test
    void resumePublishedDuringRunningNodeIsNotLost() throws Exception {
        GASchedulerRuntime runtime = new GASchedulerRuntime(config(1));
        runtime.start();
        try {
            GAGenerationBatch batch = GAGenerationBatch.create(runtime);
            AtomicInteger runs = new AtomicInteger();
            CompletableFuture<Void> completed = new CompletableFuture<>();
            batch.addNode(new GAChunkWorkKey(1, 10, 10, (byte) 1), GATaskClass.CPU_NOISE, context -> {
                if (runs.incrementAndGet() == 1) {
                    context.resume();
                    return GAChunkGraphArena.RunState.WAITING;
                }
                completed.complete(null);
                return GAChunkGraphArena.RunState.COMPLETE;
            });
            assertEquals(1, batch.submit());

            completed.get(10, TimeUnit.SECONDS);
            assertEquals(2, runs.get());
        } finally {
            runtime.shutdown(true);
        }
    }

    @Test
    void classifierAdmitsNoiseButKeepsWriterAndCommitStatusesLegacy() {
        GAWorkerConfig config = config(2);

        assertTrue(GAAffinityScheduler.classify(ChunkStatus.NOISE, config).admitted());
        assertFalse(GAAffinityScheduler.classify(ChunkStatus.FEATURES, config).admitted());
        assertFalse(GAAffinityScheduler.classify(ChunkStatus.SPAWN, config).admitted());
        assertFalse(GAAffinityScheduler.classify(ChunkStatus.FULL, config).admitted());
    }

    @Test
    void schedulerV2DefaultsToEnabledThroughputMode() {
        GAConfig config = new GAConfig();
        GAWorkerConfig workerConfig = GAWorkerConfig.from(config, 16, false);

        assertTrue(workerConfig.chunkSchedulerEnabled());
        assertEquals(GAWorkerConfig.Mode.PREGEN_THROUGHPUT, workerConfig.mode());
        assertEquals(15, workerConfig.workers());
    }

    @Test
    void batchDispatcherUsesLockFreeQueueAndRunsSubmittedTasks() throws Exception {
        GABatchDispatcher dispatcher = new GABatchDispatcher("GA-V2-DISPATCHER-TEST", 16);
        dispatcher.start();
        CompletableFuture<?>[] done = new CompletableFuture<?>[8];
        for (int i = 0; i < done.length; i++) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            done[i] = future;
            assertTrue(dispatcher.submit(() -> future.complete(null)));
        }

        CompletableFuture.allOf(done).get(10, TimeUnit.SECONDS);
        assertEquals(true, dispatcher.snapshot().get("lockFreeQueue"));
        dispatcher.shutdown();
        dispatcher.join(5_000L);
    }

    private static GAWorkerConfig config(int workers) {
        return new GAWorkerConfig(
                true,
                GAWorkerConfig.Mode.LIVE_BALANCED,
                workers,
                100_000,
                4,
                64,
                256,
                1024L * 1024L,
                4,
                256,
                1024,
                true,
                500_000L,
                64L * 1024L * 1024L,
                true,
                new String[0]
        );
    }
}

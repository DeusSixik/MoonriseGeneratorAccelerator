package dev.sixik.generator_accelerator.common.noise.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuIrPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuFillSliceMegaBatchDispatcherTest {

    @BeforeEach
    void setUp() {
        System.setProperty(GpuFillSliceMegaBatchDispatcher.ENABLED_PROPERTY, "true");
        System.setProperty(GpuFillSliceMegaBatchDispatcher.TARGET_POINTS_PROPERTY, "20");
        System.setProperty(GpuFillSliceMegaBatchDispatcher.MAX_QUEUED_JOBS_PROPERTY, "8");
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.PRESSURE_TARGET_POINTS_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.COMPILE_MAX_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.DISPATCH_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.ASYNC_PROBE_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.BACKGROUND_DISPATCH_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.PREFETCH_NEXT_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.PREFETCH_LEAD_CELLS_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.WRITEBACK_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.WRITEBACK_WAIT_NANOS_PROPERTY);
        GpuFillSliceMegaBatchDispatcher.reset();
    }

    @AfterEach
    void tearDown() {
        GpuFillSliceMegaBatchDispatcher.reset();
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.ENABLED_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.TARGET_POINTS_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.PRESSURE_TARGET_POINTS_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.MAX_QUEUED_JOBS_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.COMPILE_MAX_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.DISPATCH_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.ASYNC_PROBE_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.BACKGROUND_DISPATCH_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.PREFETCH_NEXT_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.PREFETCH_LEAD_CELLS_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.WRITEBACK_PROPERTY);
        System.clearProperty(GpuFillSliceMegaBatchDispatcher.WRITEBACK_WAIT_NANOS_PROPERTY);
    }

    @Test
    void drainsCompatibleJobsWhenTargetPointsReached() {
        GpuFillSliceMegaBatchDispatcher.Job first = job(payload(3, 0, 0, 0, false));
        GpuFillSliceMegaBatchDispatcher.Job second = job(payload(3, 0, 0, 0, false));

        assertEquals(GpuFillSliceMegaBatchDispatcher.EnqueueResult.QUEUED,
                GpuFillSliceMegaBatchDispatcher.enqueue(first));
        assertEquals(GpuFillSliceMegaBatchDispatcher.EnqueueResult.QUEUED,
                GpuFillSliceMegaBatchDispatcher.enqueue(second));
        first.markCompleted();
        second.markCompleted();

        GpuFillSliceMegaBatchDispatcher.Batch batch = GpuFillSliceMegaBatchDispatcher.drainReadyBatch();

        assertTrue(batch.ready());
        assertEquals(2, batch.jobs().size());
        assertEquals(20, batch.combinedPointCount());
        assertEquals(0, GpuFillSliceMegaBatchDispatcher.snapshotStats().queuedJobs());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainedBatches());
        assertEquals(2, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainedJobs());
        assertEquals(2, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainedBatchMaxJobs());
        assertEquals(20, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainedBatchMaxPoints());
        assertEquals(2, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainedPurePayloadJobs());
        assertEquals(20, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainedPurePayloadPoints());
        assertEquals(0, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainedExternPayloadJobs());
    }

    @Test
    void classifiesExternPayloadDrainedJobs() {
        GpuFillSliceMegaBatchDispatcher.Job first = job(payload(3, 1, 0, 0, false), 1);
        GpuFillSliceMegaBatchDispatcher.Job second = job(payload(3, 1, 0, 0, false), 1);

        GpuFillSliceMegaBatchDispatcher.enqueue(first);
        GpuFillSliceMegaBatchDispatcher.enqueue(second);
        first.markCompleted();
        second.markCompleted();

        assertTrue(GpuFillSliceMegaBatchDispatcher.drainReadyBatch().ready());
        assertEquals(0, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainedPurePayloadJobs());
        assertEquals(2, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainedExternPayloadJobs());
        assertEquals(20, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainedExternPayloadPoints());
        assertEquals(2, GpuFillSliceMegaBatchDispatcher.snapshotStats().externSnapshotJobs());
        assertEquals(20, GpuFillSliceMegaBatchDispatcher.snapshotStats().externSnapshotPoints());
        assertEquals(0, GpuFillSliceMegaBatchDispatcher.snapshotStats().externSnapshotMissingJobs());
    }

    @Test
    void countsExternPayloadJobsWithoutSnapshots() {
        GpuFillSliceMegaBatchDispatcher.enqueue(jobWithoutExternSnapshot(payload(3, 1, 0, 0, false), 1));

        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().externSnapshotMissingJobs());
        assertEquals(10, GpuFillSliceMegaBatchDispatcher.snapshotStats().externSnapshotMissingPoints());
    }

    @Test
    void readsDispatchPropertyAndRecordsProbeCounters() {
        assertFalse(GpuFillSliceMegaBatchDispatcher.dispatchEnabled());
        assertFalse(GpuFillSliceMegaBatchDispatcher.asyncProbeEnabled());
        assertFalse(GpuFillSliceMegaBatchDispatcher.backgroundDispatchEnabled());
        assertFalse(GpuFillSliceMegaBatchDispatcher.prefetchNextEnabled());
        assertEquals(2, GpuFillSliceMegaBatchDispatcher.prefetchLeadCells());
        assertFalse(GpuFillSliceMegaBatchDispatcher.writebackEnabled());
        assertEquals(0L, GpuFillSliceMegaBatchDispatcher.writebackWaitNanos());

        System.setProperty(GpuFillSliceMegaBatchDispatcher.DISPATCH_PROPERTY, "true");
        System.setProperty(GpuFillSliceMegaBatchDispatcher.ASYNC_PROBE_PROPERTY, "true");
        System.setProperty(GpuFillSliceMegaBatchDispatcher.BACKGROUND_DISPATCH_PROPERTY, "true");
        System.setProperty(GpuFillSliceMegaBatchDispatcher.WRITEBACK_PROPERTY, "true");
        assertFalse(GpuFillSliceMegaBatchDispatcher.prefetchNextEnabled());
        assertEquals(0L, GpuFillSliceMegaBatchDispatcher.writebackWaitNanos());
        System.setProperty(GpuFillSliceMegaBatchDispatcher.PREFETCH_NEXT_PROPERTY, "false");
        assertFalse(GpuFillSliceMegaBatchDispatcher.prefetchNextEnabled());
        System.setProperty(GpuFillSliceMegaBatchDispatcher.PREFETCH_NEXT_PROPERTY, "true");
        System.setProperty(GpuFillSliceMegaBatchDispatcher.PREFETCH_LEAD_CELLS_PROPERTY, "3");
        System.setProperty(GpuFillSliceMegaBatchDispatcher.WRITEBACK_WAIT_NANOS_PROPERTY, "123");
        GpuFillSliceMegaBatchDispatcher.recordDispatchAttempt(20);
        GpuFillSliceMegaBatchDispatcher.recordDispatchGpuSuccess(15);
        GpuFillSliceMegaBatchDispatcher.recordDispatchFailure(3);
        GpuFillSliceMegaBatchDispatcher.recordDispatchSkip(2);
        GpuFillSliceMegaBatchDispatcher.recordWriteback(10);
        GpuFillSliceMegaBatchDispatcher.recordWritebackMiss("gpu-not-ready", 5);
        GpuFillSliceMegaBatchDispatcher.recordWritebackWait(7L, false);
        GpuFillSliceMegaBatchDispatcher.recordWritebackWait(11L, true);
        GpuFillSliceMegaBatchDispatcher.recordPrefetchAttempt();
        GpuFillSliceMegaBatchDispatcher.recordPrefetchQueued();
        GpuFillSliceMegaBatchDispatcher.recordPrefetchDispatch();
        GpuFillSliceMegaBatchDispatcher.recordPrefetchConsumeAttempt();
        GpuFillSliceMegaBatchDispatcher.recordPrefetchHit();
        GpuFillSliceMegaBatchDispatcher.recordPrefetchWriteback();
        GpuFillSliceMegaBatchDispatcher.recordPrefetchMiss("start-after");
        double[] target = new double[1];
        GpuFillSliceMegaBatchDispatcher.recordLifecycleSwapSlices(16);
        GpuFillSliceMegaBatchDispatcher.recordLifecycleFillSlice(false, 5, target);
        GpuFillSliceMegaBatchDispatcher.recordLifecyclePrefetchStored(6, target);

        GpuFillSliceMegaBatchDispatcher.Stats stats = GpuFillSliceMegaBatchDispatcher.snapshotStats();
        assertTrue(stats.dispatchEnabled());
        assertTrue(stats.asyncProbeEnabled());
        assertTrue(stats.backgroundDispatchEnabled());
        assertTrue(stats.prefetchNextEnabled());
        assertEquals(3, stats.prefetchLeadCells());
        assertTrue(stats.writebackEnabled());
        assertEquals(123L, stats.writebackWaitNanos());
        assertEquals(1, stats.dispatchAttempts());
        assertEquals(20, stats.dispatchAttemptPoints());
        assertEquals(1, stats.dispatchGpuSuccesses());
        assertEquals(15, stats.dispatchGpuSuccessPoints());
        assertEquals(1, stats.dispatchFailures());
        assertEquals(3, stats.dispatchFailurePoints());
        assertEquals(1, stats.dispatchSkips());
        assertEquals(2, stats.dispatchSkipPoints());
        assertEquals(1, stats.writebackJobs());
        assertEquals(10, stats.writebackPoints());
        assertEquals(1, stats.writebackMissJobs());
        assertEquals(5, stats.writebackMissPoints());
        assertEquals(2, stats.writebackWaitAttempts());
        assertEquals(1, stats.writebackWaitSuccesses());
        assertEquals(18, stats.writebackWaitNanosTotal());
        assertEquals(1, stats.prefetchAttempts());
        assertEquals(1, stats.prefetchQueued());
        assertEquals(1, stats.prefetchDispatches());
        assertEquals(1, stats.prefetchConsumeAttempts());
        assertEquals(1, stats.prefetchHits());
        assertEquals(1, stats.prefetchWritebacks());
        assertEquals(List.of("start-after=1"), stats.prefetchMissReasons());
        assertEquals(1, stats.lifecycleSwapSlices());
        assertEquals(0, stats.lifecycleFillSlice0());
        assertEquals(1, stats.lifecycleFillSlice1());
        assertEquals(16, stats.lifecycleLastSwapCellStart());
        assertEquals(5, stats.lifecycleLastFillSlice1Start());
        assertEquals(6, stats.lifecycleLastPrefetchStart());
        assertEquals(System.identityHashCode(target), stats.lifecycleLastFillSlice1Target());
        assertEquals(System.identityHashCode(target), stats.lifecycleLastPrefetchTarget());
        assertEquals(List.of("gpu-not-ready=1"), stats.writebackMissReasons());
    }

    @Test
    void keepsIncompatibleJobsQueuedWithoutRequeueChurn() {
        GpuFillSliceMegaBatchDispatcher.Job first = job(payload(3, 0, 0, 0, false));
        GpuFillSliceMegaBatchDispatcher.Job incompatible = job(payload(4, 0, 0, 0, false));
        GpuFillSliceMegaBatchDispatcher.Job secondCompatible = job(payload(3, 0, 0, 0, false));

        GpuFillSliceMegaBatchDispatcher.enqueue(first);
        GpuFillSliceMegaBatchDispatcher.enqueue(incompatible);
        GpuFillSliceMegaBatchDispatcher.enqueue(secondCompatible);
        first.markCompleted();
        incompatible.markCompleted();
        secondCompatible.markCompleted();

        GpuFillSliceMegaBatchDispatcher.Batch batch = GpuFillSliceMegaBatchDispatcher.drainReadyBatch();

        assertTrue(batch.ready());
        assertEquals(2, batch.jobs().size());
        assertEquals(20, batch.combinedPointCount());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().queuedJobs());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().shapeBuckets());
        assertEquals(0, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainDeferredJobs());

        assertFalse(GpuFillSliceMegaBatchDispatcher.drainReadyBatch().ready());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().queuedJobs());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainUndersizedBatches());
    }

    @Test
    void pressureDrainIncludesRequiredJobWhenEnoughCompatiblePointsExist() {
        GpuFillSliceMegaBatchDispatcher.Job first = job(payload(3, 0, 0, 0, false));
        GpuFillSliceMegaBatchDispatcher.Job second = job(payload(3, 0, 0, 0, false));
        GpuFillSliceMegaBatchDispatcher.Job required = job(payload(3, 0, 0, 0, false));

        GpuFillSliceMegaBatchDispatcher.enqueue(first);
        GpuFillSliceMegaBatchDispatcher.enqueue(second);
        GpuFillSliceMegaBatchDispatcher.enqueue(required);

        GpuFillSliceMegaBatchDispatcher.Batch batch =
                GpuFillSliceMegaBatchDispatcher.drainReadyBatchIncluding(required, false);

        assertTrue(batch.ready());
        assertEquals(List.of(second, required), batch.jobs());
        assertEquals(20, batch.combinedPointCount());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().queuedJobs());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().pressureDrainAttempts());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().pressureDrainSuccesses());
        assertEquals(20, GpuFillSliceMegaBatchDispatcher.snapshotStats().pressureDrainPoints());
        assertTrue(GpuFillSliceMegaBatchDispatcher.snapshotStats().pressureDrainMissReasons().isEmpty());
    }

    @Test
    void pressureDrainRecordsBelowTargetMissReason() {
        GpuFillSliceMegaBatchDispatcher.Job required = job(payload(3, 0, 0, 0, false));
        GpuFillSliceMegaBatchDispatcher.enqueue(required);

        GpuFillSliceMegaBatchDispatcher.Batch batch =
                GpuFillSliceMegaBatchDispatcher.drainReadyBatchIncluding(required, false);

        assertFalse(batch.ready());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().pressureDrainAttempts());
        assertEquals(0, GpuFillSliceMegaBatchDispatcher.snapshotStats().pressureDrainSuccesses());
        assertEquals(List.of("below-target=1"),
                GpuFillSliceMegaBatchDispatcher.snapshotStats().pressureDrainMissReasons());
    }

    @Test
    void pressureDrainUsesDedicatedLowerTargetPoints() {
        System.setProperty(GpuFillSliceMegaBatchDispatcher.PRESSURE_TARGET_POINTS_PROPERTY, "10");
        GpuFillSliceMegaBatchDispatcher.Job required = job(payload(3, 0, 0, 0, false));
        GpuFillSliceMegaBatchDispatcher.enqueue(required);

        GpuFillSliceMegaBatchDispatcher.Batch batch =
                GpuFillSliceMegaBatchDispatcher.drainReadyBatchIncluding(required, false);

        assertTrue(batch.ready());
        assertEquals(List.of(required), batch.jobs());
        assertEquals(10, batch.combinedPointCount());
        assertEquals(0, GpuFillSliceMegaBatchDispatcher.snapshotStats().queuedJobs());
        assertEquals(10, GpuFillSliceMegaBatchDispatcher.snapshotStats().pressureDrainPoints());
    }

    @Test
    void defersJobsWithDifferentPointCounts() {
        GpuFillSliceMegaBatchDispatcher.Job first = job(5, payload(3, 0, 0, 0, false));
        GpuFillSliceMegaBatchDispatcher.Job incompatible = job(6, payload(3, 0, 0, 0, false));
        GpuFillSliceMegaBatchDispatcher.Job secondCompatible = job(5, payload(3, 0, 0, 0, false));

        GpuFillSliceMegaBatchDispatcher.enqueue(first);
        GpuFillSliceMegaBatchDispatcher.enqueue(incompatible);
        GpuFillSliceMegaBatchDispatcher.enqueue(secondCompatible);
        first.markCompleted();
        incompatible.markCompleted();
        secondCompatible.markCompleted();

        GpuFillSliceMegaBatchDispatcher.Batch batch = GpuFillSliceMegaBatchDispatcher.drainReadyBatch();

        assertTrue(batch.ready());
        assertEquals(5, batch.shapeKey().pointCount());
        assertEquals(2, batch.jobs().size());
        assertEquals(20, batch.combinedPointCount());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().queuedJobs());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().shapeBuckets());
        assertEquals(0, GpuFillSliceMegaBatchDispatcher.snapshotStats().drainDeferredJobs());
    }

    @Test
    void rejectsWhenQueueLimitIsReached() {
        System.setProperty(GpuFillSliceMegaBatchDispatcher.MAX_QUEUED_JOBS_PROPERTY, "1");

        assertEquals(GpuFillSliceMegaBatchDispatcher.EnqueueResult.QUEUED,
                GpuFillSliceMegaBatchDispatcher.enqueue(job(payload(3, 0, 0, 0, false))));
        assertEquals(GpuFillSliceMegaBatchDispatcher.EnqueueResult.QUEUE_FULL,
                GpuFillSliceMegaBatchDispatcher.enqueue(job(payload(3, 0, 0, 0, false))));
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().queuedJobs());
        assertEquals(1, GpuFillSliceMegaBatchDispatcher.snapshotStats().jobsRejected());
    }

    @Test
    void doesNotDrainIncompleteJobs() {
        GpuFillSliceMegaBatchDispatcher.Job first = job(payload(3, 0, 0, 0, false));
        GpuFillSliceMegaBatchDispatcher.Job second = job(payload(3, 0, 0, 0, false));

        GpuFillSliceMegaBatchDispatcher.enqueue(first);
        GpuFillSliceMegaBatchDispatcher.enqueue(second);

        assertFalse(GpuFillSliceMegaBatchDispatcher.drainReadyBatch().ready());
        assertEquals(2, GpuFillSliceMegaBatchDispatcher.snapshotStats().queuedJobs());

        first.markCompleted();
        assertFalse(GpuFillSliceMegaBatchDispatcher.drainReadyBatch().ready());

        second.markCompleted();
        assertTrue(GpuFillSliceMegaBatchDispatcher.drainReadyBatch().ready());
        assertEquals(0, GpuFillSliceMegaBatchDispatcher.snapshotStats().queuedJobs());
    }

    @Test
    void completedJobCapturesStableTargetSnapshot() {
        GpuFillSliceMegaBatchDispatcher.Job job = job(payload(3, 0, 0, 0, false));
        double[] target = job.target();
        for (int i = 0; i < target.length; i++) {
            target[i] = i + 0.25D;
        }

        job.markCompleted();
        target[1] = 999.0D;
        target[6] = 999.0D;

        assertEquals(1.25D, job.targetValuesSnapshot()[1]);
        assertEquals(6.25D, job.targetValuesSnapshot()[6]);
    }

    @Test
    void gpuCompletedJobCapturesStableOutputSnapshot() {
        GpuFillSliceMegaBatchDispatcher.Job job = job(payload(3, 0, 0, 0, false));
        double[] gpuOutput = new double[20];
        for (int i = 0; i < gpuOutput.length; i++) {
            gpuOutput[i] = i + 0.5D;
        }

        job.markGpuCompleted(gpuOutput, 1);
        gpuOutput[5] = 999.0D;
        gpuOutput[11] = 999.0D;

        assertEquals(5.5D, job.gpuValuesSnapshot()[0]);
        assertEquals(11.5D, job.gpuValuesSnapshot()[6]);
    }

    @Test
    void writebackCopiesGpuSnapshotToTargetRows() {
        GpuFillSliceMegaBatchDispatcher.Job job = job(payload(3, 0, 0, 0, false));
        double[] gpuOutput = new double[10];
        for (int i = 0; i < gpuOutput.length; i++) {
            gpuOutput[i] = 100.0D + i;
        }

        job.markGpuCompleted(gpuOutput, 0);
        assertTrue(job.writeGpuValuesToTarget());

        assertEquals(100.0D, job.target()[0]);
        assertEquals(104.0D, job.target()[4]);
        assertEquals(105.0D, job.target()[5]);
        assertEquals(109.0D, job.target()[9]);
    }

    @Test
    void jobTracksGpuDispatchInFlightWindow() {
        GpuFillSliceMegaBatchDispatcher.Job job = job(payload(3, 0, 0, 0, false));

        assertFalse(job.gpuDispatchStarted());
        assertFalse(job.gpuDispatchInFlight());
        assertFalse(job.backgroundDispatchSubmitted());

        job.markBackgroundDispatchSubmitted();
        job.markGpuDispatchStarted();

        assertTrue(job.backgroundDispatchSubmitted());
        assertTrue(job.gpuDispatchStarted());
        assertTrue(job.gpuDispatchInFlight());

        job.markGpuDispatchFinished();

        assertTrue(job.gpuDispatchStarted());
        assertFalse(job.gpuDispatchInFlight());
    }

    @Test
    void jobTracksRuntimeParityResult() {
        GpuFillSliceMegaBatchDispatcher.Job job = job(payload(3, 0, 0, 0, false));

        assertFalse(job.runtimeParityChecked());
        assertFalse(job.runtimeParityPassed());

        job.markRuntimeParity(true);

        assertTrue(job.runtimeParityChecked());
        assertTrue(job.runtimeParityPassed());

        job.markRuntimeParity(false);

        assertTrue(job.runtimeParityChecked());
        assertFalse(job.runtimeParityPassed());
    }

    @Test
    void readsDedicatedMegaBatchCompileMaxProperty() {
        assertEquals(4096, GpuFillSliceMegaBatchDispatcher.compileMax());

        System.setProperty(GpuFillSliceMegaBatchDispatcher.COMPILE_MAX_PROPERTY, "123");

        assertEquals(123, GpuFillSliceMegaBatchDispatcher.compileMax());
        assertEquals(123, GpuFillSliceMegaBatchDispatcher.snapshotStats().compileMax());
    }

    @Test
    void readsDedicatedPressureTargetPointsProperty() {
        assertEquals(20, GpuFillSliceMegaBatchDispatcher.targetPoints());
        assertEquals(20, GpuFillSliceMegaBatchDispatcher.pressureTargetPoints());
        assertEquals(20, GpuFillSliceMegaBatchDispatcher.snapshotStats().pressureTargetPoints());

        System.setProperty(GpuFillSliceMegaBatchDispatcher.PRESSURE_TARGET_POINTS_PROPERTY, "7");

        assertEquals(20, GpuFillSliceMegaBatchDispatcher.targetPoints());
        assertEquals(7, GpuFillSliceMegaBatchDispatcher.pressureTargetPoints());
        assertEquals(7, GpuFillSliceMegaBatchDispatcher.snapshotStats().pressureTargetPoints());
    }

    @Test
    void backgroundDispatchSubmitsOnlyWhenEnabled() throws InterruptedException {
        GpuFillSliceMegaBatchDispatcher.Job job = job(payload(3, 0, 0, 0, false));
        GpuFillSliceMegaBatchDispatcher.Batch batch = new GpuFillSliceMegaBatchDispatcher.Batch(
                List.of(job), job.shapeKey(), job.combinedPointCount());

        assertFalse(GpuFillSliceMegaBatchDispatcher.submitBackgroundDispatch(batch, ignored -> {
        }));
        assertFalse(job.backgroundDispatchSubmitted());

        System.setProperty(GpuFillSliceMegaBatchDispatcher.BACKGROUND_DISPATCH_PROPERTY, "true");
        assertTrue(GpuFillSliceMegaBatchDispatcher.submitBackgroundDispatch(batch, ignored -> {
        }));
        assertTrue(job.backgroundDispatchSubmitted());

        for (int i = 0; i < 50
                && GpuFillSliceMegaBatchDispatcher.snapshotStats().backgroundDispatchCompleted() == 0; i++) {
            Thread.sleep(10L);
        }

        GpuFillSliceMegaBatchDispatcher.Stats stats = GpuFillSliceMegaBatchDispatcher.snapshotStats();
        assertEquals(1, stats.backgroundDispatchSubmits());
        assertEquals(1, stats.backgroundDispatchAccepted());
        assertEquals(1, stats.backgroundDispatchStarted());
        assertEquals(1, stats.backgroundDispatchCompleted());
        assertEquals(0, stats.backgroundDispatchRejected());
        assertEquals(0, stats.backgroundDispatchFailures());
    }

    @Test
    void jobFillsCoordinatesFromSnapshot() {
        GpuFillSliceMegaBatchDispatcher.Job job = new GpuFillSliceMegaBatchDispatcher.Job(
                2, 3, 6, 6, 17L,
                64, -2, 4, -16, 8,
                0, null,
                new double[12],
                new GpuFillSliceMegaBatchDispatcher.CandidateRoot[]{
                        new GpuFillSliceMegaBatchDispatcher.CandidateRoot(0, payload(3, 0, 0, 0, false))
                });
        int[] blockX = new int[8];
        int[] blockY = new int[8];
        int[] blockZ = new int[8];

        job.fillCoordinates(blockX, blockY, blockZ, 1);

        assertEquals(0, blockX[0]);
        assertEquals(64, blockX[1]);
        assertEquals(64, blockX[6]);
        assertEquals(-128, blockY[1]);
        assertEquals(-112, blockY[3]);
        assertEquals(-8, blockZ[1]);
        assertEquals(-4, blockZ[4]);
    }

    private static GpuFillSliceMegaBatchDispatcher.Job job(GpuIrPayload payload) {
        return job(5, payload);
    }

    private static GpuFillSliceMegaBatchDispatcher.Job job(int pointCount, GpuIrPayload payload) {
        return job(pointCount, payload, 0);
    }

    private static GpuFillSliceMegaBatchDispatcher.Job job(GpuIrPayload payload, int externInputStride) {
        return job(5, payload, externInputStride);
    }

    private static GpuFillSliceMegaBatchDispatcher.Job job(int pointCount, GpuIrPayload payload, int externInputStride) {
        GpuFillSliceMegaBatchDispatcher.CandidateRoot[] roots = {
                new GpuFillSliceMegaBatchDispatcher.CandidateRoot(0, payload),
                new GpuFillSliceMegaBatchDispatcher.CandidateRoot(1, payload)
        };
        double[] externValuesSnapshot = externInputStride > 0
                ? new double[pointCount * roots.length * externInputStride]
                : null;
        return new GpuFillSliceMegaBatchDispatcher.Job(pointCount, 1, pointCount, pointCount, 0L,
                0, 0, 1, 0, 1,
                externInputStride, externValuesSnapshot,
                new double[pointCount * roots.length], roots);
    }

    private static GpuFillSliceMegaBatchDispatcher.Job jobWithoutExternSnapshot(
            GpuIrPayload payload,
            int externInputStride) {
        GpuFillSliceMegaBatchDispatcher.CandidateRoot[] roots = {
                new GpuFillSliceMegaBatchDispatcher.CandidateRoot(0, payload),
                new GpuFillSliceMegaBatchDispatcher.CandidateRoot(1, payload)
        };
        return new GpuFillSliceMegaBatchDispatcher.Job(5, 1, 5, 5, 0L,
                0, 0, 1, 0, 1,
                externInputStride, null,
                new double[10], roots);
    }

    private static GpuIrPayload payload(
            int nodes,
            int externInputs,
            int noisePermutations,
            int noiseOctaveData,
            boolean customOp) {
        int[] opcodes = new int[nodes];
        for (int i = 0; i < opcodes.length; i++) {
            opcodes[i] = customOp && i == 0 ? GpuIrPayload.CUSTOM_OP : GpuIrPayload.ADD;
        }
        return new GpuIrPayload(
                0,
                externInputs,
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                opcodes,
                new int[nodes],
                new int[nodes],
                new int[nodes],
                new int[nodes],
                new int[nodes],
                new double[nodes],
                new double[nodes],
                new double[nodes],
                new double[nodes],
                new int[noisePermutations],
                new double[noiseOctaveData]);
    }
}

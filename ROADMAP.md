# Generator Accelerator Roadmap

This roadmap focuses on keeping Generator Accelerator useful on ordinary CPUs while adding an optional GPU acceleration path for machines that have a supported OpenCL device.

## Core Principles

1. CPU first: every optimization must keep a fast pure-Java CPU path.
2. GPU optional: GPU acceleration must be a backend next to CPU DFC, not a replacement.
3. Fail closed: if GPU runtime, device selection, kernel compilation, or parity validation fails, generation falls back to CPU DFC.
4. Shared semantics: IR, optimizer, bounds, fingerprints, and parity tests are the source of truth. CPU and GPU emitters may duplicate backend code, but not behavior.
5. No native DFC backend: DFC should not depend on hand-written JNI/native libraries. GPU work should go through JavaToGpu or stay disabled.

## Active DFC Roadmap

### 0. Priority: Cross-Chunk GPU Mega-Batching

- Replace per-`NoiseChunk` / per-`fillSlice` GPU launches with a global cross-chunk collector.
- Batch work by compatible GPU payload shape across many chunks, roots, and slices before launching.
- Target large dispatches first (`64k+` points, preferably `256k-1M`) instead of `~1.9k` point slice batches.
- Keep CPU DFC as the immediate fallback when a queued job is needed before GPU output is ready.
- Use persistent/ring buffers for coordinates, extern inputs, scratch, and outputs; avoid per-launch allocation.
- Run GPU asynchronously: CPU prepares/falls back on current work while GPU drains previous mega-batches.
- Minimize readback to final slice/cache arrays only; do not round-trip intermediate node values.
- Keep diagnostics explicit:
  - queued jobs / dropped jobs / flushed jobs;
  - points queued / launched / read back;
  - wait vs fallback count when a consumer reaches an unfinished GPU job;
  - grouping efficiency by payload shape;
  - GPU busy time vs CPU fallback time.
- Do not copy C2ME's small-task model blindly: use it only as a reference for integration points, not dispatch granularity.

### 1. Stabilize CPU DFC

- Keep the current bytecode/hidden-class backend as the baseline backend.
- Keep lifecycle cache reset explicit on server start, datapack reload, and server stop.
- Use exact fingerprints for hidden-class reuse until broader shape reuse is proven safe.
- Keep `/dfc` and debug overlay diagnostics honest: cache size is not JVM class liveness.
- Continue removing native/JNI assumptions from DFC code and diagnostics.

### 2. Split Compiler Frontend From Backends

- Frontend pipeline:
  - build IR from `DensityFunction`;
  - run optimizer passes;
  - expand supported noise nodes;
  - compute bounds;
  - plan split/helper layout;
  - calculate fingerprints;
  - run GPU-readiness classification.
- Backend contract:
  - `BytecodeCpuBackend` for current CPU DFC;
  - future `JavaToGpuBackend` for eligible batch workloads;
  - future diagnostic/mock backend for CPU execution using GPU-shaped primitive payloads.

### 3. Add GPU-Ready Data Layout

- Convert GPU candidate IR into primitive arrays: opcodes, child indices, constants, and payload offsets.
- Pack noise and spline payloads into primitive arrays before attempting real GPU execution.
- Reject any graph requiring object dispatch, `DensityFunction.compute`, general object arrays, exceptions, allocation, or virtual/interface calls.
- Keep blockers visible in diagnostics so we know which IR families prevent GPU execution.

### 4. CPU Mirror Of GPU Payload

- Build a CPU evaluator that consumes the same primitive payload intended for GPU.
- Use it for parity tests without requiring a GPU.
- Validate small coordinate grids before benchmarking larger workloads.
- Keep this mirror as the primary safety net for future JavaToGpu kernels.

### 5. First JavaToGpu Backend Slice

- Target batch workloads first, not single-point `compute`.
- Start with arithmetic-only roots:
  - constants;
  - block coordinates;
  - binary/unary math;
  - clamp/min/max;
  - range choice;
  - Y-clamped gradient.
- Emit JavaToGpu kernels only for roots that pass eligibility and payload validation.
- Use CPU DFC for every blocked root.

### 6. Noise And Spline GPU Expansion

- Move inlined noise payloads to primitive arrays.
- Add JavaToGpu-compatible helper kernels for octave loops.
- Add spline table payloads after arithmetic/noise parity is stable.
- Keep strict floating-point parity thresholds and compare against CPU DFC and vanilla fallback where needed.

### 7. Runtime Selection

- Default backend order: CPU DFC first, GPU backend only when explicitly enabled or auto-selected after validation.
- GPU backend requirements:
  - JavaToGpu runtime present;
  - OpenCL device selected;
  - startup self-test passed;
  - kernel compile passed;
  - root eligibility passed;
  - parity probe passed where configured.
- If any requirement fails, mark GPU disabled for the current lifecycle and continue on CPU DFC.

## Current Implementation Checkpoints

- [x] DFC native/JNI references removed from compiler path.
- [x] Global compile cache no longer uses weak values for hidden-class bundles.
- [x] Hidden-class cache reuse switched to exact fingerprint for safety.
- [x] GPU-readiness classifier added as diagnostics-only layer.
- [x] Public `CompilationPlan` contract.
- [x] `BytecodeCpuBackend` extracted from `Compiler`.
- [x] Primitive GPU payload model for arithmetic-only IR.
- [x] CPU mirror evaluator for arithmetic GPU payload.
- [x] CPU mirror parity harness for arithmetic GPU payload.
- [x] First JavaToGpu arithmetic batch kernel.
- [x] Optional arithmetic GPU batch wrapper with CPU fallback.
- [x] Wire GPU batch execution into a real DFC `NoiseChunk.fillArray` workload behind `-Dga.dfc.gpu=true`.
- [x] Lazy GPU preflight and runtime GPU-vs-CPU-mirror parity gate for early batches.
- [x] Reusable thread-local coordinate, scratch, and runtime parity buffers for GPU batch execution.
- [x] Inline already-ready `CompiledDensityFunction` child payloads through `Invoke` boundaries.
- [x] Treat marker/cache boundaries as explicit primitive extern-input buffers for GPU payload batches.
- [x] Runtime-validate first JavaToGpu cell batches on OpenCL with parity passing at zero observed error.
- [x] Serialize JavaToGpu runtime launches to avoid parallel backend-scope lifecycle races.
- [x] Demote JavaToGpu INFO runtime records to DEBUG by default to avoid worldgen log stalls.
- [x] Disable per-NoiseChunk lazy cell-cache filler compilation by default; keep it opt-in and capped.
- [x] Build cross-chunk fill-slice GPU mega-batch collector behind an opt-in property.
- [x] Add queue/flush diagnostics for fill-slice mega-batching before enabling any hot-path dispatch.
- [x] Wire opt-in dry-run fill-slice producer into the mega-batch collector with explicit collection skip diagnostics.
- [x] Validate first in-game dry-run producer counters: collection attempts and accepted jobs work; initial run was compile-budget-limited.
- [x] Validate mega-batch compileMax dry-run: payload-ready roots scaled to 18k+ and queued jobs drained 4M+ points.
- [x] Add batch-level dry-run diagnostics for drained batches, max batch size, deferred jobs, and undersized drains.
- [x] Replace FIFO scan/requeue collector drain with shape-bucketed queues to avoid dry-run CPU churn.
- [x] Add pure-vs-extern drained payload diagnostics before enabling real cross-chunk GPU launches.
- [x] Add dry-run extern-input snapshot packing at enqueue time for cross-chunk GPU launch readiness.
- [x] Add gated mega-batch GPU dispatch probe using drained shape buckets and extern snapshots, without writing worldgen output yet.
- [x] Validate first mega-batch dispatch probe counters in-game: extern snapshots are complete and drained batches reach the GPU runtime.
- [x] Treat mega-batch runtime-busy as dispatch skip and keep probe launches out of the old small-batch warm adaptive-disable path.
- [x] Re-run mega-batch dispatch probe after busy/adaptive cleanup: no CPU fallback, no adaptive-disable gate, and GPU successes stay nonzero.
- [x] Add completion-gated mega-batch parity probe comparing GPU output against CPU-filled target arrays before any writeback.
- [x] Fix target-parity probe to snapshot CPU-filled target values at job completion instead of reading reused slice buffers later.
- [x] Validate mega-batch target parity in-game: runtime parity checks pass with max error near epsilon and failures stay zero.
- [x] Add async mega-batch probe: launch GPU from coordinate/extern snapshots before CPU target completion, then defer parity until CPU target snapshot is available.
- [x] Validate first async mega-batch probe in-game: async launch works, deferred parity passes, and CPU fallback remains zero.
- [x] Fix async probe accounting so jobs already CPU-completed before GPU completion are parity-checked immediately after GPU output snapshot.
- [x] Raise NeoForge dev mega-batch queue cap to avoid transient async producer drops while multiple shape buckets accumulate.
- [x] Re-run async mega-batch accounting: dispatch GPU success points match dispatch attempt points and QUEUE_FULL drops return to zero.
- [x] Normalize async probe success accounting back to batch-level counters while keeping deferred parity as validation-only events.
- [x] Re-run async accounting after batch-level success normalization: GPU successes count matches dispatch attempts and success points match attempt points.
- [x] Add guarded fill-slice mega-batch writeback: copy GPU snapshot into target and skip CPU fill only after runtime parity budget has passed.
- [x] Validate guarded writeback in-game: writeback jobs/points become nonzero after parity budget reaches zero, parity/fallback stay clean.
- [x] Add writeback miss diagnostics to separate parity-pending from gpu-not-ready cases before adding wait/defer policy.
- [x] Add first conservative writeback wait policy for current fill-slice jobs, gated by parity and a tiny opt-in wait budget.
- [x] Re-run writeback wait policy in-game: 100us polling produced zero successes and only added wait/undersized-drain overhead.
- [x] Gate writeback waiting on an actual in-flight GPU dispatch for the current job and split gpu-not-dispatched from gpu-not-ready misses.
- [x] Re-run in-flight-gated wait diagnostics: wait attempts dropped to zero and misses moved to gpu-not-dispatched.
- [x] Add consumer-pressure drain that can build a ready batch around the current fill-slice job after parity gate.
- [x] Re-run pressure-drain diagnostics: it can include the current job, but writeback is still capped near one current job per GPU launch.
- [x] Add pressure-drain miss-reason diagnostics to determine whether failed attempts are mostly below target or structurally blocked.
- [x] Re-run pressure-drain miss diagnostics: misses are almost entirely below-target.
- [x] Add dedicated pressureTargetPoints knob for current-job consumer pressure without lowering the main mega-batch target.
- [x] Re-run with pressureTargetPoints=65536: dispatches rose sharply, writeback stayed tiny, and fillSlice total time regressed.
- [x] Remove lower pressureTargetPoints from the default NeoForge dev run; keep the knob only for manual A/B tests.
- [x] Add foreground next-slice prefetch from swapSlices so a queued GPU job can exist before the next fillSlice consumes it.
- [x] Re-run next-slice prefetch diagnostics: jobs queued, but single-slot tracking produced zero prefetch hits.
- [x] Track prefetched next-slice jobs by target/start key so later fillSlice calls can consume queued prefetches.
- [x] Re-run keyed prefetch diagnostics: hits are still zero, so add consume miss reasons for slice/target/start mismatch.
- [x] Re-run prefetch consume diagnostics: swapSlices prefetch produced zero hits and mostly map-empty/start-before misses.
- [x] Disable foreground prefetch in the default NeoForge dev run; keep it as a manual diagnostic mode.
- [x] Re-run default no-prefetch baseline with lifecycle counters: fillSlice1 follows fillSlice0 by one cell-width step, but prefetch consume counters were just disabled-path noise.
- [x] Skip the prefetch consume path entirely when prefetchNext is disabled so default counters and hot-path work stay clean.
- [x] Add prefetch start-delta miss diagnostics so the next prefetch run reports how far swapSlices is from the later fillSlice start.
- [x] Add a Gradle-only diagnostic switch (-PgaFillSlicePrefetchNext=true) so foreground prefetch can be tested without enabling it in the default dev run.
- [x] Temporarily force prefetchNext in the NeoForge client dev args because the active IntelliJ/Gradle launch was not picking up the diagnostic flag.
- [x] Re-run forced prefetch diagnostics: prefetch queues jobs but still has zero hits; most keyed target matches are start-before-delta-1.
- [x] Parameterize prefetch lead cells and set the next diagnostic run to prefetchLeadCells=2 to test the observed one-cell offset directly.
- [x] Re-run lead=2 prefetch diagnostics: hits appear, but writeback remains tiny, so add hit-without-writeback miss reasons before changing dispatch policy.
- [x] Re-run lead=2 hit-without-writeback diagnostics: most matched prefetch jobs are still gpu-not-dispatched at consume time, so test prefetchLeadCells=3 before changing batch thresholds.
- [x] Re-run lead=3 diagnostics: matching collapses to zero hits with mostly start-after-delta-1, so restore lead=2.
- [x] Set the next diagnostic run to lead=2 with a slightly lower 245,760-point target to test whether near-threshold buckets can dispatch prefetched jobs earlier without the 65k pressure-target regression.
- [x] Re-run lead=2 at 245,760 target: matching remains good, but most prefetch hits are still gpu-not-dispatched, so restore the main 262,144 target and test a moderate 131,072 pressure target.
- [x] Re-run lead=2 with 131,072 pressure target: writeback improves slightly, but dispatch/runtime cost regresses fillSlice time, so remove the pressure override.
- [x] Add prefetch enqueue-skip diagnostics to explain why many swapSlices prefetch attempts never create queued jobs.
- [x] Re-run enqueue-skip diagnostics: most non-queued prefetch attempts have no GPU candidates, while matched hits still usually miss because their bucket is not dispatched.
- [x] Set the next diagnostic run to a middle 196,608 pressure target: lower than the 262,144 batch target but less aggressive than the regressed 131,072 run.
- [x] Re-run 196,608 pressure-target diagnostics: writeback remains tiny while GPU runtime/fillSlice time regresses, so remove pressure override.
- [x] Disable forced foreground prefetch in the default NeoForge dev run again; keep the instrumentation and lead knob for manual diagnostics only.
- [x] Add opt-in background mega-batch dispatch executor and counters while keeping the default NeoForge dev run on the synchronous dispatch path.
- [x] Re-run default background-off baseline: background counters stay zero and synchronous async mega-batch path remains stable.
- [x] Enable backgroundDispatch in the NeoForge dev run for the next A/B without foreground prefetch or pressure overrides.
- [ ] Find a better lifecycle anchor than swapSlices, or move to true background dispatch with explicit ownership/fallback rules.
- [ ] Longer-run JavaToGpu batch soak after serialized runtime launch fix; record fallback/perf results.
- [ ] Expand GPU payload support beyond arithmetic-only roots, starting with packed/fused noise and spline payloads.

## Non-Goals

- Do not replace CPU DFC with GPU-only execution.
- Do not require a video card for faster generation.
- Do not add hand-written native DFC libraries.
- Do not use shape-cache class reuse without exact-fingerprint safety.
- Do not run arbitrary Java object graphs on GPU.

# OpenCL Whole Chunk Noise Batching Design

Date: 2026-05-21

## Context

The current OpenCL finalDensity path proved that the DFC compiler can generate correct GPU code for the whole finalDensity graph. Diagnostic runs on a large synthetic request are valid and fast enough to keep using as correctness proof. The production runtime path is still disappointing because it is attached to `NoiseChunk` cell filling and therefore creates too many relatively small jobs.

The latest Y/Z slice batching improved throughput, but the stats still show thousands of GPU submissions for one generation window:

```text
slab VM stats: attempts=3238, elements=705742848, totalMs=25573.462, avgElemNs=36.2
finalDensity batch: calls=3242, attempts=3238, cells=1225056, elements=156807168
```

This means the pipeline now batches real work, but the unit of work is still too small for a GPU. The remaining cost is not only shader math; it is also queue submission, request setup, intermediate buffers, and CPU-side chunk finishing. Continuing to optimize per-cell or per-slice dispatch is the wrong priority.

C2ME's OpenCL path takes the opposite shape: it batches aligned chunks, builds one worldgen data blob, runs kernels that emit block data, and writes chunk sections. This project should reuse that architectural lesson, not copy the implementation directly. Generator Accelerator already has its own DFC compiler, OpenCL device context, generated finalDensity kernels, stats, mixin infrastructure, and flat block write path.

## Decision

Add a new whole-chunk OpenCL pipeline next to the existing finalDensity runtime. The current finalDensity column/slice batching remains as fallback, diagnostics, and compiler proof. The new path must move the production granularity from "a few `NoiseChunk` cells" to "one or more full chunks" and should eventually emit block IDs directly instead of reading density values back to the CPU.

The implementation order is:

1. **Single chunk density prototype:** one chunk-sized request computes finalDensity for the full chunk volume and returns density values for validation.
2. **Single chunk direct block output:** the GPU writes packed block state IDs and post-processing flags, then CPU only commits sections.
3. **Persistent chunk runtime:** cache programs, command queues, request layouts, and buffers so repeated chunk jobs do not rebuild transient state.
4. **Aligned multi-chunk batches:** expand from `1x1` to `2x2`, then `4x4`, with output splitting back into per-chunk section writes.
5. **Optional biome/cache/aquifer expansion:** add more worldgen data to the GPU only after direct block output is correct and faster than vanilla.

The first production candidate must be opt-in, disabled by default, and fail-soft. If the chunk path rejects a request or fails at runtime, terrain generation falls back to the existing path without corrupting chunk state.

## Non-Goals

- Do not try to make the current per-`NoiseChunk` slice path the final architecture.
- Do not start with a `4x4` chunk batch before a `1x1` direct block-output chunk is correct.
- Do not support `Blender` in the first chunk path. If blending is active, reject the chunk path and fall back.
- Do not move biome generation, aquifer internals, surface rules, or carving to GPU in the first milestone.
- Do not remove the existing finalDensity Y/Z slice runtime. It stays useful for diagnostics, fallback, and parity checks.
- Do not lower `dfc.opencl.finalDensityHybridMinSlotValues` as a production fix for small jobs.

## Architecture

Create a separate package:

```text
dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk
```

Core units:

- `DfcOpenClChunkRequest` describes the chunk batch: dimension/level seed context, min block Y, height, chunk positions, cell shape, flags, and memory limits.
- `DfcOpenClChunkOutputLayout` maps a linear GPU element index to chunk, section, local X/Y/Z, and output offset.
- `DfcOpenClChunkResult` owns the returned density or packed block output and exposes safe accessors for tests/writeback.
- `DfcOpenClChunkRuntime` performs eligibility checks, plan lookup, dispatch, validation, and failure reporting.
- `DfcOpenClChunkBlockWriter` commits packed GPU output into `ChunkAccess`/sections using existing fast section write infrastructure.
- `DfcOpenClChunkStats` records chunk-path calls, skips, attempts, timings, output bytes, and last skip/failure reasons.
- `DfcOpenClChunkBufferCache` reuses OpenCL buffers once the direct-output prototype is stable.

The new runtime can call into existing `DfcOpenClRuntime` and `DfcOpenClDeviceContext`, but it should not add more production complexity to the old per-cell entry points. If shared helper extraction becomes necessary, move small helpers into package-private utility classes instead of making `DfcOpenClRuntime` even larger.

## Pipeline

### Milestone A: Single Chunk Density Prototype

Flow:

```text
NoiseBasedChunkGenerator hook
  -> build DfcOpenClChunkRequest for one ChunkAccess
  -> reject unsupported Blender/generator/shape/memory
  -> compile or reuse finalDensity plan
  -> dispatch full chunk density kernel
  -> optional sampled CPU parity validation
  -> return density buffer to CPU
  -> CPU converts density to blocks and writes through existing chunk fill/write path
```

This milestone is not expected to be the final performance target. It exists to prove that one full chunk can be evaluated as a single GPU job and validated against vanilla coordinates.

For a 16x16 chunk with height 384, block-resolution output is:

```text
16 * 16 * 384 = 98,304 values
double density output = 786,432 bytes
int block output = 393,216 bytes
```

This is a safe size for a single chunk. The real risk is not the final output buffer; the risk is accidentally allocating a full slot buffer for every intermediate slot over the whole chunk. The prototype may use a density output buffer, but it must avoid turning every DFC slot into a chunk-sized persistent buffer unless validation shows the memory and runtime are acceptable.

### Milestone B: Single Chunk Direct Block Output

Flow:

```text
NoiseBasedChunkGenerator hook
  -> DfcOpenClChunkRuntime.tryFillSingleChunk(...)
  -> GPU evaluates final density and selected block decision logic
  -> GPU writes int output: blockStateId | flags
  -> DfcOpenClChunkBlockWriter writes sections
  -> fallback if validation or writeback fails
```

Direct block output is the first milestone that can realistically beat vanilla. The CPU must not read back all doubles and redo the expensive terrain decision loop. It should only split packed output into sections, mark post-processing where needed, and update the same observable chunk state vanilla would update.

The packed output format is:

```text
bits 0..30  = block state ID
bit 31      = post-processing flag for fluids/lighting-sensitive writes
```

If more flags are needed, reserve a separate sidecar flag buffer rather than shrinking the block state ID range silently.

### Milestone C: Persistent Runtime

After direct block output works, make repeated chunk jobs cheap:

- Reuse OpenCL context and command queues from `DfcOpenClDeviceContext`.
- Cache generated programs/kernels by plan fingerprint, output mode, and layout.
- Cache input/output buffers by byte size on the OpenCL owner thread.
- Keep stats for setup, enqueue, wait, readback, validation, and writeback separately.
- Add memory caps before allocation, not after `OutOfMemoryError` or OpenCL allocation failure.

This milestone is required before multi-chunk batches, because `2x2`/`4x4` magnifies buffer churn.

### Milestone D: Multi-Chunk Batches

Flow:

```text
chunk scheduler or generator hook
  -> collect aligned chunk positions
  -> reject missing neighbors or unsafe lifecycle states
  -> DfcOpenClChunkRequest.forAlignedBatch(2 or 4)
  -> one GPU dispatch writes one packed output buffer
  -> split output by chunk
  -> commit each chunk through DfcOpenClChunkBlockWriter
```

Start with `2x2` because it is large enough to amortize submission overhead and small enough to debug. Move to `4x4` only when:

- `1x1` direct output parity is stable.
- `2x2` output splitting and writeback are correct.
- Per-batch output and temporary buffers stay below the configured memory cap.
- Chunk lifecycle/ownership is proven safe for all chunks in the batch.

Expected final output sizes:

```text
1x1 int block output = 393,216 bytes at height 384
2x2 int block output = 1,572,864 bytes
4x4 int block output = 6,291,456 bytes
```

These final buffers are acceptable on the user's RTX 5070. Intermediate DFC buffers must still be minimized.

## Blender and FlatCache Policy

`BlendAlpha` and `BlendOffset` are chunk-local `NoiseChunk` caches. They are not world-global constants, and keeping references to them across chunks can leak chunk state or produce invalid values. The first whole-chunk path therefore accepts only `Blender` states equivalent to no blending.

Policy:

- If `Blender` is not empty/no-blending, reject the whole-chunk path with reason `blender`.
- If `BlendAlpha`/`BlendOffset` appear as raw extern markers in a chunk request, reject with reason `flatcache_unbound`.
- If a later milestone supports blending, it must build per-request 2D prefill tables and upload them as explicit request data, not retain Java `FlatCache` objects in a global plan.
- Uniform FlatCache tables may become constants for the request.
- Non-uniform tables may become bounded 2D buffers only when indexing and lifetime are covered by tests.

This keeps the optimized path safe while preserving a clear path to add blending support.

## Eligibility and Fallback Reasons

The chunk path must record one explicit reason for every skip:

```text
disabled
unavailable
broken
unsupported_generator
blender
flatcache_unbound
no_plan
no_waves
aquifer
shape
memory
validation
opencl
writeback
lifecycle
```

Fallback rules:

- Preflight rejection returns `false` and lets the original generator continue.
- OpenCL compile/dispatch failure records `opencl`; device-level failures may mark the chunk path broken for the session.
- Validation mismatch records `validation` and falls back before committing GPU output.
- Writeback failure records `writeback` and falls back unless partial writes have already happened. The writer must validate destination sections before the first mutation.
- Lifecycle/ownership uncertainty records `lifecycle`; this is especially important for multi-chunk batches.

## C2ME Lessons to Use

Use these ideas:

- Batch chunks, not individual cells.
- Build an explicit request data blob instead of reading mutable Java state from global caches.
- Keep OpenCL resources persistent and reuse buffers.
- Split GPU output into chunk sections with a dedicated writer.
- Reject blending in the first fast path.
- Add diagnostics that explain why the fast path did or did not run.

Do not copy these parts directly:

- C2ME's worldgen data model and kernels are not shaped around this DFC compiler.
- C2ME's chunk-status integration should not be adopted before a safe `1x1` generator hook works.
- C2ME's implementation assumes its own mod architecture and buffer ownership rules.

## Diagnostics

Add a separate stats line:

```text
DFC OpenCL chunk noise: calls=..., skipped=..., attempts=..., succeeded=..., failed=...,
chunks=..., batches=..., outputBytes=..., totalMs=..., avgChunkMs=..., avgBlockNs=...,
lastSkip=..., lastFailure=...
```

Also include timing breakdowns when tracing is enabled:

```text
setupMs / compileMs / uploadMs / kernelMs / readbackMs / validateMs / writebackMs
```

Manual commands may be added after the runtime exists:

- `/dfc opencl chunknoise check`
- `/dfc opencl chunknoise bench`
- `/dfc opencl chunknoise trace`

The first stats milestone is more important than commands; stats must show whether production generation is using the chunk path.

## Validation

Validation layers:

1. **Pure unit tests:** output layout, packed block accessors, memory cap math, stats skip counters.
2. **Fake writer tests:** section/local coordinate mapping and no partial mutation on invalid output.
3. **Sampled parity:** compare selected block coordinates against CPU/vanilla for no-blending chunks before commit.
4. **Manual command parity:** run existing finalDensity all-waves checks to ensure compiler diagnostics still pass.
5. **World visual validation:** generate new terrain, inspect caves, fluids, height transitions, and chunk borders.

The path remains disabled by default until direct block output passes parity and shows better wall-clock generation speed than vanilla on the user's workload.

## Acceptance Criteria

### Milestone A

- `DfcOpenClChunkRequest` and output layout are covered by unit tests.
- One full no-blending chunk can be evaluated as one OpenCL job in a diagnostic or guarded runtime path.
- Density output coordinates match vanilla sampled points.
- Existing finalDensity diagnostic commands still pass.
- Failure returns to the existing runtime path without partial chunk writes.

### Milestone B

- GPU emits packed block IDs for a full chunk.
- `DfcOpenClChunkBlockWriter` commits output to sections with tested local indexing.
- Sampled no-blending parity passes before commit when validation is enabled.
- Stats show chunk-path attempts/successes during generation.
- Wall-clock generation is not slower than the current Y/Z slice path.

### Milestone C

- Repeated chunk jobs reuse kernels and buffers.
- Stats separate setup/compile/upload/kernel/readback/writeback costs.
- Buffer allocation count drops after warmup.
- Memory cap rejects oversized requests before OpenCL allocation.

### Milestone D

- `2x2` aligned batches pass output splitting and sampled parity.
- `4x4` is attempted only after `2x2` is stable.
- Multi-chunk lifecycle checks prevent writes to chunks the pipeline does not own.
- Fallback remains per-batch, not global terrain corruption.

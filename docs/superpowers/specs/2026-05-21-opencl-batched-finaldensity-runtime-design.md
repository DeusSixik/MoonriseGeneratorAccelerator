# OpenCL Batched FinalDensity Runtime Design

Date: 2026-05-21

## Context

The existing OpenCL finalDensity diagnostics show that the generated all-waves final-output path is correct and fast when it receives a large batch, for example `cells=512` / `65536` elements. Runtime worldgen does not currently reach that path. `DfcOpenClStats` reported millions of calls with `attempts=0` and `lastSkip=runtime hybrid cell values 128 cannot reach minSlotValues=16384 before batching`, because `CompiledDensityFunction.dfc$fillCell` is invoked for one `NoiseChunk` cell at a time.

A single vanilla cell is only `cellWidth * cellWidth * cellHeight = 4 * 4 * 8 = 128` values. Dispatching OpenCL for that size would be dominated by enqueue/driver overhead, so the current guard is correct. The next runtime path must batch multiple `NoiseChunk` cells before dispatching OpenCL.

The previous FlatCache 2D design remains valid for `BlendAlpha` / `BlendOffset`, but those chunk-local `FlatCache` externs only exist after `NoiseChunk.wrap`. Router-level diagnostic commands use raw `DensityFunctions$BlendAlpha` / `DensityFunctions$BlendOffset` placeholders, so they cannot prove the runtime FlatCache path by themselves.

## Decision

Build a batched runtime finalDensity path at the `NoiseChunk` cell-cache layer rather than lowering the per-cell threshold. The runtime should gather enough cells from a real `NoiseChunk` to amortize GPU dispatch, use the already rebound chunk-local externs, and fall back to the current CPU cell-fill behavior if any precondition fails.

The first production-oriented batch shape is a vertical cell column for one active `cellX, cellZ` pair:

- Batch cells: all `cellY` values in the current column.
- Element count: `cellCountY * cellWidth * cellWidth * cellHeight`.
- With typical overworld settings this is about `32 * 128 = 4096` values.
- Slot-value count: `elementCount * scheduledSlotCount`, which is expected to exceed the current `16384` minimum once a finalDensity-sized plan is available.

This is less invasive than batching the full chunk, matches the current terrain loop order, and avoids changing block placement logic.

## Runtime Flow

1. `NoiseChunk.selectCellYZ(y, z)` remains the public entry point used by terrain fill.
2. When the first eligible `CacheAllInCell` filler for a `cellX, cellZ` column is encountered, the mixin asks the OpenCL runtime whether it can batch that filler for the whole vertical column.
3. The OpenCL runtime checks:
   - OpenCL enabled and healthy.
   - The filler is a `CompiledDensityFunction` with a registered OpenCL plan.
   - The rebound plan after `NoiseChunk.wrap` has schedulable waves.
   - The batch size meets `dfc.opencl.finalDensityHybridMinSlotValues` after multiplying by scheduled slots.
   - Chunk-local externs are either GPU-computable, constants, FlatCache 2D, or safe CPU fallback inputs for the selected path.
4. If accepted, the runtime computes the whole vertical column into a temporary batch buffer.
5. The `NoiseChunk` mixin stores the batched cell values in a small per-column cache.
6. Each later `selectCellYZ(y, z)` copies the relevant `128` values from the batch cache into the vanilla `CacheAllInCell.values` array.
7. If any check or dispatch fails, the code records a diagnostic reason and uses the existing `dfc$fillCell` / `fillArray` path for that cell.

## Coordinate Layout

The existing diagnostic request layout treats `cell` as a synthetic X/Z grid index. Runtime batching needs a real `NoiseChunk` layout:

- `firstBlockX = chunk.cellStartBlockX` for the active `cellX`.
- `firstBlockZ = chunk.cellStartBlockZ` for the active `cellZ`.
- `cellY` varies over the batch instead of synthetic X/Z cells.
- Element order must match `NoiseChunk.CacheAllInCell.values`: local X, local Z, then descending local Y for each cell.
- Batch output stores cells contiguously by `cellY` so copying one cell is a single `System.arraycopy`.

The layout helper should be separate from the existing benchmark helper to avoid changing benchmark semantics.

## FlatCache 2D Handling

The batched runtime path should reuse the existing FlatCache 2D classifier and prefill kernel where possible. The important difference is that runtime plans use rebound externs from `NoiseChunk.wrap`, so `BlendAlpha` / `BlendOffset` should appear as `NoiseChunk.FlatCache` instances rather than raw marker placeholders.

Fail-safe rules stay the same:

- Uniform FlatCache tables become constants.
- Valid non-uniform FlatCache tables use GPU prefill.
- Invalid or unknown externs force CPU fallback for that batch.
- A failed prefill compile/upload/dispatch disables only the batch attempt and records the fallback reason.

## Diagnostics

Extend runtime stats so manual testing can distinguish all major states:

- `hybridBatchCalls`: times the batch API was considered.
- `hybridBatchAttempts`: OpenCL batch dispatch attempts.
- `hybridBatchSucceeded`: successful batch dispatches.
- `hybridBatchFailed`: failed batch dispatches.
- `hybridBatchCells`: total cells included in successful/attempted batches.
- `hybridBatchElements`: total output values included in successful/attempted batches.
- `hybridBatchLastSkip`: latest rejection reason.
- Optional FlatCache counters for the latest or accumulated batches: slots, buffers, bytes, fallback reason.

Keep the existing per-cell `hybridCalls` counters until the old path is removed; they help prove that per-cell dispatch is still being skipped for size.

## Error Handling

The batch path must never corrupt terrain generation:

- On preflight rejection, do nothing and let the existing CPU path run.
- On OpenCL exception, record failure, mark the finalDensity hybrid path broken for this session only if the failure is device/runtime-level; otherwise skip that batch.
- On validation mismatch when parity is enabled, fall back and report the mismatch.
- Never reuse a batch cache after `advanceCellX`, `selectCellYZ` for a different `z`, or `swapSlices` changes the `NoiseChunk` state.

## Testing Plan

Unit tests:

- Runtime batch sizing accepts a `cellCountY` column where `cellCountY * cellWidth * cellWidth * cellHeight * scheduledSlots >= minSlotValues`.
- Runtime batch sizing rejects a single `128`-value cell with the default `16384` threshold.
- New runtime coordinate helper maps batch cell/local indices to the same block coordinates as repeated single-cell `NoiseChunk` evaluation.
- Batch output copy extracts exactly one cell into `CacheAllInCell.values` order.
- Stats record batch skip, attempt, success, and failure independently from per-cell stats.

Integration/manual tests:

- `/dfc opencl stats reset`, generate new chunks, `/dfc opencl stats` should show `hybridBatchCalls > 0`.
- With default threshold, batch attempts should be non-zero only when the batch crosses the slot-value minimum.
- Terrain generation must remain visually correct and must not trip existing parity checks.
- Existing diagnostic commands must still pass: `compiledfinaldensityallwavesoutputcheck 512`, trace bench, and no-read bench.

## Scope

In scope:

- Batched finalDensity runtime dispatch for real `NoiseChunk` cell-cache fills.
- Column-sized batch layout and copy-back cache.
- Runtime diagnostics for batch eligibility and execution.
- Reuse of existing FlatCache 2D classification/fallback in the batched path where direct external prefill is needed.

Out of scope for this step:

- Full chunk or multi-chunk GPU terrain generation.
- Lowering `dfc.opencl.finalDensityHybridMinSlotValues` as a production solution.
- GPU implementation of `Blender` internals.
- Rewriting `NoiseBasedChunkGenerator.doFill` block placement logic.


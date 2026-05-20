# OpenCL FlatCache 2D External Inputs Design

Date: 2026-05-20

## Context

`DensityFunctions.BlendAlpha` and `DensityFunctions.BlendOffset` are identity-significant vanilla sentinels. Their raw `compute()` values are constant (`1.0` and `0.0`), but `NoiseChunk` replaces them during `wrap` with chunk-local `FlatCache` instances filled from `blender.blendOffsetAndFactor(x, z)`. Therefore OpenCL must not treat every `BlendAlpha` / `BlendOffset` external as globally constant.

A safety fix already prevents the generic `minValue() == maxValue()` shortcut from firing for `NoiseChunk` cache wrappers. The next optimization should preserve that fallback while avoiding expensive per-element CPU external prefill when real FlatCache data is available.

## Decision

Implement a classifier for OpenCL external input slots:

1. `constant`: safe raw constants or FlatCache arrays that are actually uniform.
2. `flatCache2d`: recognized chunk-local `NoiseChunk.FlatCache` data with an accessible flat array.
3. `cpuFallback`: anything unknown, unsupported, or failed at runtime.

The preferred path is `flatCache2d`, but it must be fail-safe: if detection, validation, upload, kernel compilation, or execution fails, the runtime falls back to existing CPU external prefill.

## Why Not Implement Blender On GPU

The GPU should not reimplement `Blender`. Blender depends on `WorldGenRegion` / nearby old chunk `BlendingData`, and it is not a world-global constant. It is effectively local to generation context. Vanilla already computes the correct chunk-local result into `NoiseChunk.FlatCache`; OpenCL should consume that prepared table instead of duplicating Blender logic.

## Data Model

For each recognized FlatCache external:

- Source data: `NoiseChunk$FlatCache$FlatArray.bts$getArray()`.
- Shape: `side = noiseChunk.noiseSizeXZ + 1`.
- Length: `side * side`.
- Coordinates: `flatIndex = (quartX - firstNoiseX) * side + (quartZ - firstNoiseZ)`.
- Values are 2D over quart X/Z and independent of block Y.

The runtime must not guess `firstNoiseX`, `firstNoiseZ`, or `side` from the OpenCL request alone. Add or extend a small access interface implemented by the `NoiseChunk.FlatCache` mixin so the OpenCL runtime can read the exact flat array metadata used by `FlatCache.compute()`.

Multiple external slots may reference the same FlatCache instance. The runtime should deduplicate by object identity and upload one GPU buffer per distinct FlatCache table.

## Execution Flow

1. Build the usual finalDensity slot plan.
2. Classify direct external input slots:
   - If the extern is a trustworthy constant, use the existing constant/direct prefill path.
   - If the extern is a `NoiseChunk` cache wrapper with a valid flat array, classify as `flatCache2d`.
   - Otherwise classify as `cpuFallback`.
3. For `flatCache2d` slots:
   - Upload each distinct flat table to a small read-only OpenCL buffer.
   - Run a small prefill kernel that writes the existing compact slot-major `slotBuffer` layout.
   - Keep the downstream wave/final kernels unchanged; they still read from `external_slots`.
4. For mixed input slots:
   - Constants can be filled directly.
   - FlatCache slots are filled by the prefill kernel.
   - Unknown slots force CPU fallback for the direct external prefill stage unless a later design adds mixed CPU/GPU partial fill.

## Empty Blender Fast Path

Do not rely solely on `Blender.empty()` identity. Instead prefer data-based decisions:

- Raw `BlendAlpha` / `BlendOffset` sentinels remain constants.
- Actual FlatCache arrays are scanned for uniform values.
- If `blendAlpha` is all `1.0` and `blendOffset` is all `0.0`, no GPU FlatCache upload is needed.
- If a non-empty Blender produces uniform data for a specific chunk, it still benefits from the constant path.

## Error Handling

The FlatCache 2D path must never break terrain generation:

- If flat array is null, too short, or side metadata is invalid, use CPU fallback.
- If OpenCL buffer allocation/upload fails, use CPU fallback.
- If generated prefill kernel compilation or execution fails, use CPU fallback and record a diagnostic reason.
- Existing validation should compare final output against CPU reference when validation is enabled.

Diagnostics should report at least:

- `flatCache2dSlots`
- `flatCache2dBuffers`
- `flatCache2dBytes`
- `flatCache2dFallbackReason` when applicable

## Testing Plan

Unit tests:

- Classifier treats raw constants as `constant`.
- Classifier treats uniform FlatCache-like externs as `constant` only after scanning actual values.
- Classifier treats non-uniform FlatCache-like externs as `flatCache2d`.
- Classifier falls back for cache wrappers with missing or invalid arrays.
- Generated FlatCache prefill source computes the same slot-major values as CPU prefill.

Runtime/diagnostic tests:

- Existing finalDensity OpenCL tests continue to pass.
- Trace output includes FlatCache 2D counters when applicable.
- CPU fallback remains available and correct.

Manual benchmark targets:

- Empty/default blending should keep the current constant fast path.
- Non-uniform blending should avoid per-element CPU prefill for BlendAlpha/BlendOffset.
- No regressions in non-trace finalDensity bench when no FlatCache 2D inputs are present.

## Scope

In scope:

- FinalDensity OpenCL external input handling.
- `BlendAlpha` / `BlendOffset` FlatCache arrays.
- A fail-safe GPU prefill stage that writes existing slot buffers.

Out of scope:

- Full GPU implementation of `Blender`.
- World-global Blender caching.
- General support for every `NoiseChunk.FlatCache` marker beyond the BlendAlpha/BlendOffset use case unless it naturally shares the same interface and tests.

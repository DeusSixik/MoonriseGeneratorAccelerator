# Generator Accelerator Roadmap

This roadmap focuses on keeping Generator Accelerator useful on ordinary CPUs while adding an optional GPU acceleration path for machines that have a supported OpenCL device.

## Core Principles

1. CPU first: every optimization must keep a fast pure-Java CPU path.
2. GPU optional: GPU acceleration must be a backend next to CPU DFC, not a replacement.
3. Fail closed: if GPU runtime, device selection, kernel compilation, or parity validation fails, generation falls back to CPU DFC.
4. Shared semantics: IR, optimizer, bounds, fingerprints, and parity tests are the source of truth. CPU and GPU emitters may duplicate backend code, but not behavior.
5. No native DFC backend: DFC should not depend on hand-written JNI/native libraries. GPU work should go through JavaToGpu or stay disabled.

## Active DFC Roadmap

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
- [ ] Longer-run JavaToGpu batch soak after serialized runtime launch fix; record fallback/perf results.
- [ ] Expand GPU payload support beyond arithmetic-only roots, starting with packed/fused noise and spline payloads.

## Non-Goals

- Do not replace CPU DFC with GPU-only execution.
- Do not require a video card for faster generation.
- Do not add hand-written native DFC libraries.
- Do not use shape-cache class reuse without exact-fingerprint safety.
- Do not run arbitrary Java object graphs on GPU.

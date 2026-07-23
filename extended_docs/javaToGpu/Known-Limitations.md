# Known Limitations

JavaToGpu is an alpha project. It is already useful for GPU-kernel experiments, but it is intentionally smaller than full Java.

## Big Picture

- JavaToGpu does not move an entire Java application to the GPU automatically.
- GPU code must be written in the supported `@GPU` subset.
- Unsupported code should fail with a diagnostic instead of producing surprising GPU behavior.
- Runtime behavior is still being validated across vendors and drivers.

## Kernel Code Limits

Inside `@GPU` methods, keep code simple and GPU-oriented:

- `@GPU` entry methods return `void`; write results to output buffers.
- General object allocation and object graphs are not supported.
- Virtual/interface dispatch and arbitrary Java library calls are not supported.
- Exceptions, recursion, monitors, and synchronization blocks are not supported.
- Object arrays are not supported as a general kernel data model.

Use `GPU.*` builtins for indexing, math, barriers, images, and other GPU operations.

## Data Model Limits

- Arrays inside `@GPUStruct` fields are not supported.
- General Java unions or arbitrary memory overlays are not supported.
- Some low-level layouts require explicit pointer views, qualifiers, or packed-buffer offsets.
- Image helper coverage is alpha-level; not every OpenCL image family has a polished host-side helper yet.

## Backend Limits

- OpenCL is the active backend.
- CUDA, Vulkan, and Metal are planned directions, but not current user backends.
- NVIDIA OpenCL and AMD OpenCL are currently validated on real hardware.
- Intel OpenCL should still be treated as a hardware-specific validation target until tested on a real runner.

## ASM Input Limits

The ASM path is not a general JVM decompiler. It is useful when another tool intentionally emits bytecode that matches JavaToGpu's supported subset.

If you want to experiment with bytecode input, run the ASM report APIs first and treat their output as a migration checklist.

## Stability Limits

- Public APIs can change before beta.
- Generated launcher names and shapes can change before beta. Dynamic/framework integrations should prefer `GpuGeneratedLauncherInvoker` over hardcoding generated class names.
- Validation reports prove the tested commit, machine, driver, and backend, not universal GPU behavior.
- Performance tuning is still early; prefer correctness validation before benchmarking.

## Practical Advice

Start with small kernels, add one feature at a time, and keep a CPU reference implementation for tests. When something fails, check [Troubleshooting](Troubleshooting.md) before assuming it is a driver problem.

## Related Documents

- [Getting Started](Getting-Started.md)
- [Language Contract](Language-Contract.md)
- [OpenCL Data Model](OpenCL-Data-Model.md)
- [Troubleshooting](Troubleshooting.md)

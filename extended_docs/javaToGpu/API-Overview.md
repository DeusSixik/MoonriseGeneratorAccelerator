# API Overview

This page gives a practical map of the JavaToGpu API for normal application code.

If you are writing an application, the usual import path is small: `api.JavaToGpu` for runtime scopes, `api.GPU` for kernel builtins, `api.annotations.*` for kernel metadata, and grouped data wrappers only when arrays are not enough. Use the lower-level `runtime` package when you need explicit selection policy, descriptors, dynamic launcher invocation, or extension/SPI work.

## Packages You Will Use

- `net.sixik.ga_utils.javatogpu.api` - thin user-facing root facade: `JavaToGpu`, `GpuScope`, kernel-facing `GPU`, and backend/device target selectors.
- `net.sixik.ga_utils.javatogpu.api.annotations` - annotations for marking kernels, parameters, structs, helpers, intrinsics, attributes, and qualifiers.
- `net.sixik.ga_utils.javatogpu.api.types.*` - unsigned scalar aliases and vector wrappers grouped by primitive family.
- `net.sixik.ga_utils.javatogpu.api.pointers.*` - helper pointers and address-space pointer views.
- `net.sixik.ga_utils.javatogpu.api.images` - image wrappers and samplers.
- `net.sixik.ga_utils.javatogpu.runtime` - lower-level runtime scopes, backend selection, launch configs, descriptors, compile options, and invocation helpers for advanced users and extensions.
- `net.sixik.ga_utils.javatogpu.runtime.selection` - domain entry points for advanced backend/device discovery, policy selection, and preflight diagnostics.

Current alpha note: `JavaToGpu` is the preferred user-facing runtime facade for common application flows. `net.sixik.ga_utils.javatogpu.runtime.GpuRuntime` remains supported as the lower-level compatibility entry point for current users, generated launchers, advanced configuration, and extension modules.

For advanced backend/device diagnostics, prefer `runtime.selection.GpuRuntimeSelection` over adding new code directly to the root `runtime` package. It delegates to the compatibility runtime APIs today and gives selection-focused code a stable package home while the large root runtime package is split gradually.

Run the facade example without launching a kernel:

```powershell
.\gradlew.bat :examples-app:runRuntimeFacadeExample --console=plain
```

## Source Annotations

Use these in source code that should compile to GPU code.

### Entry Points And Helpers

- `@GPU` marks a static Java method as a GPU kernel entry point.
- `@CCode` marks a reusable helper method that should be emitted as GPU helper code.
- `@CCodeLibrary` groups reusable helper methods.
- `@GPUIntrinsic` maps a Java method to a backend intrinsic instead of a normal helper call.

### Memory And Data Layout

- `@GPUGlobal`, `@GPUConstant`, and `@GPULocal` choose the OpenCL address space for array or pointer-like parameters.
- `@GPUStruct` marks a Java class as a value type that can be marshalled to OpenCL struct layout.
- `@GPUConstantData` and `@GPUExternConstantData` describe generated or external constant data metadata.

### Portable Codegen Hints

- `@GPUWorkGroupSize` declares a portable required work-group size for the kernel.
- `@GPUWorkGroupSizeHint` declares a portable preferred work-group size hint for backends that support it.
- `@GPUVectorTypeHint` declares a portable preferred vector type hint for backend lowerers that use it.
- `@GPUPacked`, `@GPUAligned`, and `@GPUAlwaysInline` cover common layout and helper emission metadata without raw backend strings.

### Runtime Selection And Tests

- `@GPUDeviceConstraint` restricts a method to supported backends, vendors, device classes, and required runtime features.
- `@GPUFallbackVariant` groups ABI-compatible implementations that runtime may choose for different devices.
- `@GPUTest` and `@GPUTests` attach fixture-based method-test metadata used by manual probes today and future runtime evidence.

### Optimizer Policy

- `@GPUOptimize` records method-level optimizer policy such as enablement/profile hints, `fastMath`, family toggles, journal/dump hints, production intent, vendor adaptation, vectorization preference, and resource-shaping intent; the default remains strict and fail-closed.

### Extension Metadata And Escape Hatches

- `@GPUIntrinsicLibrary`, `@GPUVectorType`, `@GPUScalarAliasType`, `@GPUPointerType`, `@GPUPointerAddressSpace`, `@GPUPointerOperator`, and `@GPUVectorOperator` are mostly for built-in wrappers and extension libraries.
- `@GPUAttribute` is the backend-aware raw escape hatch for metadata JavaToGpu does not model portably yet.
- `@OpenCLAttributes` and `@OpenCLQualifiers` remain OpenCL-only compatibility annotations for existing code.

Prefer portable annotations first. Use raw attributes only for backend-specific code that cannot be expressed through the normal API. When a raw OpenCL attribute maps to a modeled concept but is placed in an invalid context, validator diagnostics point to the portable annotation replacement instead of making users decode backend-specific attribute rules.

```java
@GPU
@GPUWorkGroupSize(x = 8, y = 8, z = 1)
@GPUWorkGroupSizeHint(x = 8, y = 8, z = 1)
@GPUOptimize(fastMath = false, enabledFamilies = {"clamp", "step", "mix"})
static void kernel(@GPUGlobal float[] output) {
    output[GPU.get_global_id(0)] = 1.0f;
}
```

`@GPUOptimize` records intent in the generated `IrGpu` manifest. It does not by itself enable production mutation, selected IR replacement, or optimized backend source selection.

Use a device constraint when a method requires specific hardware capabilities:

```java
@GPU
@GPUDeviceConstraint(
        vendors = {GpuVendorTarget.NVIDIA, GpuVendorTarget.AMD},
        deviceClasses = {GpuDeviceClassTarget.DGPU},
        requiredFeatures = {"fp64"}
)
static void doubleKernel(@GPUGlobal double[] output) {
    output[GPU.get_global_id(0)] = 1.0;
}
```

If no discovered device satisfies the constraint, runtime selection throws `GpuRuntimeDeviceSelectionException` before kernel compilation.

Use fallback variants when the same logical operation needs different implementations for dGPU and iGPU devices:

```java
@GPU
@GPUFallbackVariant(group = "noise", id = "dgpu", priority = 100)
@GPUDeviceConstraint(deviceClasses = {GpuDeviceClassTarget.DGPU})
static void noiseDiscrete(@GPUGlobal float[] output) {
    output[GPU.get_global_id(0)] = expensiveScalarPath();
}

@GPU
@GPUFallbackVariant(group = "noise", id = "igpu", priority = 10)
@GPUDeviceConstraint(deviceClasses = {GpuDeviceClassTarget.IGPU})
static void noiseIntegrated(@GPUGlobal float[] output) {
    output[GPU.get_global_id(0)] = memoryFriendlyPath();
}
```

All methods in a fallback group must have the same parameter count, Java parameter types, and GPU access modes. Runtime first chooses the best compatible device, then uses variant priority and stable ids as tie-breakers. Explicit device overrides remain mandatory constraints. An active OpenCL scope does not switch to another device; create a new scope/backend when a later call requires different hardware.

Fallback variants may live in another library or Gradle module. The annotation processor generates a `GpuRuntimeMethodVariantProvider` and `META-INF/services` registration for every owner that declares fallback variants. Runtime discovers those providers through the generated launcher's classloader, so a dependency JAR can contribute another implementation without the application importing or calling that implementation directly. Conflicting group/variant ids fail closed.

When using strict named JPMS modules, add an explicit `provides GpuRuntimeMethodVariantProvider with ...` bridge if the generated service resource is not visible through the module layer. Normal classpath and automatic-module usage requires no additional configuration.

## Startup Device Self-Tests

OpenCL can validate discovered devices before final selection. Configure the behavior through `GpuRuntimeCompileOptions`:

```java
GpuRuntimeCompileOptions options = GpuRuntimeCompileOptions
        .defaults(GpuBackendTarget.OPENCL)
        .withDeviceSelfTestMode(GpuRuntimeDeviceSelfTestMode.REQUIRED);
```

- `AUTO` runs the self-test when multiple devices are discovered.
- `DISABLED` skips self-test execution and cached evidence.
- `REQUIRED` rejects any candidate without passed correctness evidence.

The runtime chooses smaller workloads for iGPU and CPU OpenCL devices. Unified-memory iGPUs use a separate transfer model, and performance rates are compared only with compatible workload and transfer profiles. Compute and transfer timing are scored independently, so noise in one metric does not erase stable evidence from the other. Use these results for deterministic startup ranking, not as a general benchmark.

## Runtime IR Analysis

The built-in runtime pipeline analyzes typed `IrGpu` before optimization. Register-pressure analysis uses scope-qualified variable identities, backward last-use dataflow, branch joins, loop fixed points, and helper call-frame summaries to estimate peak live values separately from total declared storage. Inline helpers may increase caller pressure; non-inline helpers retain separate frames. The analysis is advisory and does not rewrite code or satisfy production optimizer proof requirements.

When an estimate is close to or above the current device-profile budget, `optimizer-report.txt` contains a diagnostic and `runtime-ir-analysis.properties` contains machine-readable per-method fields. Treat the values as conservative planning evidence.

Successful OpenCL program builds query `CL_PROGRAM_BUILD_LOG` and retain any returned diagnostics in the runtime compile snapshot. With compiler diagnostics enabled, the runtime also captures the selected program binary with `CL_PROGRAM_BINARY_SIZES`/`CL_PROGRAM_BINARIES`, records size/SHA-256/format metadata, and writes `opencl-program.bin` in the per-kernel artifact directory. Artifact dumping produces `backend-compiler-feedback.properties`; common NVIDIA, AMD, and Intel/general resource lines are parsed into separate general/vector/scalar register counts, spill bytes, stack-frame bytes, local-memory bytes, and occupancy. When both values exist, `runtime-ir-analysis.properties` compares the selected compiler register count with the heuristic estimate. Empty driver logs and binary-query failures remain valid and simply produce explicit unavailable diagnostics. This comparison is diagnostic and does not enable an optimization automatically.

Hardware workload validation performs an additional fail-safe diagnostic build. NVIDIA uses `-cl-nv-verbose`; custom or AMD options can be provided with `JTG_OPENCL_DIAGNOSTIC_COMPILE_ARGS`. The diagnostic program is discarded after its log and binary are captured and never replaces the production program. Optional NVIDIA `ptxas`/`cuobjdump`/`nvdisasm` inspection is format-aware and timeout-isolated; missing tools or unsupported payloads never fail execution.

The runtime additionally uses standard `clGetKernelWorkGroupInfo` queries to capture maximum work-group size, preferred multiple, local memory, and private memory for both NVIDIA and AMD. These values remain available even when the compiler build log is empty. An explicit local launch size is checked against the compiled kernel's maximum before enqueue; multidimensional local sizes are validated by their total product. Unspecified local sizes remain driver-selected. Runtime artifact dumps write `runtime-launch-advisory.properties` with `aligned`, `non-preferred-multiple`, `driver-selected`, or `unavailable` status. Preferred-multiple mismatches are diagnostic only and never reject execution.

Vendor validation aggregates workload-kernel advisory files into `runtime-launch-advisory-summary.md` and the main validation report. GitHub Actions appends that generated Markdown directly instead of reparsing properties in shell code.

The validation history properties retain compact launch-advisory and compiler-resource summaries plus versioned per-kernel snapshots; the Markdown table keeps the short aggregate views. Compiler-resource history tracks effective registers, spill stores/loads, stack frames, provider, and inspection tool by kernel resource.

`runtime-compiler-resource-drift.properties` blocks CI when a kernel disappears, resource metrics become unavailable, spill/stack usage increases, or register allocation grows beyond `max(2, 10% of baseline)`. Smaller register changes remain advisory, and old history caches without compiler snapshots transition through `no-baseline` without failing the lane.

The reporter compares current counts and kernel snapshots with the latest compatible history entry. Per-kernel matching uses the kernel resource and detects status degradation, reduced kernel maximum, preferred-match loss, blocking activation, and missing resources even when aggregate counts remain equal. Older histories without snapshots use aggregate fallback.

`validateOpenClKernelLaunchAdvisoryDrift` is the CI gate: only `regressed` blocks a valid drift artifact, while changed/no-baseline states remain advisory.

The vendor workflow exposes an opt-in `launch_advisory_negative_fixture` dispatch input. It mutates only the restored validation-history baseline, verifies the real per-kernel validator rejects the synthetic kernel-maximum regression, and prevents cache staging or save for the fixture run.

Manual dispatch also accepts `validation_lane=all|nvidia|nvidia-rtx5070|nvidia-rtx3060|amd`; `nvidia` runs both NVIDIA devices, while the device-specific selectors construct only the requested self-hosted runner entry.

The vendor workflow persists validation history through a per-lane cache and keeps the restored baseline immutable for the duration of the run, preventing same-run report regeneration from masking cross-run drift.

If you need a backend-specific hint that JavaToGpu does not expose yet, use `@GPUAttribute` and declare the target explicitly:

```java
@GPUAttribute(backend = GpuBackendTarget.OPENCL, value = "vec_type_hint(float4)")
@GPU
static void openClOnlyKernel(@GPUGlobal float[] output) {
    output[GPU.get_global_id(0)] = 1.0f;
}
```

For OpenCL-only legacy code, `@OpenCLAttributes` and `@OpenCLQualifiers` remain valid compatibility annotations. For new code, prefer portable annotations for modeled concepts and `@GPUAttribute` for deliberate backend-specific metadata.

## `GPU.*` Builtins

Use `GPU.*` inside kernels for operations the compiler knows how to lower.

`GPU.*` is intentionally OpenCL-style. Even when CUDA, Vulkan/SPIR-V, or Metal lowerers are added, user code should keep the same `GPU` facade instead of switching to backend-specific Java dialects.

Common groups:

- Work-item indexing: `get_global_id`, `get_local_id`, `get_group_id`, `get_global_size`, `get_local_size`.
- Math: `sin`, `cos`, `tan`, `sqrt`, `pow`, `exp`, `log`, `clamp`, `mix`, `smoothstep`, and related helpers.
- Synchronization: `barrier` and memory fence constants.
- Images and samplers: image reads, writes, and metadata queries.
- Pointer/view bridging: `GPU.global(...)`, `GPU.constant(...)`, `GPU.local(...)`.

Prefer `GPU.*` over ordinary Java library calls inside kernels.

## Package Layout

Keep application imports split by intent:

- `net.sixik.ga_utils.javatogpu.api` is the thin root facade: `GPU`, `JavaToGpu`, `GpuScope`, and backend/device target selectors.
- `net.sixik.ga_utils.javatogpu.api.annotations` contains source annotations such as `@GPU`, `@GPUGlobal`, `@GPUStruct`, and `@GPUTest`.
- `net.sixik.ga_utils.javatogpu.api.types.*` contains scalar aliases and vector wrappers grouped by primitive family.
- `net.sixik.ga_utils.javatogpu.api.pointers.*` contains helper pointers and address-space pointer views.
- `net.sixik.ga_utils.javatogpu.api.images` contains image and sampler wrappers.

Do not import vectors, unsigned aliases, pointers, images, or samplers from the root `api` package. They intentionally live in grouped subpackages so the public API stays navigable.

`GpuAnnotationSupport` is public for processor/runtime compatibility, but normal user code should import concrete annotations from `api.annotations` instead.

## Data Types

Public data wrappers are grouped by purpose:

- `net.sixik.ga_utils.javatogpu.api.types.*` for scalar aliases and vectors.
- `net.sixik.ga_utils.javatogpu.api.pointers.*` for private and address-space pointer wrappers.
- `net.sixik.ga_utils.javatogpu.api.images` for image and sampler wrappers.
- `net.sixik.ga_utils.javatogpu.api.annotations` for kernel, address-space, struct, test, and optimizer annotations.

### Scalars

Supported scalar shapes include the common Java primitives used by the current subset: `byte`, `short`, `int`, `long`, `float`, `double`, and `char`.

Unsigned aliases include `UByte`, `UShort`, `UInt`, and `ULong`.

Import unsigned aliases from the matching family package, for example `api.types.bytes.UByte`, `api.types.shorts.UShort`, `api.types.integers.UInt`, and `api.types.longs.ULong`.

### Vectors

OpenCL-style vector wrapper families include:

- `Float2`, `Float3`, `Float4`
- `Int2`, `Int3`, `Int4`
- `UInt2`, `UInt3`, `UInt4`
- `Double2`, `Double3`, `Double4`

Vectors can be locals, helper parameters/returns, kernel parameters, and buffer element types where supported.

Import vector wrappers from the matching family package, for example `api.types.floats.Float4`, `api.types.integers.Int2`, or `api.types.doubles.Double4`.

### Structs

Use `@GPUStruct` for small value objects with GPU-compatible fields.

Currently supported field categories:

- primitive scalar fields
- vector fields
- nested `@GPUStruct` fields

Arrays inside struct fields are not supported in the current alpha.

### Pointer Views

Pointer wrappers and address-space views are useful for low-level helpers and packed/blob data.

Examples:

- helper pointer wrappers: `FloatPtr`, `IntPtr`, `DoublePtr`
- address-space views: `GlobalBytePtr`, `GlobalIntPtr`, `GlobalFloatPtr`, `ConstantBytePtr`, `LocalFloatPtr`

Use these only when simple typed arrays are not enough.

Import helper pointers from `api.pointers`, global views from `api.pointers.global`, constant views from `api.pointers.constant`, and local views from `api.pointers.local`.

### Images And Samplers

Image wrappers model OpenCL image parameters. Typical shapes include read-only images, write-only images, and `Sampler` arguments.

Import image and sampler wrappers from `net.sixik.ga_utils.javatogpu.api.images`.

Use image APIs when you need OpenCL image memory, filtering, channel metadata, or pixel read/write operations.

## Runtime API

The usual runtime entry point for application code is `JavaToGpu`.

Common calls:

- `JavaToGpu.useOpenCl()` for a simple scoped OpenCL runtime.
- `JavaToGpu.useOpenClSharedCache()` for repeated calls with a warm session and compile cache.
- `JavaToGpu.shutdownOpenClSharedCache()` to release the process-wide shared OpenCL cache.
- `JavaToGpu.useStandardBackendAndDevice()` for the default backend/device selection path.
- `JavaToGpu.explainStandardBackendAndDevice()` for setup diagnostics without installing a runtime backend.
- `JavaToGpu.launch1D(...)`, `launch2D(...)`, and `launch3D(...)` for explicit launch sizes.
- Generated launcher methods for normal `@GPU` calls.
- Automatic method-variant selection for generated launchers that declare `@GPUFallbackVariant`.

### Generated Launchers

Normal application code should call the generated launcher directly when the kernel is known at compile time. Generated launcher source is intentionally readable: comments mark the generated boundary, embedded descriptor/source constants, fallback descriptors, default launch overloads, explicit launch overloads, standard backend/device preflight helpers, return-first convenience metadata, and output-length validation.

```java
DemoKernel_transform_GpuLauncher.invokeWithStandardBackendAndDevice(
        GpuRuntimeCompileOptions.defaults(GpuBackendTarget.OPENCL),
        input,
        output
);
```

Use `GpuGeneratedLauncherInvoker` when a framework, plugin, test harness, or dynamic loader only has the owner class and method name at runtime:

```java
GpuGeneratedLauncherInvoker.GeneratedLauncher launcher =
        GpuGeneratedLauncherInvoker.launcher(DemoKernel.class, "transform");

launcher.invokeWithGlobalWorkSizeAndStandardBackendAndDevice(
        itemCount,
        GpuRuntimeCompileOptions.defaults(GpuBackendTarget.OPENCL),
        input,
        output
);
```

Keep the cached `GeneratedLauncher` handle for repeated dynamic calls. It resolves the generated launcher class, descriptor, and return-first metadata once, while still invoking generated overloads so fallback variant routing and standard backend/device preflight stay intact.

Return-first helpers are a narrow convenience over the real `void + output buffer` ABI. They are generated only for kernels with exactly one primitive read-write output array. Check `RETURN_VALUE_CONVENIENCE_*` on the generated class, or `launcher.returnValueConvenience()`, when you need to explain why the helper exists or was skipped.

Most applications should stop there. Treat `net.sixik.ga_utils.javatogpu.runtime` as an advanced compatibility layer, not as the first package to browse.

Runtime package map:

- `runtime.selection` - backend/device discovery, ranking, policy, and preflight diagnostics.
- `runtime.launch` - descriptor-based launches and generated-launcher support.
- `runtime.observability` - lifecycle events, logging, journals, and service harnesses.
- `runtime.optimization` - IR optimization/review support and optimizer extension points.
- `runtime.memory` - native-memory provider bridge used by LWJGL today and future Panama providers.
- `runtime.spi` and `runtime.hooks` - backend providers, stage hooks, and ServiceLoader extension contracts.
- `runtime.validation` - maintainer and CI harnesses, not normal application flow.
- `runtime.opencl` and `runtime.cuda` - backend implementation details.

Selection/launch compatibility facades:

| Existing root import | Prefer for new code | Why |
| --- | --- | --- |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeDeviceDiscovery` | `net.sixik.ga_utils.javatogpu.runtime.selection.GpuRuntimeSelection` / `net.sixik.ga_utils.javatogpu.runtime.selection.GpuRuntimeDeviceDiscoverySupport` | Backend/device discovery snapshots. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeBackendSelectionOrchestrator` | `net.sixik.ga_utils.javatogpu.runtime.selection.GpuRuntimeSelection` / `net.sixik.ga_utils.javatogpu.runtime.selection.GpuRuntimeBackendSelectionSupport` | Backend selection and backend/device preflight. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeWorkloadHintInference` | `net.sixik.ga_utils.javatogpu.runtime.selection.GpuRuntimeWorkloadHintInference` | Read-only workload-hint inference. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeWorkloadHintBackendScoreContributor` | `net.sixik.ga_utils.javatogpu.runtime.selection.GpuRuntimeWorkloadHintBackendScoreContributor` | Caller-provided workload-hint backend scoring. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeInferredWorkloadHintBackendScoreContributor` | `net.sixik.ga_utils.javatogpu.runtime.selection.GpuRuntimeInferredWorkloadHintBackendScoreContributor` | Inferred workload-hint backend scoring. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuBackendCompilerFeedbackScoreContributor` | `net.sixik.ga_utils.javatogpu.runtime.selection.GpuBackendCompilerFeedbackScoreContributor` | Compiler-feedback backend scoring. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuLauncherNaming` | `net.sixik.ga_utils.javatogpu.runtime.launch.GpuLauncherNamingSupport` | Generated-launcher naming rules. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeCompileRequestFactory` | `net.sixik.ga_utils.javatogpu.runtime.launch.GpuRuntimeCompileRequestSupport` | Runtime compile-request construction. |

Diagnostics compatibility facades:

| Existing root import | Prefer for new code | Why |
| --- | --- | --- |
| `net.sixik.ga_utils.javatogpu.runtime.GpuBackendCompilerFeedbackRegistry` | `net.sixik.ga_utils.javatogpu.runtime.diagnostics.GpuBackendCompilerFeedbackRegistry` | Backend compiler-feedback inspection. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuBackendSourcePromotionBlockerClassifier` | `net.sixik.ga_utils.javatogpu.runtime.diagnostics.GpuBackendSourcePromotionBlockerClassifier` | Backend-source promotion blocker classification. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuProductionPromotionExplainabilityFormatter` | `net.sixik.ga_utils.javatogpu.runtime.diagnostics.GpuProductionPromotionExplainabilityFormatter` | Production-promotion explainability formatting. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuPromotionArtifactRegistry` | `net.sixik.ga_utils.javatogpu.runtime.diagnostics.GpuPromotionArtifactRegistry` | Promotion artifact filename registry. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeCompileArtifactDumper` | `net.sixik.ga_utils.javatogpu.runtime.diagnostics.GpuRuntimeCompileArtifactDumper` | Runtime compile artifact dumping. |

Optimization compatibility facades:

| Existing root import | Prefer for new code | Why |
| --- | --- | --- |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeIrPeepholePass` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeIrPeepholePass` | Built-in typed-IR peephole optimization pass. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeIrPeepholeRuleRegistry` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeIrPeepholeRuleRegistry` | Typed-IR peephole rule discovery and analysis. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeIrPeepholeTypedRewriteVisitor` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeIrPeepholeTypedRewriteVisitor` | Typed peephole rewrite visitor preflight. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeIrTypedNodeGraph` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeIrTypedNodeGraphSupport` | Typed-node graph helper used by peephole optimization. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeCommonSubexpressionReviewPass` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeCommonSubexpressionReviewPass` | Review-only common-subexpression optimizer-family lane. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeRegisterPressureAnalysisPass` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeRegisterPressureAnalysisPass` | Built-in register-pressure analysis pass. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeRegisterPressureAnalyzer` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeRegisterPressureAnalyzer` | Advisory typed-IR register-pressure analysis. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeClampPeepholeRule` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeClampPeepholeRule` | Built-in clamp peephole rule. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeDotPeepholeRule` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeDotPeepholeRule` | Built-in dot peephole rule. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeMadFmaPeepholeRule` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeMadFmaPeepholeRule` | Built-in mad/fma peephole rule. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeMixPeepholeRule` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeMixPeepholeRule` | Built-in mix peephole rule. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeStepPeepholeRule` | `net.sixik.ga_utils.javatogpu.runtime.optimization.GpuRuntimeStepPeepholeRule` | Built-in step peephole rule. |

Memory compatibility facades:

| Existing root import | Prefer for new code | Why |
| --- | --- | --- |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeNativeMemoryServiceRegistry` | `net.sixik.ga_utils.javatogpu.runtime.memory.GpuRuntimeNativeMemoryServiceRegistry` | Native host-memory service discovery. |

Observability compatibility facades:

| Existing root import | Prefer for new code | Why |
| --- | --- | --- |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLifecycleEventBus` | `net.sixik.ga_utils.javatogpu.runtime.observability.GpuRuntimeLifecycleEventBus` | Runtime lifecycle event dispatch. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLifecycleFields` | `net.sixik.ga_utils.javatogpu.runtime.observability.GpuRuntimeLifecycleFields` | Runtime lifecycle field vocabulary helpers. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLifecycleFileJournalListener` | `net.sixik.ga_utils.javatogpu.runtime.observability.GpuRuntimeLifecycleFileJournalListener` | Built-in file-backed lifecycle journal service. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLifecycleLoggingService` | `net.sixik.ga_utils.javatogpu.runtime.observability.GpuRuntimeLifecycleLoggingService` | Built-in lifecycle-to-log bridge. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLogBus` | `net.sixik.ga_utils.javatogpu.runtime.observability.GpuRuntimeLogBus` | Runtime log dispatch. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeSystemStreamLogService` | `net.sixik.ga_utils.javatogpu.runtime.observability.GpuRuntimeSystemStreamLogService` | Built-in system stream log sink. |

Variant compatibility facades:

| Existing root import | Prefer for new code | Why |
| --- | --- | --- |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeMethodVariantRegistry` | `net.sixik.ga_utils.javatogpu.runtime.variants.GpuRuntimeMethodVariantRegistry` | Runtime method-variant discovery. |
| `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeMethodVariantSelector` | `net.sixik.ga_utils.javatogpu.runtime.variants.GpuRuntimeMethodVariantSelector` | Runtime method-variant selection. |

Public value/report records such as optimizer reports, proof artifacts, register-pressure reports, compiler-feedback reports, and runtime exceptions intentionally remain root runtime contracts for now. Moving implementation helpers is safer than moving value shapes used in generated artifacts, tests, and extension APIs.

The root imports remain supported for compatibility. New advanced code should prefer the domain package unless it is deliberately preserving an old public import.

Lower-level runtime calls remain available under `net.sixik.ga_utils.javatogpu.runtime` for advanced configuration, custom policies, direct descriptor invocation, generated launcher internals, and extension modules:

- `GpuRuntime.use(policy)` for custom fallback policies.
- `GpuRuntime.trySelect(policy)` for prechecking whether a custom GPU path is available.
- `GpuRuntime.invoke(...)` for descriptor-based direct invocation.
- `GpuExecutionConfig.oneDimensional(...)`, `twoDimensional(...)`, and `threeDimensional(...)` when code already works directly with runtime types.
- `GpuRuntimeCompileOptions.withDeviceSelfTestMode(...)` for `AUTO`, `DISABLED`, or strict `REQUIRED` startup correctness evidence.
- `GpuRuntimeCompileOptions.openClIrGpuSourceReview(...)` for opt-in reconstructed-`IrGpu` source smoke/review runs without changing the production default source path.
- `GpuProductionPromotionOperatorAcceptance` for identity-bound operator approval of a reviewed production source-switching context. The binding includes backend, device vendor/label, driver, optimization profile, kernel resource, and production decision mode.
- `GpuBackendSourcePromotionCandidateGate` for joining real-workload readiness with controlled identity-bound acceptance. A review-ready result remains non-mutating and does not enable default production source switching.
- `GpuBackendSourcePromotionManifest` for generating and validating a manual approval artifact bound to the candidate SHA-256, Git SHA, device/driver identity, and exact kernel resource list. Manifest validation remains review-only and non-mutating.
- `GpuBackendSourcePromotionActivationGate` for joining approved manifest validation with the candidate and controlled source-switching evidence. `controlled-activation-ready` never enables default runtime activation or production mutation.
- `GpuProductionActivationToken.fromArtifact(...)` for an explicit runtime opt-in loaded from one exact controlled-activation artifact after its SHA-256 is verified. The token remains bound to the artifact backend, device/driver identity, activation scope, and approved kernel resources.

Runtime failures share the public `GpuRuntimeException` base type. Catch a specific subtype when recovery depends on the phase, or catch the base type for a general CPU/backend fallback:

```java
try (GpuScope ignored = JavaToGpu.useOpenClSharedCache()) {
    DemoKernel.transform(input, output);
} catch (GpuRuntimeDeviceSelectionException exception) {
    CpuFallback.transform(input, output);
} catch (GpuRuntimeException exception) {
    System.err.println(exception.diagnosticText());
    CpuFallback.transform(input, output);
}
```

Important subtypes include `GpuRuntimeMethodVariantSelectionException`, `GpuRuntimeBackendUnavailableException`, `GpuRuntimeCompileOptionsException`, `GpuRuntimeInvocationException`, `GpuRuntimeCapabilityException`, `GpuRuntimeKernelCompilationException`, and `GpuRuntimeKernelExecutionException`. Every structured exception exposes `code()`, `phase()`, `summary()`, `context()`, `diagnosticText()`, and the original `getCause()`.

Build-time call-site indexes let `diagnosticText()` point at the exact Java expression that invoked a local or dependency-provided `@GPU` method. The same `GpuRuntimeDiagnosticContext` keeps the GPU method location, selected backend/device, compile arguments, and optimization profile. Bytecode rewriting preserves the caller's original `try/catch` region, so the fallback example above remains valid after the annotated method body is replaced with its generated launcher invocation.

When OpenCL discovers multiple devices, the default `AUTO` startup policy runs a small compile/enqueue/readback correctness kernel on each candidate before final selection. Failed devices are rejected; passed evidence is cached by hardware, driver, runtime, runner, and compiler identity. Passed devices also receive bounded compute and host/device transfer measurements. Only trimmed-median samples inside the noise limit affect ranking; noisy measurements remain telemetry. Use `DISABLED` when startup latency matters more than validation, or `REQUIRED` when even a single-device deployment must provide passed evidence.

## Programmatic Compiler API

Use `GpuProgramCompiler` when another compiler or tool wants to call JavaToGpu directly.

Main entry points:

- `GpuProgramCompiler.createDefault()`
- `compileSource(...)`
- `compileStructuredAsm(...)`

Use normal Java source for application kernels. Use the structured ASM path only when you are building tooling that intentionally emits supported bytecode.

## Recommended Reading

- [Getting Started](Getting-Started.md)
- [Cookbook](Cookbook.md)
- [Runtime Guide](Runtime-Guide.md)
- [OpenCL Data Model](OpenCL-Data-Model.md)
- [Known Limitations](Known-Limitations.md)

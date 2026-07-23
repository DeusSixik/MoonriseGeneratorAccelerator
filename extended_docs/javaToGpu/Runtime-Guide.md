# Runtime Guide

For ordinary application code, start with the public `JavaToGpu` facade. The lower-level `GpuRuntime` API remains available for custom backend policies, descriptor-based invocation, generated launcher internals, and extension modules.

## How To Read This Page

| If you need... | Start with |
| --- | --- |
| One normal OpenCL scope | Runtime Scopes |
| CPU fallback or backend/device explanation | Runtime Selection |
| Explicit global/local sizes | Explicit Launch Sizes |
| Dynamic generated launcher calls | Generated Launcher Helpers |
| Compile flags or optimizer review artifacts | Runtime Compile Options |
| Debugging failures | Runtime Failures And Fallbacks |

Skip the production source-acceptance and activation-token sections unless you are maintaining optimizer/source-promotion gates. They are not required for normal application launches.

## Runtime Scopes

### Isolated OpenCL Backend

Use this for simple applications, tests, and one-off calls:

```java
try (GpuScope ignored = JavaToGpu.useOpenCl()) {
    DemoKernel.transform(input, output);
}
```

### Shared OpenCL Cache

Use this for hot paths and repeated calls:

```java
try (GpuScope ignored = JavaToGpu.useOpenClSharedCache()) {
    DemoKernel.transform(input, output);
    DemoKernel.transform(input, output);
} finally {
    JavaToGpu.shutdownOpenClSharedCache();
}
```

The shared cache keeps the OpenCL session and compiled kernels warm across calls.

## Runtime Selection

### Strict OpenCL

```java
try (GpuScope ignored = JavaToGpu.useOpenClSharedCache()) {
    DemoKernel.transform(input, output);
}
```

This fails fast if OpenCL cannot be selected.

### Fallback Policy

```java
GpuRuntimeBackendPolicy policy = GpuRuntimeBackendPolicy.builder()
        .preferOpenClSharedCache()
        .preferFactory(MyCpuFallbackBackend::new)
        .build();

try (GpuRuntimeScope ignored = GpuRuntime.use(policy)) {
    DemoKernel.transform(input, output);
}
```

### Standard Backend Catalog

Use the standard backend catalog when you want JavaToGpu to assemble the normal production backend chain for you:

```java
GpuRuntimeBackendPolicy policy = GpuRuntimeBackendPolicy.builder()
        .preferStandardBackends()
        .build();

GpuRuntimeSelectionResult result = GpuRuntime.trySelect(policy);
System.out.println(result.explanationSummary());
```

Advanced code that only needs selection/discovery can use the domain entry point instead of importing more root-runtime helpers:

```java
GpuRuntimeSelectionResult result = GpuRuntimeSelection.trySelect(policy);
GpuRuntimeBackendDeviceSelection preflight = GpuRuntimeSelection.trySelectStandardBackendAndDevice();
System.out.println(preflight.toMarkdown());
```

`net.sixik.ga_utils.javatogpu.runtime.selection.GpuRuntimeSelection` is a compatibility-safe facade over the current runtime selection APIs. It is the preferred package for new selection-focused tools while `GpuRuntime` remains the lower-level runtime compatibility entry point.

`GpuRuntimeBackendCatalog.standard()` is lazy and inspectable: listing entries does not initialize OpenCL or any native
driver. Today the production catalog contains the OpenCL shared-cache adapter. `standardWithPlannedBackends()` also
adds CUDA, Vulkan/SPIR-V, and Metal to the same adapter list for diagnostics. CUDA can already contribute device
inventory through `nvidia-smi` when available, publish `cuda-c`/`ptx` metadata, and expose a non-production
compile/prepare/invoke skeleton whose real driver stages stay explicit opt-ins until production CUDA is validated.
When callers pass a `GpuRuntimeLifecycleEventBus` into the shared execution pipeline, the CUDA staged path now emits
`BACKEND_COMPILATION_*`, `MODULE_LOAD_*`, and `INVOCATION_*` events with portable `runtime.backend.*` fields plus
CUDA-specific `runtime.cuda.*` receipts for native bridge stages.
Vulkan/SPIR-V and Metal remain planned
placeholders with clear diagnostics instead of being silently hidden.
When CUDA options use `GpuRuntimeCompileOptions.cudaNvcc(...)`, the optional built-in process bridge may invoke
`nvcc --ptx`, `nvcc --cubin`, or `nvcc --fatbin` to materialize a CUDA module artifact. That still remains a compile-only step; CUDA module loading, argument
binding, launch, and readback are separate stages. Add `.withCudaDriverModuleLoader()` to opt into the built-in
CUDA Driver API module loader. Today that bridge can load the driver library, resolve required module-loader symbols,
call `cuInit`, read `cuDriverGetVersion`, parse PTX `.version` / `.target` metadata, reject known PTX ISA versions that
need a newer CUDA driver API, reject a PTX target that is newer than the selected CUDA device compute capability, load
compatible PTX or binary CUBIN/FATBIN payloads with `cuModuleLoadDataEx`, resolve the entry function with `cuModuleGetFunction`, and unload the module
through `CudaDriverLoadedModule.close()`. PTX compatibility preflight runs only for PTX; CUBIN/FATBIN payloads skip it because they are already backend-native binaries. Missing driver/symbol/PTX/function/version/target cases return structured
blockers such as `cuda-driver-library-unavailable`, `cuda-driver-symbol-missing:*`,
`cuda-driver-cuDriverGetVersion-failed:*`, `cuda-driver-ptx-version-unsupported:*`, `cuda-ptx-target-too-new:*`,
`cuda-driver-cuModuleLoadDataEx-failed:*`, or `cuda-driver-cuModuleGetFunction-failed:*`. Successful module-load receipts
include `runtime.cuda.loadedModule.driver.version.*`, `runtime.cuda.ptxCompatibility.*`,
`runtime.cuda.ptxDriverCompatibility.*`, and `runtime.cuda.ptxDeviceCompatibility.*` fields. Add
`.withCudaDriverArgumentBinder()` to enter the built-in driver argument-binding preflight. It requires a real driver
module/function handle, prepares an empty `CudaKernelArgumentFrame` for zero-argument kernels, and receives shallow-copied
Java invocation values through `CudaExecutionPlan`. Successful preflight updates the portable
`runtime.backend.prepare.binding.*` counts plus CUDA-specific `runtime.cuda.argumentBinding.*`,
`runtime.cuda.argumentFrame.*`, and `runtime.cuda.executionPlan.*` receipt fields. Blocked preflight reports
`cuda-driver-argument-values-missing` when no payload exists, `cuda-driver-argument-count-mismatch` when descriptor and
payload disagree, `cuda-driver-buffer-binding-missing` / `cuda-driver-scalar-value-binding-missing` when no invocation
payload exists, or `cuda-driver-local-binding-missing` when a `LOCAL` payload is absent. The current native binding slice
supports non-empty primitive array `READ_ONLY` / `READ_WRITE` buffers
(`byte[]`, `short[]`, `char[]`, `int[]`, `long[]`, `float[]`, `double[]`), GPU vector array buffers,
`@GPUStruct[]` buffers, plus
primitive scalar `VALUE` arguments (`byte`, `short`, `char`, `int`, `long`, `float`, `double`, `boolean`, and registered
scalar aliases). For buffers it resolves `cuMemAlloc_v2`, `cuMemcpyHtoD_v2`, and `cuMemFree_v2`, uploads host values,
packs vector arrays using the declared vector storage width, packs struct arrays and `@GPUStruct` `VALUE` arguments with
the same primitive/vector/nested struct field layout as the OpenCL ABI slice, builds the host-side kernel parameter table with
device-pointer and value slots, records readback-required counts for `READ_WRITE` outputs plus scalar-slot
receipts, and frees device allocations/native argument slots when the prepared
argument frame closes. Primitive array and local `@GPUStruct[]` `LOCAL` arguments are mapped to CUDA dynamic shared memory and contribute
`runtime.cuda.argumentFrame.localSharedMemory.*` layout receipts. One `LOCAL` is omitted from the kernel parameter table.
When multiple `LOCAL` parameters exist, the CUDA lowerer emits hidden unsigned byte-offset parameters after visible
non-`LOCAL` parameters, and the driver binder appends matching offset slots after normal device-pointer/scalar slots.
Launch receives the total layout size as `sharedMemoryBytes`. Add `.withCudaDriverKernelLauncher()` to enter the
built-in Driver API launcher after native argument binding. It resolves `cuLaunchKernel`, requires a real
`CudaDriverLoadedModule`, a prepared argument frame, and an explicit `GpuExecutionConfig`, then derives CUDA grid/block
dimensions from the portable global/local work shape and submits the kernel parameter table. The driver launcher is
fail-closed for launch shape: local size must be explicit, each global dimension must be divisible by the matching local
dimension, block item count must fit the Driver API/device limit, and oversized dynamic shared memory is reported as an
unsupported blocker instead of an exception. A successful launcher emits `runtime.cuda.kernelLaunch.*`, including
`launchShape.*` and shared-memory byte-size fields, plus portable `runtime.backend.invoke.*` launch-shape fields. Add
`.withCudaDriverReadback()` to enter the built-in Driver API readback bridge. It resolves `cuMemcpyDtoH_v2`, copies
`READ_WRITE` primitive/vector/struct array device allocations back into the original Java arrays, marks allocation readback receipts, and
updates `runtime.cuda.readback.*` plus portable `runtime.backend.invoke.readback.*` counts. Built-in production CUDA
driver execution remains unavailable until broader image/sampler coverage, cross-device validation,
and explicit production activation policy are in place.
The staged CUDA binder/readback path accepts full Java arrays or explicit `GpuMemorySlice.of(array, offset, length)`
views for contiguous primitive, vector, and `@GPUStruct[]` buffers. Slice uploads allocate/copy only the selected element
range, and readback writes results back into the same host offset while preserving surrounding array elements.
For explicit hardware validation of this non-production staged path, run:

```powershell
.\gradlew.bat :processor:integrationCudaSmokeTest --console=plain
```

Use explicit per-format lanes when validating the PTX-vs-binary module paths independently:

```powershell
.\gradlew.bat :processor:integrationCudaPtxSmokeTest --console=plain
.\gradlew.bat :processor:integrationCudaCubinSmokeTest --console=plain
.\gradlew.bat :processor:integrationCudaFatbinSmokeTest --console=plain
```

The binary smoke summaries include scenario-level evidence counters. For example,
`evidence.scenario.struct-value.realDriver.count=1` and `evidence.scenario.local-struct.realDriver.count=1` prove that
the staged CUDA Driver API path actually launched and read back kernels that use an `@GPUStruct` `VALUE` parameter and
local `@GPUStruct[]` shared memory, rather than relying only on generic lane success.

That task discovers a CUDA device through `nvidia-smi`, compiles inline CUDA-C with `nvcc`, loads PTX/CUBIN/FATBIN through the CUDA
Driver API, binds arguments, launches kernels, and reads back outputs. The current smoke coverage includes primitive
buffers plus scalar `VALUE`, `GpuMemorySlice` primitive subranges, `Float2[]` buffers, simple `@GPUStruct[]` buffers,
scalar `@GPUStruct` `VALUE` parameters, multi-`LOCAL` dynamic shared-memory offsets, local `@GPUStruct[]` shared memory,
and launch-shape artifact checks. It skips when CUDA tooling/driver state is unavailable unless `JTG_CUDA_SMOKE_REQUIRED=true` is set. Use
`JTG_CUDA_SMOKE_ARCH=compute_86`, `JTG_CUDA_NVCC=C:\path\to\nvcc.exe`, or `JTG_CUDA_NVCC_OUTPUT_FORMAT=ptx|cubin|fatbin`
to pin the compiler target, compiler path, or requested nvcc output family.
The default task writes and validates `processor/build/reports/cuda/integration-cuda-smoke-summary.properties` after every
run, including `status`, test counts, `executed`, `realDriverExecutionEvidence`, `realDriverExecutionEvidence.rich`,
`evidence.*` counters, `nvcc.outputFormat`, and structured blockers such as `cuda-nvcc-host-compiler-missing:cl.exe`.
The per-format lanes write sibling summary files named `integration-cuda-ptx-smoke-summary.properties`,
`integration-cuda-cubin-smoke-summary.properties`, and `integration-cuda-fatbin-smoke-summary.properties`. A passed smoke
summary must include rich real-driver evidence for every executed test; otherwise the summary gate fails the build.
On Windows, make sure the Visual C++ host compiler (`cl.exe`) is on `PATH` before expecting `nvcc` to emit PTX; missing
`cl.exe` is reported as `cuda-nvcc-host-compiler-missing:cl.exe` and skipped by default. Run the task from a Visual
Studio Developer Command Prompt or configure a CUDA-supported MSVC toolchain.
If `nvcc` emits a PTX ISA newer than the installed CUDA driver can load, the same smoke also skips by default with a
structured blocker such as `cuda-driver-ptx-version-unsupported:ptx-9.3:driver-13.2:requires-13.3`. This can happen even
when the selected `compute_XX` target is old because PTX ISA version follows the CUDA toolkit frontend. Use a matching
driver/toolkit pair, set `JTG_CUDA_NVCC` to an older compatible toolkit, or request `cubin`/`fatbin` output for the
staged binary module path.
`cubin` and `fatbin` are recognized canonical module formats. The staged CUDA loader passes their binary payloads into
`cuModuleLoadDataEx` and skips PTX ISA compatibility preflight for those formats. The binary lanes are the preferred way
to validate execution when PTX ISA is newer than the installed driver API; production CUDA execution still requires
promotion policy and broader coverage before it can be treated as supported.

Once the per-format summaries exist, run the production-readiness boundary gate:

```powershell
.\gradlew.bat :processor:validateCudaProductionReadiness --console=plain
```

The task writes `processor/build/reports/cuda/production-readiness.properties`. `review-ready` means the staged CUBIN/FATBIN
execution evidence is complete while production CUDA remains disabled by policy; `production-ready` is reserved for a future
activation path, and `blocked` means smoke evidence or metadata contracts regressed.

When changing CUDA launch sizing or dynamic shared-memory logic, run the hardware-free launch contract gate:

```powershell
.\gradlew.bat :processor:validateCudaLaunchContract --console=plain
```

This gate uses synthetic Driver API handles, not a real CUDA driver. It proves that valid 1D/3D launch shapes and dynamic
shared memory are accepted while auto-local, non-divisible global/local shapes, oversized blocks, and shared-memory limit
violations stay fail-closed with stable `cuda-driver-launch-*` blockers.

CUDA image/sampler arguments have a separate fail-closed contract:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerContract --console=plain
```

That gate recognizes the existing Java image and sampler wrapper types but expects unsupported CUDA binder receipts with
stable `cuda-driver-image-argument-unsupported:*` / `cuda-driver-sampler-argument-unsupported:*` blockers. This is not
production image/sampler support; it is the guardrail that prevents accidental struct/scalar binding while the CUDA
texture/surface/sampler ABI is still pending. The same gate records a hardware-free runtime binding preflight plan:
current synthetic cases report `runtimeBindingPlanEntries=6`, `runtimeBindingPlanPlannedSlots=12`, and
`runtimeBindingPlanActiveSlots=0`, proving planned texture/surface/sampler slots are visible while actual runtime binding
remains disabled.

The planned CUDA image/sampler ABI can be checked separately:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerAbiPlan --console=plain
```

This gate is also hardware-free. It fixes the future mapping for Java image wrappers to `CUtexObject` / `CUsurfObject`
carriers and `Sampler` state to `CUDA_TEXTURE_DESC`, reports current source-preview coverage (`sourcePreviewEnabled=2`,
`sourcePreviewFolded=1`, `sourcePreviewPending=14`, `sourcePreviewKernelParameterSlots=6`,
`sourcePreviewMetadataSlots=4`), records the planned runtime slot shape (`plannedRuntimeKernelParameterSlots=44`,
`plannedRuntimeMetadataSlots=28`), and keeps active runtime binding at `runtimeBindingEnabled=0` /
`runtimeBindingKernelParameterSlots=0` until the real implementation and real-device evidence exist.

The Driver API object-creation boundary has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerObjectCreationContract --console=plain
```

This resolves the planned texture/surface object symbols through synthetic Driver API handles and must report
`objectCreationEnabled=false`, `objectOwnershipBoundary=prepared`, `activeObjectCount=0`, `requiredDriverSymbols=13`,
`resolvedDriverSymbols=13`, and `missingDriverSymbols=0`. The ownership path is ready to close future texture/surface
handles, but this gate is still symbol/preflight only; runtime binding does not call `cuTexObjectCreate`,
`cuSurfObjectCreate`, or their destroy functions during normal execution.

The CUDA descriptor boundary is checked separately:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerDescriptorContract --console=plain
```

This gate fixes the planned resource/texture descriptor vocabulary without allocating native descriptor structs. It must
report `resourceDescriptors=16`, `textureDescriptors=9`, `descriptorBuildEnabled=false`,
`nativeDescriptorAllocationEnabled=false`, `activeDescriptorCount=0`, and `nativeLayoutPending=17`. The sampler state is
currently a planned default (`nearest-clamp-to-edge` with unnormalized coordinates) until Java-side sampler metadata is
available for CUDA.

The planned native descriptor layout has a separate preview-only check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerNativeDescriptorLayout --console=plain
```

This gate records the logical `CUDA_RESOURCE_DESC` / `CUDA_TEXTURE_DESC` field shape before native memory work exists. It
must report `resourceLayouts=16`, `resourceLayoutFields=35`, `textureLayouts=9`, `textureLayoutFields=54`,
`nativeLayoutBuildEnabled=false`, `nativeDescriptorAllocationEnabled=false`, and `activeNativeDescriptorCount=0`. It does
not allocate descriptor memory, encode CUDA SDK struct bytes, call texture/surface object creation, or enable runtime
image/sampler binding.

Java-side descriptor build planning has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerDescriptorBuildPlan --console=plain
```

This gate validates the invocation wrapper data that a future descriptor builder will need. It must report
`caseReady=7/7`, `planReady=4`, `planBlocked=3`, `entries=9`, `resourceDescriptorPayloads=6`,
`textureDescriptorPayloads=7`, `activeDescriptorPayloads=0`, and `activeNativeDescriptors=0`. The intentionally blocked
cases pin stable diagnostics for missing image handles, closed samplers, and missing 2D height metadata.

The Java-only descriptor payload model has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerDescriptorPayloadModel --console=plain
```

This gate turns ready descriptor build plans into logical Java payload objects for future `CUDA_RESOURCE_DESC` /
`CUDA_TEXTURE_DESC` encoders. It must report `caseReady=3/3`, `modelReady=1`, `modelBlocked=2`, `entries=19`,
`resourcePayloads=16`, `texturePayloads=9`, `samplerPayloads=1`, and `activeNativeDescriptors=0`. Blocked preflight
cases stay blocked with stable missing-handle/closed-sampler diagnostics. Native allocation, native struct encoding,
texture/surface object creation, and runtime image/sampler binding remain disabled.

The planned native descriptor field encoding shape has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerNativeDescriptorEncodingPlan --console=plain
```

This gate records field-write intent only. It maps ready Java payloads to logical `CUDA_RESOURCE_DESC` /
`CUDA_TEXTURE_DESC` field paths such as `resType`, `res.array.hArray`, `res.linear.devPtr`, `addressMode[0]`,
`filterMode`, `flags`, and `readMode`. It must report `caseReady=3/3`, `planReady=1`, `planBlocked=2`, `entries=19`,
`resourceFieldWrites=35`, `textureFieldWrites=54`, `fieldWrites=89`, `nativeWriteEnabledCount=0`,
`sdkStructByteEncodingEnabledCount=0`, and `activeNativeDescriptors=0`. It still writes no native memory and does not
encode CUDA SDK struct bytes.

The native descriptor allocation/ownership preflight has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerNativeDescriptorAllocationPreflight --console=plain
```

This gate records allocation, ownership, cleanup, and rollback intent below descriptor field encoding. It must report
`caseReady=3/3`, `planReady=1`, `planBlocked=2`, `preflightReady=0`, `preflightBlocked=3`, `entries=19`,
`resourceDescriptorAllocations=16`, `textureDescriptorAllocations=9`, `plannedNativeDescriptors=25`,
`allocatedNativeDescriptors=0`, `nativeDescriptorOwnershipPlanned=25`, `cleanupPlanned=25`, `rollbackPlanned=25`,
`allocationEnabledCount=0`, and `activeNativeDescriptors=0`. It still allocates no native descriptor memory, encodes no
CUDA SDK struct bytes, creates no texture/surface objects, and binds no runtime image/sampler handles.

The native descriptor allocation transaction plan has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerNativeDescriptorAllocationTransactionPlan --console=plain
```

This gate turns allocation intent into Java-side owner skeletons and deterministic cleanup/rollback order only. It must
report `caseReady=3/3`, `preflightReady=0`, `preflightBlocked=3`, `transactionReady=0`, `transactionBlocked=3`,
`entries=19`, `descriptorOwners=25`, `resourceDescriptorOwners=16`, `textureDescriptorOwners=9`,
`activeDescriptorOwners=0`, `nativeAddressesPresent=0`, `allocationEnabledCount=0`, `cleanupPlanned=25`,
`rollbackPlanned=25`, and `activeNativeDescriptors=0`. It still applies no native allocation, cleanup, rollback, SDK
struct byte encoding, object creation, or runtime binding.

The native descriptor field-write transaction plan has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerNativeDescriptorEncodingTransactionPlan --console=plain
```

This gate maps logical field writes to planned descriptor owner slots only. It must report `caseReady=3/3`,
`encodingPlanReady=1`, `encodingPlanBlocked=2`, `allocationTransactionReady=0`, `allocationTransactionBlocked=3`,
`transactionReady=0`, `transactionBlocked=3`, `entries=19`, `descriptorWrites=25`,
`resourceDescriptorWrites=16`, `textureDescriptorWrites=9`, `resourceFieldWrites=35`, `textureFieldWrites=54`,
`fieldWrites=89`, `ownersPresent=25`, `ownersActive=0`, `nativeAddressesPresent=0`,
`nativeWriteEnabledCount=0`, `sdkStructByteEncodingEnabledCount=0`, and `activeNativeDescriptors=0`. It still writes
no native memory, encodes no SDK struct bytes, creates no texture/surface objects, and binds no runtime image/sampler
handles.

The planned texture/surface object request shape has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerObjectCreationRequestPlan --console=plain
```

This gate records request intent above descriptor encoding only. It must report `caseReady=3/3`, `planReady=1`,
`planBlocked=2`, `entries=19`, `objectRequests=16`, `textureObjectRequests=8`, `surfaceObjectRequests=8`,
`foldedSamplers=1`, `objectCreationCallEnabledCount=0`, and `activeObjects=0`. Blocked descriptor plans keep stable
blockers and produce zero object requests. Runtime image/sampler binding still does not call `cuTexObjectCreate` or
`cuSurfObjectCreate`.

The native object-preparation preflight has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerNativeObjectPreparationPreflight --console=plain
```

This gate records the native prerequisites below request planning and above real Driver API object creation. It must
report `caseReady=3/3`, `planReady=1`, `planBlocked=2`, `preflightReady=0`, `preflightBlocked=3`, `entries=19`,
`objectPreparations=16`, `textureObjectPreparations=8`, `surfaceObjectPreparations=8`, `foldedSamplers=1`,
`resourceDescriptorsRequired=16`, `resourceDescriptorsAvailable=0`, `resourceDescriptorOwnersPresent=16`,
`resourceDescriptorWritesPlanned=16`, `textureDescriptorsRequired=8`, `textureDescriptorsAvailable=0`,
`textureDescriptorOwnersPresent=8`, `textureDescriptorWritesPlanned=8`, `resourceDescriptorNativeAddressesPresent=0`,
`textureDescriptorNativeAddressesPresent=0`, `createFunctionsAvailable=16`, `destroyFunctionsAvailable=16`,
`objectHandlesAvailable=0`, and `activeObjects=0`. It still allocates no native descriptor memory and calls no object
creation functions.

The planned runtime object binding shape has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerRuntimeObjectBindingPlan --console=plain
```

This gate connects planned future `CUtexObject` / `CUsurfObject` handles to kernel parameter slots only. It must report
`caseReady=3/3`, `planReady=1`, `planBlocked=2`, `entries=19`, `objectBindings=16`, `textureObjectBindings=8`,
`surfaceObjectBindings=8`, `foldedSamplers=1`, `plannedObjectKernelParameterSlots=16`,
`plannedMetadataKernelParameterSlots=28`, `plannedKernelParameterSlots=44`, `runtimeBindingKernelParameterSlots=0`,
`objectCreationCallEnabledCount=0`, and `activeObjects=0`. Runtime binding still passes no texture/surface object
handles to CUDA kernels.

The runtime object-binding transaction preflight has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerRuntimeObjectBindingTransactionPreflight --console=plain
```

This gate verifies the final prerequisites before any planned `CUtexObject` / `CUsurfObject` slot can become a real
kernel-argument write. It must report `caseReady=3/3`, `planReady=1`, `planBlocked=2`, `preflightReady=0`,
`preflightBlocked=3`, `entries=19`, `objectBindingTransactions=16`, `textureObjectTransactions=8`,
`surfaceObjectTransactions=8`, `objectHandlesRequired=16`, `objectHandlesAvailable=0`, `nativeDescriptorsAvailable=0`,
`resourceDescriptorsRequired=16`, `resourceDescriptorOwnersPresent=16`, `resourceDescriptorNativeAddressesPresent=0`,
`resourceDescriptorWritesPlanned=16`, `resourceDescriptorNativeWritesEnabled=0`, `textureDescriptorsRequired=8`,
`textureDescriptorOwnersPresent=8`, `textureDescriptorNativeAddressesPresent=0`, `textureDescriptorWritesPlanned=8`,
`textureDescriptorNativeWritesEnabled=0`, `transactionApplyEnabledCount=0`, and `kernelParameterWriteEnabledCount=0`.
The expected state is blocked until native descriptor addresses/writes, object handles, ownership, and kernel parameter
writes exist.

The top-level image/sampler fail-closed contract has its own hardware-free check:

```powershell
.\gradlew.bat :processor:validateCudaImageSamplerFailClosedContract --console=plain
```

This gate aggregates the staged image/sampler reports and proves the whole boundary is still closed. It must report
`componentReady=15/15`, `plannedNativeDescriptors=25`, `plannedObjectRequests=16`,
`plannedRuntimeKernelParameterSlots=44`, `nativeMutationCount=0`, `runtimeBindingKernelParameterSlots=0`,
`objectCreationCallEnabledCount=0`, `nativeDescriptorsAvailable=0`, `nativeDescriptorAddressesPresent=0`,
`nativeDescriptorWritesEnabled=0`, `objectHandlesAvailable=0`, `activeNativeDescriptors=0`, and `activeObjects=0`.
If this gate fails, do not treat CUDA image/sampler support as production-safe.

CUDA source preview now has a 2D texture/surface slice: `Image2DReadOnly` lowers to `cudaTextureObject_t`,
`Image2DWriteOnly` lowers to `cudaSurfaceObject_t`, `read_imagef/i/ui` lowers to `tex2D<T>`, `write_imagef/i/ui`
lowers to `surf2Dwrite(...)`, `Sampler` is folded out of the kernel signature as planned descriptor state, and
`get_image_width/height` lower to explicit metadata parameters. Non-2D image shapes and unsupported image metadata still
fail closed with structured blockers such as `cuda-image-sampler-source-lowering-pending:*`,
`cuda-image-write-source-lowering-pending:*`, or `cuda-image-metadata-source-lowering-pending:*`.
CUDA inventory exposes driver, memory, CUDA runtime version, and compute capability through the same artifact/lifecycle
field vocabulary; tooling can read selected-device fields such as `runtime.device.cuda.runtimeVersion` and
`runtime.device.cuda.computeCapability` or per-device fields such as `deviceDiscovery.device.0.cuda.computeCapability`.

When you want one scope that selects both the backend and the device, use `GpuRuntime.useStandardBackendAndDevice(...)`:

```java
try (GpuRuntimeScope ignored = GpuRuntime.useStandardBackendAndDevice(
        GpuRuntimeCompileOptions.defaults(GpuBackendTarget.OPENCL).preferDeviceVendor("NVIDIA")
)) {
    DemoKernel.transform(input, output);
}
```

Generated launchers also expose scoped helpers when you want the generated call itself to open the backend+device scope:

```java
DemoKernel_transform_GpuLauncher.invokeWithStandardBackendAndDevice(
        GpuRuntimeCompileOptions.defaults(GpuBackendTarget.OPENCL).preferDeviceVendor("NVIDIA"),
        input,
        output
);

DemoKernel_transform_GpuLauncher.invokeWith3DWorkSizeAndStandardBackendAndDevice(
        width,
        height,
        depth,
        GpuRuntimeCompileOptions.defaults(GpuBackendTarget.OPENCL).preferDeviceVendor("NVIDIA"),
        input,
        output
);
```

The reflection-style helper has the same shape when you do not want to reference the generated launcher class directly:

```java
GpuGeneratedLauncherInvoker.invokeWithStandardBackendAndDevice(
        DemoKernel.class,
        "transform",
        GpuRuntimeCompileOptions.defaults(GpuBackendTarget.OPENCL).preferDeviceVendor("NVIDIA"),
        input,
        output
);
```

If you prefer the normal `invokeWithCompileOptions(...)` shape, opt in through the compile options instead:

```java
GpuRuntimeCompileOptions options = GpuRuntimeCompileOptions
        .defaults(GpuBackendTarget.OPENCL)
        .preferDeviceVendor("NVIDIA")
        .withStandardBackendDevicePreflight();

DemoKernel_transform_GpuLauncher.invokeWithCompileOptions(options, input, output);
```

This profile only opens the standard backend+device preflight when no backend is already installed. Existing
`GpuRuntime.useOpenCl...`, custom backend scopes, and default launcher calls are not overridden, so applications avoid
surprise startup device scans unless they explicitly request them.
The raw property form is `runtime.backendDevicePreflight=standard`; the only valid values are `disabled` and
`standard`, and unknown values are treated as disabled with a `runtime-backend-device-preflight-mode-invalid` blocker.
When automatic preflight runs, ServiceLoader lifecycle services receive `BACKEND_DEVICE_PREFLIGHT_STARTED` and
`BACKEND_DEVICE_PREFLIGHT_COMPLETED` events around the facade scope. The same lifecycle bus is passed into backend
selection and device discovery, so a trace service can show the path from launcher call to selected backend/device
before backend compilation starts. These facade events include portable fields such as `runtime.kernel.name`,
`runtime.kernel.resource`, `runtime.backend.target`, `runtime.compile.optimizationProfile`,
`runtime.backendDevicePreflight.mode`, `runtime.work.globalShape`, `runtime.status`, and, on failures,
`runtime.failure.type` / `runtime.failure.message`.

For diagnostics without installing anything, call `GpuRuntime.trySelectStandardBackendAndDevice(...)` first and print
`selection.toMarkdown()` when `selection.matched()` is false. `GpuRuntime.use(selection)` / `installSelectedBackend()`
passes the selected discovery result into device-aware runtime backends before installing them. OpenCL uses that
preselected device for its first native session and compile provenance, then still performs final per-method validation
before launching a kernel.

### Capability Precheck

```java
GpuRuntimeBackendPolicy policy = GpuRuntimeBackendPolicy.builder()
        .requireFeature(GpuBackendTarget.OPENCL, GpuRuntimeFeature.IMAGES)
        .preferOpenClSharedCache()
        .build();

GpuRuntimeSelectionResult result = GpuRuntime.trySelect(policy);
if (!result.matched()) {
    System.out.println("GPU path skipped: " + result.failureSummary());
    return;
}

try (GpuRuntimeScope ignored = result.install()) {
    DemoKernel.transform(input, output);
}
```

### Backend Target Controls

Use backend target controls when you want deterministic startup behavior instead of "try whatever works":

```java
GpuRuntimeBackendPolicy openClOnly = GpuRuntimeBackendPolicy.builder()
        .forceBackendTarget(GpuBackendTarget.OPENCL)
        .preferStandardBackends()
        .build();

GpuRuntimeSelectionResult result = GpuRuntime.trySelect(openClOnly);
System.out.println(result.explanation().toMarkdown());
```

`forceBackendTarget(...)` is an alias for `requireBackendTarget(...)`. `excludeBackendTarget(...)` rejects a backend
family while still allowing later fallback candidates. Both controls participate in `failureSummary()`,
`explanationSummary()`, `candidateDecisions()`, and `artifactFields(...)`, so applications can explain why CUDA,
OpenCL, Vulkan/SPIR-V, Metal, or a custom backend was selected or rejected.

### Device Selection Controls

Use strict device overrides when the application must run on one specific device family:

```java
GpuRuntimeCompileOptions options = GpuRuntimeCompileOptions
        .defaults(GpuBackendTarget.OPENCL)
        .withDeviceOverride(GpuRuntimeDeviceOverride.byVendor("NVIDIA"));
```

Use device preferences when you want deterministic ranking without forcing a single device. Preferred values add score;
excluded values reject matching candidates before OpenCL creates the runtime context:

```java
GpuRuntimeCompileOptions options = GpuRuntimeCompileOptions
        .defaults(GpuBackendTarget.OPENCL)
        .preferDeviceVendor("NVIDIA")
        .preferDeviceClass(GpuDeviceClassTarget.DGPU)
        .excludeIntegratedAndCpuDevices();
```

Available helpers include `preferDeviceId(...)`, `preferDeviceVendor(...)`, `preferDeviceLabel(...)`,
`preferDeviceClass(...)`, `excludeDeviceId(...)`, `excludeDeviceVendor(...)`, `excludeDeviceLabel(...)`,
`excludeDeviceClass(...)`, `excludeCpuDevices()`, `excludeIntegratedGpuDevices()`, and
`excludeIntegratedAndCpuDevices()`. These controls feed the same device-selection artifact fields as the built-in
OpenCL self-tests, so the selected device, rejected candidates, score adjustments, and first blocker stay auditable.
Compile dumps also include `deviceOverride` and `devicePreference` in `compile-provenance.properties`, making the
selection intent visible beside backend target, compile args, optimization profile, and selected device facts.

Preview the OpenCL device decision without compiling or invoking a kernel:

```java
GpuRuntimeCompileOptions options = GpuRuntimeCompileOptions
        .defaults(GpuBackendTarget.OPENCL)
        .preferDeviceClass(GpuDeviceClassTarget.DGPU)
        .excludeCpuDevices();

GpuRuntimeDeviceDiscoveryResult discovery = GpuRuntimeDeviceDiscovery.discoverOpenCl(options);
System.out.println(discovery.toMarkdown());

GpuRuntimeDeviceDiscoveryCatalog catalog = GpuRuntimeDeviceDiscovery.discoverStandardBackends(options);
System.out.println(catalog.toMarkdown());

GpuRuntimeSelectionResult backendSelection = GpuRuntime.trySelectStandardBackends();
System.out.println(backendSelection.explainWithDeviceDiscovery(catalog).toMarkdown());
```

`GpuRuntimeDeviceDiscoveryResult` is fail-soft: it carries `discoveryAvailable=false`, `firstBlocker`, and diagnostics
when OpenCL cannot be queried, and otherwise includes discovered device profiles plus the same ranked device-selection
evidence used by runtime compile artifacts. The markdown and artifact fields also summarize native platform groups and
runtime self-test state, so local diagnostics can distinguish "which OpenCL platform?", "which device?", and "were
self-tests disabled, missing, accepted, or failed?" without opening raw artifacts.
`GpuRuntimeDeviceDiscoveryCatalog` wraps multiple backend discovery states. Today it contains real OpenCL discovery plus
explicit planned/unavailable CUDA, Vulkan/SPIR-V, and Metal entries, so tools can render one inventory even before all
backend adapters exist.
`GpuRuntimeBackendDeviceSelectionExplanation` is the combined surface for CLIs and support logs: it links backend
candidate decisions with the discovery catalog and reports whether the selected backend and selected device evidence
agree.

### Method Test-Vector Metadata Preview

`@GPUTest` is the first authoring contract for method-specific backend/device probes. It records stable fixture
references in the generated `IrGpu` manifest so runtime tooling can preflight fixtures, compare a CPU/reference path,
and optionally validate the same kernel on candidate devices without users writing separate probe methods.

For a beginner-friendly walkthrough with copyable numeric and `@GPUStruct[]` examples, start with
[Method Tests](Method-Tests.md). This section focuses on the lower-level runtime API surface.

```java
@GPU
@GPUTest(
        id = "selection-smoke",
        inputs = {"fixtures/selection-smoke.inputs.json"},
        expectedOutputs = {"fixtures/selection-smoke.outputs.json"},
        tolerance = "abs=1e-5,rel=1e-4",
        tags = {"selection", "smoke"}
)
void kernel(@GPUGlobal float[] input, @GPUGlobal float[] output) {
    int id = GPU.get_global_id(0);
    output[id] = input[id] * 2.0f;
}
```

The generated `.irgpu.properties` file stores this as `methodTestVector.*` metadata with the method name, emitted name,
case id, input refs, expected-output refs, tolerance, tags, and `selectionProbe` flag. Old manifests parse with an empty
test-vector list, so this metadata is safe to keep even when applications skip the optional runtime probe executor.

At runtime, inspect the generated metadata without executing the kernel:

```java
GpuRuntimeMethodTestProbePlan plan = GpuRuntimeMethodTestProbes.plan(MyKernel_GpuLauncher.KERNEL_DESCRIPTOR);
GpuRuntimeMethodTestFixtureReadiness readiness = GpuRuntimeMethodTestProbes.fixtureReadiness(plan);
GpuRuntimeMethodTestFixtureValueBindingPlan bindings = GpuRuntimeMethodTestProbes.fixtureValueBindings(
        MyKernel_GpuLauncher.KERNEL_DESCRIPTOR,
        plan,
        MyKernel.class.getClassLoader()
);
GpuRuntimeMethodTestInvocationMaterializationPlan materialization =
        GpuRuntimeMethodTestProbes.fixtureInvocationMaterialization(
                MyKernel_GpuLauncher.KERNEL_DESCRIPTOR,
                bindings
        );
GpuRuntimeMethodTestReferenceComparisonPlan referenceComparison =
        GpuRuntimeMethodTestProbes.compareWithReference(
                materialization,
                plan,
                invocationArguments -> MyKernelReference.kernel(
                        (float[]) invocationArguments[0],
                        (float[]) invocationArguments[1]
                )
        );
GpuRuntimeMethodTestGpuProbePlan gpuProbe =
        GpuRuntimeMethodTestProbes.executeGpuProbe(
                MyKernel_GpuLauncher.KERNEL_DESCRIPTOR,
                materialization,
                plan,
                GpuRuntimeMethodTestGpuProbeOptions.cached()
        );

System.out.println(plan.toMarkdown());
System.out.println(readiness.toMarkdown());
System.out.println(bindings.toMarkdown());
System.out.println(materialization.toMarkdown());
System.out.println(referenceComparison.toMarkdown());
System.out.println(gpuProbe.toMarkdown());
System.out.println(plan.artifactFields("methodTests"));
System.out.println(readiness.artifactFields("methodTestFixtures"));
System.out.println(bindings.artifactFields("methodTestValueBindings"));
System.out.println(materialization.artifactFields("methodTestInvocations"));
System.out.println(referenceComparison.artifactFields("methodTestReferenceComparisons"));
System.out.println(gpuProbe.artifactFields("methodTestGpuProbes"));
```

The plan reports whether the `IrGpu` artifact loaded, how many vectors were found, how many are usable as future
selection probes, and the first blocker when metadata is unavailable. The fixture readiness report resolves declared
input and expected-output refs as classpath resources, reads their raw bytes, records byte size plus SHA-256 as stable
evidence/cache keys, and performs a narrow JSON-object shape preview. The preview records the root kind, top-level field
count, primary field, primary value kind, and primary item count.

`fixtureValueBindings(...)` is the first value-binding preflight. It matches JSON fields to descriptor parameter names
for read-only, read-write, or value inputs plus read-write expected outputs. Supported fixture values include primitive
numeric scalars and arrays such as `float`, `float[]`, `int`, and `int[]`, plus `@GPUStruct` objects and `@GPUStruct[]`
arrays whose fields are primitive numeric values or nested `@GPUStruct` objects. Array fields inside a struct remain
unsupported, matching the current OpenCL ABI marshalling rules. It does not allocate buffers or invoke OpenCL, so
applications can use it as a safe preflight before enabling CPU-reference comparison or the optional GPU probe executor.

Struct fixture JSON is written as ordinary objects. For a kernel parameter `Point[] points` and read-write output
`Point[] output`, use arrays of objects with field names matching the Java struct fields:

```json
{
  "points": [
    { "x": 1.0, "y": 2.0 },
    { "x": 3.0, "y": 4.0 }
  ]
}
```

```json
{
  "output": [
    { "x": 2.0, "y": 4.0 },
    { "x": 6.0, "y": 8.0 }
  ]
}
```

`fixtureInvocationMaterialization(...)` is the next read-only step. It converts ready bindings into Java invocation
objects in descriptor-parameter order, including boxed scalar values, primitive arrays, struct objects, struct arrays,
and zero-filled read-write output arrays sized from expected-output fixtures. Struct materialization requires an
accessible no-arg constructor and writable fields. Expected-output values are materialized separately for a reference
comparison step. This still does not allocate GPU buffers or invoke OpenCL.

`compareWithReference(...)` runs a caller-supplied CPU/reference callback against cloned materialized arguments and
compares read-write outputs with materialized expected outputs. Numeric arrays compare element-by-element; struct
outputs compare deterministic flattened numeric field paths such as `[0].x` and `[0].y`. The same simple `abs=` / `rel=`
tolerances from `@GPUTest` apply to both numeric and struct fixtures. The reference callback is explicit on purpose: generated
examples and builds may rewrite `@GPU` method bodies to launcher calls, so runtime tooling must not assume the original
CPU body is still available through reflection. This is a bounded local correctness check that can run before or beside
the GPU probe executor; by itself it does not allocate GPU buffers, invoke OpenCL, persist probe-result caches, or affect
backend ranking.

`executeGpuProbe(...)` is the first bounded runtime execution step. It uses the current `GpuRuntime` backend, infers a
1D launch size from materialized expected-output fixture length when no explicit `GpuExecutionConfig` is supplied,
applies a default max-global-work-items cap, executes the generated descriptor with the materialized arguments, and
compares read-write outputs against expected fixtures with the same numeric tolerance logic. This API is opt-in: install
an OpenCL/custom backend before calling it, keep fixture sizes small, and treat the resulting `GpuRuntimeMethodTestGpuProbePlan`
as execution evidence rather than automatic backend-ranking policy. Each execution includes a stable
`GpuRuntimeMethodTestGpuProbeEvidenceKey` hash built from the test id, kernel source/resource, materialized fixture
values, expected outputs, launch config, compile options, backend/device identity, and compiler identity.

Use `GpuRuntimeMethodTestGpuProbeOptions.cached()` or `withCache(...)` to enable the process-local
`GpuRuntimeMethodTestGpuProbeCache`. Use `GpuRuntimeMethodTestGpuProbeOptions.persistentCached(path)` when probe
evidence should survive a new cache instance, or `persistentCached(path, maxEntryAge)` when old entries should expire.
Cache entries are keyed by the evidence hash, store only executed probe evidence, reject corrupted/mismatched/expired
properties files as cache misses, carry their creation timestamp, and mark returned executions with `cacheHit=true` when
a backend run was skipped.
Method-test metadata, fixture readiness, value binding, invocation materialization, reference comparison, GPU probe
execution, and GPU probe cache lookup now publish standard `GpuRuntimeLifecycleEvent` entries. Applications can observe
them through ServiceLoader `GpuRuntimeLifecycleService` implementations or pass an explicit `GpuRuntimeLifecycleEventBus`
to the overloads that accept one.
IR loading, validation, optimizer, fallback/rollback selection, lowerer/source-selection, compile, invocation, and artifact-dump events also carry
backend-neutral `runtime.*` fields such as `runtime.kernel.name`, `runtime.backend.target`, `runtime.backend.name`,
`runtime.device.label`, `runtime.irgpu.present`, `runtime.module.format`, `runtime.module.lowererVersion`,
`runtime.ir.selectedStage`, `runtime.ir.fallbackDecision`, `runtime.fallback.decision`,
`runtime.work.globalShape`, `runtime.work.localShape`, `runtime.cache.key`, and `runtime.status`. Older
OpenCL-specific fields remain present for compatibility, but new tooling should prefer the `runtime.*` vocabulary so
CUDA/Vulkan/Metal traces can use the same parser later.
Compile, invocation, and shutdown events also expose portable backend runtime-state fields such as
`runtime.backend.cache.mode`, `runtime.backend.cache.compiledKernel.count`,
`runtime.backend.cache.compileHit.count`, `runtime.backend.compile.count`,
`runtime.backend.invocation.count`, and `runtime.backend.buffer.native.count`. Events with typed backend-state
payloads also include `runtime.backend.state.present=true`, which lets journal consumers distinguish explicit runtime
state from older ad-hoc counter maps. Backend-compilation events carry a
`runtime.compilation.*` result summary for cache-key presence, module presence/format, compile-log presence,
binary-artifact count, and validation-evidence count. Invocation events also carry a
backend-neutral binding summary under `runtime.invocation.binding.*`: buffer, local, scalar, and total argument binding
counts are available as `runtime.invocation.binding.buffer.count`, `runtime.invocation.binding.local.count`,
`runtime.invocation.binding.scalar.count`, and `runtime.invocation.binding.argument.count`. Artifact-dump events expose
`runtime.artifactDump.*` fields so journals can tell how many output directories were planned and how many text,
binary, and source-location artifacts were written after a successful dump.
Runtime IR selection and production-mutation safety use portable `runtime.ir.*` fields. Prefer
`runtime.ir.selectedStage`, `runtime.ir.fallbackDecision`, `runtime.ir.productionGate.status`,
`runtime.ir.productionMutation.enabled`, `runtime.ir.productionMutation.productionGateStatus`, and
`runtime.ir.productionMutation.diagnostic` over older `runtimeProductionMutationSafety.*` report fields.
Backend source-selection events expose the same backend-neutral shape through `runtime.backend.source.*` fields.
The most useful fields for logs are `runtime.backend.source.status`, `runtime.backend.source.decision`,
`runtime.backend.source.selection`, `runtime.backend.source.available`,
`runtime.backend.source.promotionFirstBlocker`, `runtime.backend.source.productionSwitchingEnabled`, and
`runtime.backend.source.runtimeLoadMode`. They explain whether the runtime compiled descriptor source, selected
reconstructed `IrGpu` source, or failed closed before backend compilation.
Backend/device selection lifecycle events use the same vocabulary: `runtime.selection.status`,
`runtime.backend.selection.matched`, `runtime.device.discovery.available`, and selected `runtime.device.*` fields show
whether a backend and concrete device were chosen before backend compilation begins.
Automatic backend/device preflight events use the same descriptor/options/work vocabulary and add
`runtime.backendDevicePreflight.*` plus `runtime.failure.*` when the scoped preflight backend fails.
The failure block keeps `runtime.failure.type` and `runtime.failure.message` for older tooling, then adds stable
`runtime.failure.code`, `runtime.failure.phase`, `runtime.failure.category`, `runtime.failure.summary`,
`runtime.failure.catchable`, `runtime.failure.cause.*`, and `runtime.failure.context.*` facts for structured runtime
exceptions.
Backend adapter artifact fields also include portable `runtime.backend.adapter.*` and `runtime.backend.lowerer.*` keys,
so OpenCL, the CUDA source-preview/skeleton adapter, and planned adapters can be rendered by the same diagnostics
tooling.
Production candidate gates and manual promotion manifest artifacts mirror their evidence under
`runtime.production.candidateGate.*` and `runtime.production.manifest.*`; the manifest, activation-gate, and validation
report readers consume those portable keys first and keep the older unprefixed / `binding.*` / `authorization.*` keys as
compatibility mirrors only.
Backend selection, device discovery, and combined runtime-selection artifact maps include the same portable fields,
while their older prefixed keys remain available for compatibility.
Backend compile, invocation, runtime-state, and artifact-dump events use the same shared field composer, so logs can
follow `runtime.cache.key`, `runtime.module.*`, `runtime.work.*`, `runtime.backend.cache.*`,
`runtime.backend.compile.*`, `runtime.backend.invocation.*`, `runtime.compilation.*`,
`runtime.invocation.binding.*`, `runtime.artifactDump.*`, and `runtime.backend.state.*` across OpenCL now and future
execution adapters later.
Lifecycle event reports keep the indexed `field.N.key/value` representation, but also copy any `runtime.*` event field
to a direct `runtimeLifecycle.event.runtime.*` property so journals can be queried without unpacking the indexed list.

Native host memory is also behind a small service boundary. `GpuRuntimeNativeMemoryService` allocates closeable native
memory and returns both the native address and a `ByteBuffer` view. The built-in service uses LWJGL today; future Java
Panama modules can provide the same ServiceLoader contract without forcing CUDA descriptor encoders or argument packers
to depend directly on one allocation API. The current CUDA image/sampler descriptor allocation diagnostic exercises this
boundary through `validateCudaImageSamplerNativeDescriptorAllocationResult`, while SDK struct byte encoding and object
creation remain disabled.
The built-in LWJGL provider implementation now lives in `runtime.memory`; the root native-memory SPI names remain stable
for ServiceLoader providers and existing user imports.

Lifecycle events can also be routed into a pluggable logging backend through `GpuRuntimeLogService`. The built-in
`GpuRuntimeLifecycleLoggingService` bridges lifecycle events into the runtime logging bus, but it stays silent until a
log sink is present. For local console output, enable the built-in system stream sink:

```powershell
.\gradlew.bat :examples-app:runOpenClPracticalReleaseExample --console=plain "-Pjavatogpu.runtimeLog=system-out"
```

For application logging, provide a ServiceLoader implementation instead of depending on JavaToGpu internals:

```java
public final class Log4jGpuRuntimeLogService implements GpuRuntimeLogService {
    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("JavaToGpu");

    @Override
    public void log(GpuRuntimeLogRecord record) {
        String text = record.message() + " " + record.fields();
        switch (record.level()) {
            case TRACE -> LOG.trace(text, record.throwable());
            case DEBUG -> LOG.debug(text, record.throwable());
            case INFO -> LOG.info(text, record.throwable());
            case WARN -> LOG.warn(text, record.throwable());
            case ERROR -> LOG.error(text, record.throwable());
        }
    }
}
```

Register that class in `META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLogService`. JavaToGpu sorts
log services by extension order/id/version and isolates failures, so a broken logging sink cannot control runtime
selection, compilation, or invocation.
Built-in lifecycle/log implementations live in `runtime.observability`; the older root class names are compatibility
facades for existing code.

To smoke-test lifecycle and logging services without opening OpenCL/CUDA, use the observability harness:

```powershell
.\gradlew.bat :examples-app:runRuntimeObservabilityServiceHarnessExample --console=plain
```

Library tests can call `runtime.validation.GpuRuntimeObservabilityServiceHarness.loadFromServiceLoader().runSyntheticOpenCl()` directly.
The harness publishes one synthetic lifecycle event and one synthetic log record, then reports service counts, success
flags, artifact fields, and Markdown.

Device-selection policies have a matching hardware-free harness:

```powershell
.\gradlew.bat :examples-app:runDevicePolicyHarnessExample --console=plain
```

It runs the built-in plus ServiceLoader `GpuRuntimeDevicePolicy` registry against synthetic OpenCL CPU/iGPU/dGPU
candidates and prints the selected device, policy execution count, first blocker, and artifact-friendly status.

IR validation providers can be checked the same way, without javac annotation processing or GPU execution:

```powershell
.\gradlew.bat :examples-app:runIrValidationProviderHarnessExample --console=plain
```

Library tests can call `GpuIrValidationProviderHarness.loadFromServiceLoader().runSynthetic()` directly. The harness
runs synthetic helper/kernel IR methods through `GpuIrValidationRunner` and reports validation entries, diagnostics,
extension metadata, first blocker, and Markdown/artifact fields.

To let device selection consume already-recorded probe evidence, opt in through compile options:

```java
GpuRuntimeCompileOptions options = GpuRuntimeCompileOptions
        .defaults(GpuBackendTarget.OPENCL)
        .withPersistentMethodTestProbeEvidenceRanking(Path.of(".javatogpu/method-test-probes"));
```

This convenience method enables `GpuRuntimeMethodTestProbeMode.CACHE_ONLY` and configures the persistent evidence cache.
You can also set the mode explicitly when the cache path is configured separately:

```java
GpuRuntimeCompileOptions options = GpuRuntimeCompileOptions
        .defaults(GpuBackendTarget.OPENCL)
        .withMethodTestProbeMode(GpuRuntimeMethodTestProbeMode.CACHE_ONLY);
```

The ranking policy is cache-only: it does not compile or execute probes during device selection. For each candidate it
recomputes the stable evidence hash for selection-probe vectors, reads the configured cache, gives passed evidence a
ranking boost, rejects failed cached evidence, and treats missing evidence as neutral. This keeps startup predictable
while allowing applications and future tools to warm evidence ahead of backend/device selection.

Backend selection can read the same warmed cache when score-based ranking is explicitly enabled:

```java
GpuRuntimeCompileRequest request = new GpuRuntimeCompileRequest(
        MyKernel_GpuLauncher.KERNEL_DESCRIPTOR,
        options,
        deviceProfile,
        Optional.of(irGpuArtifact)
);

GpuRuntimeBackendPolicy policy = GpuRuntimeBackendPolicy.builder()
        .rankCandidatesByScore()
        .scoreCandidatesForCompileRequest(request)
        .scoreCandidatesWithCachedMethodTestProbeEvidence()
        .preferStandardBackendsWithPlannedDiagnostics()
        .build();
```

This bridge is also cache-only. Passed cached selection-probe evidence fills the backend `policyAdjustment` score bucket,
failed cached evidence applies a large negative adjustment, and missing evidence stays neutral. If the compile options
were created with `withPersistentMethodTestProbeEvidenceRanking(path, maxEntryAge)`, entries older than half of
`maxEntryAge` are gradually down-weighted before expiry; score diagnostics include `freshnessPermille`, `ageLimited`,
`oldestAgeMillis`, and `maxAgeMillis`. Use hard `require...` helpers when a backend must be rejected rather than merely
ranked lower.

Precomputed compiler feedback can also be used as advisory backend score evidence:

```java
GpuBackendCompilerFeedbackReport compilerFeedback =
        GpuBackendCompilerFeedbackRegistry.loadWithBuiltIns().inspect(snapshot);

GpuRuntimeBackendPolicy policy = GpuRuntimeBackendPolicy.builder()
        .rankCandidatesByScore()
        .scoreCandidatesWithCompilerFeedback(compilerFeedback)
        .preferStandardBackendsWithPlannedDiagnostics()
        .build();
```

`scoreCandidatesWithCompilerFeedback(report)` reads only the supplied report. It does not compile candidates during
selection. The score bridge applies only to a matching backend target, rewards available resource evidence and healthy
metrics such as low register pressure, zero spills, zero stack frame, and known occupancy, and applies bounded penalties
for high register pressure, spills, stack frame bytes, or heavy local-memory use. Treat this as placement evidence, not a
correctness gate; `@GPUTest` probe evidence has much stronger score weight.

Compiler-feedback providers can be checked without a backend compiler:

```powershell
.\gradlew.bat :examples-app:runCompilerFeedbackHarnessExample --console=plain
```

Library tests can call `runtime.validation.GpuBackendCompilerFeedbackHarness.loadWithBuiltIns().runSyntheticOpenCl()` directly. The harness
feeds synthetic compiler logs through the same provider registry and reports the selected provider, parsed metrics,
execution outcomes, artifact fields, and Markdown.

To run all hardware-free extension smoke examples together:

```powershell
.\gradlew.bat :examples-app:runExtensionHarnessExamples --console=plain
```

Use this aggregate task before native OpenCL/CUDA checks when you only need to verify ServiceLoader registration,
extension ordering, fail-soft isolation, and basic report rendering.

To check the built-in OpenCL provider/factory SPI contract without opening an OpenCL platform or context:

```powershell
.\gradlew.bat :processor:validateOpenClBackendSpiContract --console=plain
```

This verifies the metadata contract for provider id/version, production execution support, compile/prepare/invoke stage
coverage, `opencl-c` module format, shared pipeline factory, and stable artifact aliases.

When the application knows the shape of the workload before real backend execution exists, pass workload hints:

```java
GpuRuntimeWorkloadHints hints = GpuRuntimeWorkloadHints.builder()
        .expectedItemCount(1_000_000L)
        .preferredWorkGroupSize(256)
        .memoryIntensity(GpuRuntimeWorkloadIntensity.HIGH)
        .arithmeticIntensity(GpuRuntimeWorkloadIntensity.HIGH)
        .requireCapability(GpuRuntimeCapability.COMPUTE_CAPABILITY)
        .preferModuleFormat(GpuBackendModuleFormat.PTX)
        .build();

GpuRuntimeBackendPolicy policy = GpuRuntimeBackendPolicy.builder()
        .rankCandidatesByScore()
        .scoreCandidatesWithWorkloadHints(hints)
        .preferStandardBackendsWithPlannedDiagnostics()
        .build();
```

`scoreCandidatesWithWorkloadHints(hints)` is an advisory placement signal. It rewards candidates whose report, device
profile, or provider metadata match the declared intent and penalizes obvious mismatches, but it does not reject a
candidate by itself. Use hard `requireDeclaredCapability(...)`, `requireDeclaredModuleFormat(...)`, or
`requireExecutionPipelineAvailable()` when the application cannot run without a capability or artifact family.

When you do not want to hand-write those hints, let the runtime infer conservative hints from the generated descriptor
and already-loaded `IrGpu` artifact:

```java
GpuRuntimeBackendPolicy policy = GpuRuntimeBackendPolicy.builder()
        .rankCandidatesByScore()
        .scoreCandidatesWithInferredWorkloadHints(
                MyKernel_GpuLauncher.KERNEL_DESCRIPTOR,
                irGpuArtifact
        )
        .preferStandardBackendsWithPlannedDiagnostics()
        .build();
```

`scoreCandidatesWithInferredWorkloadHints(...)` looks only at metadata that is already present: parameter types/access
(`double[]`, images, local buffers, struct arrays), global/local/constant address-space usage, required IrGpu features,
entry constraints, launch dimensions, and visible math density in descriptor/IrGpu bodies. It never compiles, probes, or executes candidates during selection. The
result is still advisory; fallback order remains unchanged unless `rankCandidatesByScore()` is enabled.

Warm evidence explicitly before selection when you want stronger placement confidence without making the selection
policy execute kernels:

```java
GpuRuntimeMethodTestProbeEvidenceWarmupPlan warmup =
        GpuRuntimeMethodTestProbeEvidenceWarmup.warmSelectionProbeEvidence(
                MyKernel_GpuLauncher.KERNEL_DESCRIPTOR,
                MyKernel.class.getClassLoader(),
                List.of(GpuRuntimeMethodTestProbeEvidenceWarmupCandidate.owned(deviceProfile, backendFactory)),
                GpuRuntimeMethodTestGpuProbeOptions
                        .persistentCached(Path.of(".javatogpu/method-test-probes"))
                        .withCompileOptions(GpuRuntimeCompileOptions.defaults(GpuBackendTarget.OPENCL))
        );

System.out.println(warmup.toMarkdown());
```

The warm-up helper runs only selection-probe vectors, installs each caller-provided backend/device candidate in a scoped
runtime backend, writes successful or failed executions through the configured cache, and emits lifecycle events for the
warm-up boundary plus the existing metadata/fixture/materialization/GPU-probe/cache stages. Selection can then consume
the warmed cache through `withPersistentMethodTestProbeEvidenceRanking(path)` while staying read-only.

When you want OpenCL discovery, explicit warm-up, and cache-only selection as one auditable operation, use the OpenCL
selection helper:

```java
GpuRuntimeMethodTestProbeEvidenceSelectionPlan placement =
        GpuRuntimeMethodTestProbeEvidenceSelection.warmAndSelectOpenCl(
                MyKernel_GpuLauncher.KERNEL_DESCRIPTOR,
                MyKernel.class.getClassLoader(),
                GpuRuntimeMethodTestGpuProbeOptions
                        .persistentCached(Path.of(".javatogpu/method-test-probes"))
                        .withCompileOptions(baseOptions),
                baseOptions,
                2
        );

GpuRuntimeDeviceProfile selected = placement.selectedDevice().orElseThrow();
System.out.println(placement.toMarkdown());
```

`warmAndSelectOpenCl(...)` discovers devices, creates OpenCL warm-up candidates, normalizes missing probe cache options
to the shared cache, carries persistent cache directories and expiry into the cache-only selection compile options, and
keeps warm-up candidates separate from the full discovered device list. That makes partially warmed evidence visible:
warmed devices can be `passed` or `failed`, while devices not warmed remain `missing` and neutral.
`GpuRuntimeMethodTestProbeEvidenceWarmupCandidates.openClGpuDevices(...)` is the built-in OpenCL convenience helper for
that candidate list: it uses policy-ranked discovery order when available, skips CPU devices, and lazily creates owned
OpenCL backends only when the explicit warm-up phase actually runs.

The helper also publishes lifecycle events around the high-level operation: selection start, OpenCL discovery start/end,
warm-up candidate selection, and final selection completion. A `GpuRuntimeLifecycleService` can record those events for
logs or a journal without changing the selection result. This keeps the future runtime journal path service-based: put a
listener implementation on the classpath, and `warmAndSelectOpenCl(...)` becomes visible from discovery through
cache-only placement.

Runtime compile artifact dumps include `runtime-method-test-evidence.properties` for each compiled kernel. That artifact
records entry-method `@GPUTest` metadata counts, selection-probe counts, and cache-only probe-evidence ranking facts
when the ranking policy participated. `openClValidationReport` aggregates those per-kernel artifacts into a `Method Test
Evidence` section so CI logs can distinguish kernels with no method-test metadata, kernels with metadata but no warmed
cache evidence, and kernels where cached passed/failed/missing evidence affected ranking.

The examples app includes a portable walkthrough that uses a synthetic reference backend to record one persistent probe
entry, then demonstrates cache-only ranking without requiring OpenCL hardware:

```powershell
.\gradlew.bat :examples-app:runMethodTestProbeEvidenceRankingExample --console=plain
```

Use `-Pjavatogpu.methodTestProbeEvidenceCacheDir=...` when you want to inspect or reuse the generated cache directory.

When you want to test the same flow against actual OpenCL discovery and backend execution, run:

```powershell
.\gradlew.bat :examples-app:runOpenClMethodTestProbeEvidenceSelectionExample --console=plain
```

That example calls `warmAndSelectOpenCl(...)`, which discovers OpenCL, turns discovered GPU profiles into owned
`OpenClGpuRuntimeBackend` warm-up candidates, pins each probe run to the candidate device id, and then prints the
markdown report. The selection phase remains `CACHE_ONLY`; only the explicit warm-up phase may execute the tiny
method-test probe kernels. Use `-Pjavatogpu.methodTestProbeOpenClEvidenceCacheDir=...` to control the persistent cache
directory and `-Pjavatogpu.methodTestProbeOpenClWarmupLimit=1` to cap the number of warmed devices.
The same runnable also enables lifecycle output through services: a full `runtime-lifecycle.jsonl` journal and a compact
`opencl-evidence-selection.trace` are written next to the evidence cache by default, and the console prints only the
`warmAndSelectOpenCl(...)` trace lines. The compact example trace includes a `summary=` segment when backend-state,
compilation, invocation-binding, or artifact-dump portable fields are present, so users can inspect runtime behavior
without opening the full JSONL journal. Use `-Pjavatogpu.lifecycleJournalFile=...`,
`-Pjavatogpu.lifecycleJournalFormat=properties`, or `-Pjavatogpu.exampleLifecycleTraceFile=...` to override the outputs.

Example compact trace line:

```text
BACKEND_COMPILATION_COMPLETED | backend=OPENCL | kernel=javatogpu/demo.cl | profile=off | status=completed | summary=compilation module=opencl-c cacheKey=true log=false binaries=0 | message=OpenCL program compiled
```

When you want one user-facing walkthrough instead of separate example commands, run:

```powershell
.\gradlew.bat :examples-app:runOpenClPracticalReleaseExample --console=plain
```

It combines backend/device explanation, method-test fixture/reference preflight, real OpenCL probe warm-up,
cache-only placement, launch-shape guidance, vector/struct/packed-root-blob/image workload smoke, image-helper guidance,
optimizer artifact review guidance, and lifecycle trace output in one report. Use
`-Pjavatogpu.practicalOpenClEvidenceCacheDir=...` to choose the evidence and journal directory.
The image helper section points to `OpenClImageWorkflow.rgbaIntToFloat2D(...)`, which bundles the common 2D RGBA image
input/output/sampler/readback path while keeping the OpenCL resources explicit. It also exposes a natural
`images.executionConfig()` for one-work-item-per-pixel 2D kernels plus shape helpers for logs and validation.
The optimizer review section points to `runOptimizationJournalExample`, the default journal root, the before/after
OpenCL files (`original.backend.opencl-c` and `optimized.backend.opencl-c`), the selected compiled file
(`backend.opencl-c`), and the handoff/evidence files that explain why the default path stays review-only.

## Explicit Launch Sizes

Generated direct calls are convenient, but lower-level workloads sometimes need explicit launch sizes.

One-dimensional launch:

```java
GpuRuntime.invoke(
        GpuExecutionConfig.oneDimensional(itemCount),
        descriptor,
        input,
        output
);
```

Two-dimensional launch:

```java
GpuRuntime.invoke(
        GpuExecutionConfig.twoDimensional(width, height),
        descriptor,
        input,
        output
);
```

Three-dimensional launch:

```java
GpuRuntime.invoke(
        GpuExecutionConfig.threeDimensional(width, height, depth),
        descriptor,
        input,
        output
);
```

Explicit local sizes are also supported by the matching config factory overloads. After OpenCL compiles the selected kernel, JavaToGpu validates the total explicit local work-group size against that kernel's `CL_KERNEL_WORK_GROUP_SIZE` limit. For multidimensional launches, the validated size is the product of the local dimensions. An oversized explicit group fails before enqueue with `GpuRuntimeCapabilityException`. If no local size is specified, JavaToGpu leaves work-group selection to the OpenCL driver.

For logs, examples, or diagnostics, `GpuExecutionConfig` exposes readable launch-shape helpers:

```java
GpuExecutionConfig config = GpuExecutionConfig.twoDimensional(16, 8, 4, 2);

System.out.println(config.summary());      // 2D global=16x8, local=4x2
System.out.println(config.globalShape());  // 16x8
System.out.println(config.localShape());   // 4x2, or auto when local sizing is driver-selected
```

## Generated Launcher Helpers

For packed/blob workloads where logical item count does not match raw buffer length:

```java
GpuGeneratedLauncherInvoker.invokeWithGlobalWorkSize(
        OwnerClass.class,
        "kernel",
        itemCount,
        blob,
        view,
        output
);
```

For explicit multidimensional configs, use generated launcher config entry points or `GpuGeneratedLauncherInvoker.invokeWithConfig(...)` where applicable.

For a narrow scalar-style result, keep the kernel ABI as `void + output buffer`, but let the generated launcher allocate the single primitive output array for you:

```java
GpuGeneratedLauncherInvoker.GeneratedLauncher launcher =
        GpuGeneratedLauncherInvoker.launcher(OwnerClass.class, "kernel");

float first = launcher.invokeReturningFirstWithGlobalWorkSizeAs(
        Float.class,
        itemCount,
        input
);
```

The same convenience can open a standard backend+device scope for one call:

```java
float first = launcher.invokeReturningFirstWithGlobalWorkSizeAndStandardBackendAndDeviceAs(
        Float.class,
        itemCount,
        GpuRuntimeCompileOptions.defaults(GpuBackendTarget.OPENCL).preferDeviceVendor("NVIDIA"),
        input
);
```

Keep the `GeneratedLauncher` handle when you call the same kernel repeatedly. It resolves the generated launcher class,
descriptor, and return-first metadata once, while still invoking the generated overloads so fallback/variant routing stays
intact.

This helper is generated only when the `@GPU` method has exactly one primitive `@GPUGlobal` read-write output array. The helper allocates that output array using the launch item count, invokes the normal generated launcher, and returns `output[0]`. The `*As(...)` reflection helpers validate the generated return type before launching, so asking for `Integer.class` from a float-return helper fails before the kernel runs. If a kernel has multiple mutable primitive output arrays, object/struct outputs, or a different result shape, use the explicit output-buffer form instead. This is not arbitrary non-`void` `@GPU` support; it is a small convenience adapter over the existing launcher ABI.

To see whether the helper exists, and why it was skipped, inspect the generated launcher metadata through the reflection helper:

```java
GpuGeneratedLauncherReturnValueConvenienceReport report =
        launcher.returnValueConvenience();

System.out.println(report.summary());
```

Common skip reasons are `no-read-write-output-array`, `multiple-read-write-output-arrays`, `output-array-component-not-supported`, and `method-return-type-not-void`.

During annotation processing, JavaToGpu also emits a non-failing `NOTE` for almost-matching kernels where the helper was skipped, such as kernels with multiple primitive read-write output arrays. Suppress those compile-time notes with:

```groovy
options.compilerArgs += '-Ajavatogpu.returnValueConvenienceDiagnostics=quiet'
```

## Runtime Compile Options

Compile options are optional and keep the normal kernel arguments unchanged. Use them when you need backend-specific build flags or want to select a future runtime optimization profile explicitly.

```java
GpuRuntimeCompileOptions compileOptions = new GpuRuntimeCompileOptions(
        GpuBackendTarget.OPENCL,
        List.of("-cl-fast-relaxed-math"),
        "diagnostic"
);

GpuRuntime.invokeWithCompileOptions(
        GpuExecutionConfig.oneDimensional(itemCount),
        compileOptions,
        descriptor,
        input,
        output
);
```

Generated launchers expose the same path:

```java
OwnerClass_kernel_GpuLauncher.invokeWithConfigAndCompileOptions(
        GpuExecutionConfig.oneDimensional(itemCount),
        compileOptions,
        input,
        output
);
```

Reflection launcher helpers also support compile options for dynamically loaded/generated kernels:

```java
GpuGeneratedLauncherInvoker.invokeWithConfigAndCompileOptions(
        OwnerClass.class,
        "kernel",
        GpuExecutionConfig.oneDimensional(itemCount),
        compileOptions,
        input,
        output
);
```

The default optimization profile is `off`. Keep production code on `off` unless you are collecting diagnostics or testing an opt-in optimizer path.

OpenCL compile options are validated before the runtime touches the device. Supported options include common OpenCL build flags such as `-cl-fast-relaxed-math`, `-cl-mad-enable`, `-cl-opt-disable`, `-cl-std=...`, `-DNAME=VALUE`, and `-Ipath`. Backend-mismatched options fail early with a clear Java exception instead of being ignored by the runtime.

### Startup Device Self-Tests

OpenCL performs a bounded compile, enqueue, and readback correctness smoke before selecting among multiple discovered devices. Passed evidence contributes to deterministic ranking; failed correctness evidence rejects the device before JavaToGpu creates the production OpenCL context.

The default mode is `AUTO`: self-tests run when multiple device candidates are present and are skipped for a single candidate. Override this per invocation when startup latency or strict deployment validation matters:

```java
GpuRuntimeCompileOptions options = GpuRuntimeCompileOptions
        .defaults(GpuBackendTarget.OPENCL)
        .withDeviceSelfTestMode(GpuRuntimeDeviceSelfTestMode.REQUIRED);
```

- `AUTO` - run self-tests for multi-device discovery and reuse cached evidence.
- `DISABLED` - do not run or apply self-test evidence.
- `REQUIRED` - require passed evidence for every selectable candidate, including single-device systems.

Evidence is cached for the current process. The identity includes backend, device id/label/vendor, device class, unified-memory topology, driver version, runtime API version, self-test runner id/version, and JavaToGpu compiler identity. Changing any of those values invalidates the old entry.

After correctness passes, OpenCL performs one warm-up plus five measured compute and host/device round-trip samples. JavaToGpu discards the minimum and maximum sample and uses the median of the remaining three. The runtime selects a bounded workload profile from the detected device class:

| Device class | Profile | Compute workload | One-way transfer | Noise limit | Compute / transfer score caps |
| --- | --- | ---: | ---: | ---: | ---: |
| dGPU | `dgpu-balanced-v1` | 262,144 items x 128 iterations | 4 MiB | 300 permille | 3,000,000 / 1,000,000 |
| iGPU | `igpu-unified-v1` or `igpu-conservative-v1` | 131,072 items x 64 iterations | 2 MiB | 400 permille | 2,000,000 / 250,000 |
| CPU OpenCL | `cpu-opencl-conservative-v1` | 32,768 items x 32 iterations | 1 MiB | 500 permille | 500,000 / 100,000 |

An iGPU with unified memory uses the `unified-memory-round-trip` transfer model. Dedicated, integrated, unified, and host-memory rates are compared only with results from the same transfer model; compute rates are compared only inside the same workload profile. This prevents a small iGPU workload or shared-memory transfer path from being ranked directly against a dGPU-sized workload.

Compute and transfer stability are evaluated independently. Stable compute evidence may still affect ranking when transfer timing is noisy, and the reverse is also true. A noisy metric remains visible in selection artifacts but receives zero score. These measurements are a short startup ranking smoke, not a general GPU benchmark; real Intel/AMD iGPU validation, register-pressure stress, long-running stability, and persistent-cache policies remain roadmap work.

### Register-Pressure Analysis

The default runtime IR pipeline performs an advisory register-pressure analysis before transformation passes. It reads typed `IrGpu` method bodies and estimates the peak number of simultaneously required value slots from:

- kernel and helper parameters;
- local scalar and vector declarations;
- private arrays;
- nested expression evaluation pressure;
- branches, loops, and switch bodies.

The current `register-pressure-interprocedural:v4` model performs backward last-use analysis. Sequential locals are released after their final reference, unused parameters do not contribute to peak liveness, branch paths are merged at control-flow joins, and loops are iterated to a fixed point so loop-carried values remain live across the back-edge. Structured `break` and `continue` targets are modeled separately.

Typed `IrGpu` references currently store source names, so the analyzer first assigns a stable lexical identity to each parameter, local declaration, and private array. Nested declarations with the same source name remain separate live values. Unresolved references receive isolated conservative identities and add an advisory diagnostic instead of being merged with an unrelated declaration.

Helper calls are analyzed after all method-local estimates are available. A non-inline helper uses a separate frame, so caller and callee pressure are compared rather than blindly added. A helper marked inline may add its non-reusable frame pressure to values live at the call site. Source and emitted helper names are both resolved. Recursive call cycles are reported but are not expanded indefinitely, and unresolved helpers remain advisory edges.

The result uses `LOW`, `MODERATE`, `HIGH`, `CRITICAL`, or `UNAVAILABLE`. Device-class and vendor profiles provide conservative budgets, for example 64 value slots for the initial NVIDIA/AMD dGPU profile and 24 for the initial iGPU profile. These values are planning heuristics, not the physical register count reported by the GPU compiler. Driver compilers may allocate, merge, spill, or eliminate values differently.

High and critical estimates add optimizer diagnostics with the hottest method, estimated value-register count, advisory budget, and suggested reductions such as fewer simultaneously live temporaries, smaller private arrays, narrower vectors, or less unrolling. The analysis never changes the selected IR and never counts as accepted optimizer proof.

Artifact dumps store the complete result in `runtime-ir-analysis.properties`. Per-method fields include total parameter/local/private-array storage, `peakLiveRegisters`, expression-temporary peak, scoped-variable count, shadowed-variable count, unresolved-reference count, budget, utilization, level, and typed-node counts. Per-call fields include resolution state, inline/recursive flags, caller-live values, argument/result pressure, callee pressure, additional frame pressure, and combined estimate.

Set `-Djavatogpu.opencl.runtimeCompileArtifactDirectory=<directory>` to dump the full runtime compile artifact bundle for each OpenCL kernel invocation without enabling the operational validation report path. Each kernel gets a sanitized subdirectory under that root. When IR artifacts are available, the bundle includes `original.irgpu.properties` and `optimized.irgpu.properties` so the pre/post optimizer IR can be compared directly, plus `original.backend.opencl-c` and `optimized.backend.opencl-c` for before/after generated OpenCL backend source. If CUDA source preview reconstruction is possible from the same `IrGpu`, the bundle also includes `original.preview.backend.cuda-c`, `optimized.preview.backend.cuda-c`, and `cuda-source-preview.properties`; these files are hardware-free preview diagnostics only and are not compiled or executed. `backend.opencl-c` remains the selected OpenCL source that the backend actually compiles. In the default `GpuRuntimeCompileOptions.openCl(...)` path this remains the original/pass-through source; in explicit `GpuRuntimeCompileOptions.openClIrOptimizerExperimentalApply(...)` runs it can become the optimized source if runtime-equivalence and production gates do not reject the selected optimized IR. The bundle also includes `runtime-ir-handoff.properties`, `optimizer-report.txt` when reports exist, backend source artifacts, provenance, and diagnostics. The dump itself is diagnostic-only and does not enable production mutation or source switching.

Workload-level source-promotion gates mirror per-kernel source-selection decisions as both legacy `kernel.N.sourceSwitching.*` fields and portable `kernel.N.runtime.backend.source.*` fields. New report tooling should prefer the portable fields for status, decision, selected source, production-switching state, first blocker, and runtime load mode. Workload gates also mirror aggregate source decisions under indexed `runtime.backend.source.decision.*` fields and expose compact `runtime.backend.source.decisions`, `runtime.backend.source.promotionFirstBlockers`, and `runtime.backend.source.promotionFirstBlockerFamilies` summaries for CI, while keeping `sourceSwitching.decisions` and unprefixed blocker summaries for compatibility. Aggregate blocker evidence is mirrored under portable `runtime.backend.source.promotionFirstBlocker.*` and `runtime.backend.source.promotionFirstBlockerFamily.*` fields while retaining legacy `sourceSwitching.sourcePromotionFirstBlocker.*` keys. Workload gates and production-promotion explainability artifacts also mirror aggregate production readiness under portable `runtime.backend.source.productionSwitchingEnabled.*`, `runtime.backend.source.productionPromotionDecisionMode.productionEnabled.*`, `runtime.backend.source.productionPromotionOperatorAccepted.*`, and `runtime.backend.source.productionDecision.*` fields, while retaining older unprefixed and `sourceSwitching.productionDecision.*` compatibility keys. Controlled production validation evidence is mirrored under portable `runtime.production.sourceSwitching.controlled.*`, `runtime.production.mutation.controlled.*`, `runtime.production.activationToken.smoke.*`, and `runtime.production.activationToken.negative.*` fields while retaining legacy `controlledProductionSourceSwitching.*`, `controlledProductionMutation.*`, `controlledProductionActivationTokenSmoke.*`, and `controlledProductionActivationTokenNegative.*` keys. Candidate-gate and manual manifest artifacts now mirror their evidence under `runtime.production.candidateGate.*` and `runtime.production.manifest.*`, while keeping unprefixed, `binding.*`, and `authorization.*` compatibility keys. The compact workload and production-promotion summaries preserve those portable aggregate fields for CI. The OpenCL validation report, history, formatter, validator, summary, manifest, activation-gate, and production-decision readers already read these portable fields first, then fall back to legacy `sourceSwitching.*` / unprefixed / controlled-production / manifest-binding keys for older artifacts.

OpenCL isolated runtime-equivalence checks write raw pipeline comparison cases into `runtime-equivalence.properties` when invocation arguments use supported array shapes. Each case records the comparison mode, original invocation inputs, descriptor-source reference outputs, reconstructed-source candidate outputs, exact tolerance metadata, per-output equivalence flags, and diagnostics. Primitive arrays are written as readable vectors, vector and struct arrays as deterministic packed Base64, scalar values as literals, and opaque image/sampler/runtime objects as stable type tags without process-specific handles. This evidence validates source reconstruction and remains separate from per-family optimizer proof.

Non-analysis optimizer passes with runtime-equivalence payload evidence also produce `runtime-optimizer-family-equivalence-payload.properties` plus durable files under `runtime-optimizer-family-equivalence-payload/family-<index>-<name>/pass-<index>/`. Each pass directory contains `manifest.properties`, CPU/reference, pre-optimization, post-optimization, tolerance, failure-fixture, and diagnostics properties files. When proof fields contain structured case evidence, the component files include deterministic indexed fields for case names, inputs, raw outputs, tolerances, equivalence flags, and diagnostics. Missing components are materialized with `status=not-recorded`; they are never inferred from another pass. Artifact paths are normalized and must remain inside the kernel's `runtime-compile-artifacts` directory. These files are diagnostic evidence only and cannot enable source switching or production mutation.

When the optional IR optimizer bridge participates, each kernel directory may also receive `runtime-ir-optimizer-evidence.properties`. The artifact records only proposal/provider evidence from stable `javatogpu.ir-optimizer` sources: pass counts, proposal-only versus selected optimized counts, rollback counts, IR identities, proof fields, approval-template applicability, and diagnostics. Proposal-only materialized candidates are composed through a separate review-working artifact, so `optimized.irgpu.properties` and `optimized.backend.opencl-c` can accumulate safe review transforms from multiple providers while `backend.opencl-c` still remains the selected source compiled by OpenCL. The validation sandwich now also has an internal optimized-artifact candidate envelope for proposed artifacts; it records candidate identity and selection blockers as report-only evidence while final runtime IR selection remains separate and fail-closed. Candidate envelope proof fields are also lifted into top-level `optimizedArtifactCandidate.*` summary fields for status/counts, ready/blocked counts, first blockers, selection-ready/applied counts, selected-IR replacement counts, mutation-policy count, and disabled selection/replacement guardrails. Backend-neutral source materialization proof is aggregated into `backendNeutralSourceMaterialization.*` fields for pass count, candidate count, source-ready count, total source length, status, and first blocker; it is review-only source metadata evidence and does not require runtime-equivalence review by itself. Constant-folding preview proof is also aggregated into `constantFoldingPreview.*` fields for pass count, candidate count, skipped blockers, required runtime-equivalence/approval gates, and unresolved integer-overflow / floating-point-rounding safety flags. A separate constant-folding materialization provider can now emit a review-only transformed artifact for plain 32-bit integer literal unary `-` plus binary `+`, `-`, `*`, and exact `/` expressions where the divisor is non-zero and the division has no remainder, including nested safe folds materialized to a fixed point. It also supports a narrow pure-symbolic identity slice for `x + 0`, `0 + x`, `x - 0`, `x * 1`, `1 * x`, and `x / 1`, where the retained side can be a deterministic `GpuIrPureExpression` composed of named variables, plain int32 literal leaves, arithmetic binary nodes, and unary minus nodes; mixed identity chains such as `((x / 1) + 0)`, repeated identities across multiple roots, and multiple method bodies are recomputed one reachable rewrite per fixed-point pass so text and typed roots stay aligned. `x * 0`, `1 / x`, and broader division algebra remain excluded until stronger side-effect / unused-operand and equivalence proof exists. Top-level `constantFoldingMaterialization.*` fields record pass/transformed-node/literal-rewrite/identity-rewrite/fixed-point-pass counts, all-method-body rewrite scope, reachable-node-scan and rewrite-granularity metadata, body-text replacement scope, exact-int or symbolic-identity runtime-equivalence payload presence/pass counts, per-case method/node/rewrite-kind identity, status, skipped divide-by-zero/non-even-division counts, and first blocker. The same compact proof payload is routed into `runtime-optimizer-family-equivalence-payload.properties` plus durable component files, so this narrow materialized family can become runtime-equivalence `review-ready` while approval/manual review still blocks completion. Pending approval templates also export `pass.N.approvalTemplate.field.runtimeEquivalencePayload.*`, `pass.N.approvalTemplate.field.resourceDirectory`, and `pass.N.approvalTemplate.field.resourcePath` fields so the manual-review package is visibly bound to the same payload resource, comparison mode, completeness, case count, and manifest location; top-level `approvalTemplate.runtimeEquivalencePayloadRequired.count`, `approvalTemplate.runtimeEquivalencePayloadPresent.count`, `approvalTemplate.runtimeEquivalencePayloadPassed.count`, and `approvalTemplate.runtimeEquivalencePayloadComplete.count` summarize those bindings across pending templates. The bridge also probes the deterministic approval manifest classpath resource and emits `approvalManifest.*` fields for missing, blocked, or accepted manifest validation. `reviewPackage.approvalManifest.*` records the next package boundary over those templates, including manifest requirement, present/accepted counts, resource-path summary, first blocker, manual-review-only state, and disabled production mutation / selected-IR replacement. Safe-local-CSE preview proof is aggregated into `safeLocalCsePreview.*` fields for pass count, expression/candidate/duplicate/equivalence-class counts, unsupported-operator / impure-operand / control-flow-boundary blockers, required runtime-equivalence/approval gates, and unresolved dominance / side-effect-freedom proof flags. Safe-local-CSE materialization proof is aggregated into `safeLocalCseMaterialization.*` fields for pass count, existing-local binding count, introduced-temporary count, transformed-node count, body-text replacement count, fixed-point pass count, runtime-equivalence payload presence/pass counts, status, and first blocker. This materialized family rewrites repeated pure binary expressions to existing local references or conservative introduced local temporaries in straight-line `ir-text-v1` bodies, and changes only the optimized review artifact. Intrinsic materialization proof is aggregated into `madFmaMaterialization.*`, `clampMaterialization.*`, `stepMaterialization.*`, and `mixMaterialization.*` fields for pass/candidate/transformed-node/body-text-replacement counts, fast-math or strict-float safety policy, direct/inverted step accounting, canonical/expanded/MAD-expanded mix accounting, runtime-equivalence payload presence/pass counts, status, and first blocker. Loop-vectorization proof is aggregated into `loopVectorizationMaterialization.*` fields for transformed-loop/text-replacement/typed-body materialization counts and ordered-reduction safety evidence. Typed dead-code preview proof is aggregated into `typedDeadCodePreview.*` fields for pass count, typed/reachable/unreachable node counts, missing-root / missing-child-reference / side-effecting-unreachable blockers, required runtime-equivalence/approval gates, and unresolved side-effect-freedom proof flags. The artifact also records `previewReadiness.*` fields with aggregate status, recorded/candidate/blocked family counts, and a per-family status summary so CI can distinguish no-candidate previews from proof-blocked previews and review-ready runtime-equivalence candidates. `runtimeEquivalenceReview.*` records the next fail-closed review boundary: status, eligibility, requirement, first blocker, family summary, disabled production mutation, disabled selected-IR replacement, and manual-review-only state. `reviewPackage.*` records the manual-review package boundary above that review gate, including package status, requirement/completeness, first blocker, proposal-pass count, pending approval count, original/optimized IR and proof-summary requirements, manual-review-only state, and disabled production mutation / selected-IR replacement. `openClValidationReport` aggregates this into `Runtime IR Optimizer Evidence` for CI visibility, including optimized-artifact candidate status/blockers, backend-neutral source materialization readiness, approval-template payload readiness, approval-manifest package readiness, constant-folding preview/materialization, safe-local-CSE preview/materialization, mad/FMA, clamp, step, mix, loop-vectorization, typed dead-code preview/materialization totals, preview-readiness status, runtime-equivalence review eligibility, and review-package status. `validateOpenClRuntimeIrOptimizerEvidence` then validates the raw runtime evidence files when present and fails CI if those fail-closed guardrails are missing, completed early, or production-enabling; normal vendor routines pass `--allow-missing` so optional `not-recorded` optimizer evidence does not fail a non-optimizer run. The section and validator are evidence-only and do not participate in history, source switching, selected-IR replacement, or production mutation gates.

For local optimized-artifact execution tests, use `GpuRuntimeCompileOptions.openClIrOptimizerExperimentalApply(...)`. That preset records `experimentalApply.*` fields in `runtime-ir-optimizer-evidence.properties` and allows `backend.opencl-c` to come from the selected optimized IR if runtime-equivalence and production gates allow it. The default validator still rejects applied optimized-artifact selection; pass `--allow-experimental-apply` only for intentionally local apply-mode artifacts.

Loop-vectorization evidence now includes the `loopVectorizationMaterialization.typedBody.rebuild.*` fields. These record attempted, parsed, built, graph-validated, and rejected typed-body rebuild counts plus rebuild status and first blocker, so dumps explain whether `optimized.irgpu.properties` contains a validated reconstructed typed body or a deliberately invalidated review typed body after the source rewrite. Rejected or invalidated rebuild evidence keeps loop-vectorization blocked even when the source rewrite and runtime-equivalence payload evidence are present.

Typed dead-code materialization is now represented as a separate review-only evidence family. `typedDeadCodeMaterialization.*` records pass count, typed/unreachable/removed node counts, missing-root / missing-child-reference / side-effecting-unreachable blockers, runtime-equivalence payload presence/pass counts, side-effect-freedom proof, status, and first blocker. It removes only unreachable pure typed nodes from the optimized review artifact, leaves method text bodies unchanged, and is included in `runtimeEquivalenceReview.*` / OpenCL Markdown summaries without enabling source switching, selected-IR replacement, or production mutation.

Safe-local-CSE materialization is also review-only. `safeLocalCseMaterialization.*` records pass count, existing-local binding count, introduced-temporary count, transformed-node count, body-text replacement count, fixed-point pass count, runtime-equivalence payload presence/pass counts, status, and first blocker. In dumps, this is the easiest first alpha source-changing example to inspect: `optimized.backend.opencl-c` can replace repeated expressions with existing local variables or conservative generated locals such as `jtg_cse0`, while `backend.opencl-c` remains the original selected source compiled by OpenCL.

For introduced-local CSE, check `safeLocalCseMaterialization.introduedTemporary.count`, `safety.newTemporaryIntroduced`, and the per-case rewrite kind `safe-local-cse-introduce-local`. The optimizer still requires straight-line `ir-text-v1`, exact text/typed occurrence agreement, no input reassignment between uses, runtime-equivalence payload evidence, and manual review before any future production selection gate can be considered.

Intrinsic materialization is review-only too. `madFmaMaterialization.*`, `clampMaterialization.*`, `stepMaterialization.*`, and `mixMaterialization.*` record whether `optimized.backend.opencl-c` can show `mad(...)`, `clamp(...)`, `step(...)`, and `mix(...)` candidates, including text replacement counts and payload pass counts. `backend.opencl-c` remains the selected source unless future proof, approval, production gate, and mutation policy explicitly allow optimized artifact selection.

Runtime peephole/InstCombine remains diagnostic-only. `GpuRuntimeIrPeepholeReplacementPlan` records read-only typed replacement plans for current rule families: `mad/fma`, `clamp`, `step`, `dot`, and `mix`. Built-in rules now use `GpuRuntimeIrTypedNodeGraph` as the shared read-only typed-node traversal surface, and each emitted plan is checked by `GpuRuntimeIrPeepholeReplacementPlanValidation` before proof fields are exported. `GpuRuntimeIrPeepholeTypedRewriteVisitor` adds a shared traversal preflight above validated plans: it records deterministic visit order, visitor-ready/blocked counts, first visitor blocker, and disabled replacement-builder / transformed-IR / mutation / selected-IR flags. `GpuRuntimeIrPeepholeReplacementBlueprint` adds a read-only target intrinsic-call blueprint above visitor-ready plans: it records target node kind/operation, argument node ids/roles, blueprint-ready/blocked counts, first blueprint blocker, and the same disabled builder / transformed-IR / mutation / selected-IR guardrails. `GpuRuntimeIrPeepholeRewriteTransactionPreflight` adds the graph-transaction shape above blueprint-ready plans: replaced root ids, removed covered ids, retained input ids, planned added-node count, `plannedAddedNodeIds=not-allocated`, and disabled node-id allocation / graph rewrite / transformed-IR / mutation / selected-IR guardrails. `GpuRuntimeIrPeepholeNodeIdAllocationPreflight` adds a diagnostic allocation preview above transaction-ready plans: it records current graph max node id, deterministic candidate node ids, allocation-ready/blocked counts, first blocker, and disabled id reservation / allocator application / graph rewrite / transformed-IR / mutation / selected-IR guardrails. `GpuRuntimeIrPeepholeReplacementNodePreflight` adds the next diagnostic preview: candidate replacement-node id, target intrinsic-call kind/operation, argument ids/roles, ready/blocked counts, first blocker, and disabled replacement-node build / replacement-builder / graph rewrite / transformed-IR / mutation / selected-IR guardrails. `GpuRuntimeIrPeepholeGraphPatchPreflight` adds a diagnostic graph-patch preview: replacement node id, replaced/removed/retained/inserted node sets, ready/blocked counts, first blocker, and disabled patch application / graph rewrite / transformed-IR / mutation / selected-IR guardrails. `GpuRuntimeIrPeepholeTransformedGraphPreflight` adds the transformed-graph materialization preview above that patch: original IR identity, deterministic materialization key, `transformedGraphIdentity=not-built`, ready/blocked counts, first blocker, and disabled transformed-graph build / transformed-IR / patch application / graph rewrite / mutation / selected-IR guardrails. `GpuRuntimeIrPeepholeRewriteSketch` records the mutation-free structural rewrite skeleton around the validated replacement plan: ready sketches prove only that the future rewrite shape is describable, while `rewriteBuilderImplemented`, `mutationAllowed`, and `selectedIrReplacement` remain `false`. `rewriteSelection.*` fields add a read-only selection preflight over those sketches, including sketch/conflict counts, selection status, first blocker, proof/approval requirements, and disabled mutation/selection guardrails. `rewriteProof.*` fields add the matching proof preflight: proof status, first blocker, runtime-equivalence payload presence/completeness, rollback evidence, approval acceptance, original IR identity, absent transformed IR identity, and disabled selected-IR replacement. `rewriteReviewPackage.*` fields add the manual-review package boundary above proof readiness: package required/complete state, conflict count, proof acceptance, runtime-equivalence payload, rollback, approval, manual-review-only state, and disabled mutation / selection / selected-IR replacement. The same visitor/blueprint/transaction/allocation/replacement-node/graph-patch/transformed-graph/selection/proof/package preflight fields are also emitted under `rule.N.*`, making rule-family blockers visible without enabling any rewrite. Proof fields expose the candidate root node, covered nodes, input nodes, completeness, first blocker, structural validation counters, visitor counters, blueprint counters, transaction counters, allocation counters, replacement-node counters, graph-patch counters, transformed-graph counters, sketch counters, selection preflight fields, proof preflight fields, and review-package fields. `runtime-optimizer-drift.properties` aggregates complete/partial replacement-plan counts, plan-validation total/valid/invalid counts, rewrite-visitor ready/blocked counts, replacement-blueprint ready/blocked counts, rewrite-transaction ready/blocked counts, node-id allocation ready/blocked counts, replacement-node preview ready/blocked counts, graph-patch preview ready/blocked counts, transformed-graph materialization ready/blocked counts, rewrite-sketch ready/blocked counts, conflict counts, selection-readiness guardrails, proof/runtime-equivalence/rollback blockers, package-completeness blockers, first incomplete/invalid/visitor/blueprint/transaction/allocation/replacement-node/graph-patch/transformed-graph/sketch/selection/proof/package blockers, and compact `optimizerRule.*` summaries from existing `rule.N.*` proof fields so CI can detect planning drift while the rewrite engine remains absent. Workload gates carry the same visitor/blueprint/transaction/allocation/replacement-node/graph-patch/transformed-graph fields forward as evidence-only telemetry. These plans, visitors, blueprints, transactions, allocation previews, replacement-node previews, graph-patch previews, transformed-graph previews, and sketches describe what a future structural rewrite would need to replace; they do not edit typed bodies, reserve node ids, build replacement nodes, apply graph patches, build transformed graphs, apply allocators, rewrite graphs, select optimized IR, or enable production mutation.

`GpuRuntimeIrPeepholeIrArtifactEnvelopePreflight` adds the next evidence-only boundary above transformed-graph materialization. It records the metadata, proof, and rollback anchors a future optimized IR artifact would require, including deterministic envelope keys and aggregate / `rule.N.irArtifactEnvelope.*` ready or blocked counts. Drift artifacts and workload gates now carry these fields, but `artifactEnvelopeBuilt`, `optimizedArtifactBuilt`, `transformedIrBuilt`, `mutationAllowed`, and `selectedIrReplacement` remain `false`.

`GpuRuntimeIrPeepholeArtifactProofBindingPreflight` adds a proof-binding preflight above that envelope. It records aggregate and `rule.N.artifactProofBinding.*` counts, first blockers, proof status, review-package status, runtime-equivalence payload completeness, rollback evidence, approval state, and disabled proof/rollback/approval binding flags. Drift artifacts and workload gates carry these fields as telemetry only; `proofBound`, `rollbackBound`, `approvalBound`, `optimizedArtifactBuilt`, `mutationAllowed`, and `selectedIrReplacement` remain `false`.

`GpuRuntimeIrPeepholeOptimizedArtifactSelectionPreflight` adds the final selection preflight above proof binding. It records aggregate and `rule.N.artifactSelection.*` counts, first blockers, proof-binding readiness, production-gate requirement/acceptance, mutation-policy allowance, and disabled selection / optimized-artifact-selected / selected-IR replacement flags. Runtime drift and workload gates expose this as evidence only; runtime IR selection remains unchanged.

The same index records `familyBinding.*`. Binding remains `not-bound` unless exactly one optimizer family has passed runtime evidence whose comparison mode is explicitly family-specific (`optimizer-family:<name>:...`). Descriptor-versus-reconstructed-source evidence is pipeline validation and is deliberately rejected as optimizer-family proof.

Run `./gradlew :processor:openClOptimizerFamilyPayloadFixtureTest` to materialize the CI contract fixture under `processor/build/reports/opencl/optimizer-family-payload-fixture/`. The fixture contains complete CSE and auto-vectorization payload trees plus `fixture-summary.properties`, including one structured raw case per family. It validates artifact layout and upload behavior, not production rewrite readiness.

After a successful OpenCL program build, the runtime queries `CL_PROGRAM_BUILD_LOG` through the native program and device handles and stores any returned diagnostics in the compile snapshot. JavaToGpu then writes `backend-compiler-feedback.properties`. The built-in parser recognizes common NVIDIA/ptxas register and spill lines, AMD VGPR/SGPR/scratch fields, Intel/general register and spill fields, stack-frame bytes, local/shared-memory bytes, and occupancy percentages. SGPR and VGPR values remain separate; `effectiveRegisterCount` prefers a general register count, then VGPR, then SGPR, and never adds distinct register files together.

If both the heuristic estimate and a compiler register count exist, `runtime-ir-analysis.properties` adds the compiler provider, parsed metrics, compiler count, heuristic count, delta, and comparison status. This is calibration evidence only: it does not rewrite IR, change device selection, or enable production optimization. OpenCL permits an empty successful build log, and many drivers do not expose resource counts by default; a missing or unrecognized log leaves the heuristic analysis unchanged and marks compiler feedback unavailable. Failed build diagnostics remain available through `GpuRuntimeKernelCompilationException`.

The real-device `openClWorkloadValidationTest` also enables an isolated diagnostic rebuild after the production program has compiled. This second build cannot replace or invalidate the production program. NVIDIA devices use `-cl-nv-verbose` by default. AMD and other vendors remain fail-safe until a reviewed diagnostic option is supplied through `JTG_OPENCL_DIAGNOSTIC_COMPILE_ARGS` or the `javatogpu.opencl.compilerDiagnosticArgs` system property.

When compiler diagnostics are enabled, the runtime also queries `CL_PROGRAM_NUM_DEVICES`, `CL_PROGRAM_DEVICES`, `CL_PROGRAM_BINARY_SIZES`, and `CL_PROGRAM_BINARIES` while the selected program is alive. The diagnostic program binary is preferred; if no diagnostic program was built or its binary is unavailable, the successful production program is used as a fallback. The selected payload is written as `opencl-program.bin` in the kernel's `runtime-compile-artifacts` directory. Metadata includes capture status, source program, device count/index, byte size, SHA-256, coarse format (`ptx`, `elf`, `nvidia-fatbin`, `spir-v`, `llvm-bitcode`, `pe-coff`, or `unknown`), artifact name, and a diagnostic. Binary-query failures remain advisory and never invalidate successful execution.

For NVIDIA binaries, JavaToGpu optionally discovers `ptxas`, `cuobjdump`, and `nvdisasm` from explicit properties/environment variables, `CUDA_PATH`/`CUDA_HOME`, the standard Windows `%ProgramFiles%\\NVIDIA GPU Computing Toolkit\\CUDA\\v*\\bin` installations, or `PATH`. Captured PTX is assembled with `ptxas --verbose`; native cubin/fatbin payloads, or a cubin produced from PTX when further inspection is needed, use `cuobjdump` and then `nvdisasm`. Explicit overrides are `javatogpu.opencl.nvidiaPtxasPath`, `javatogpu.opencl.nvidiaCuobjdumpPath`, `javatogpu.opencl.nvidiaNvdisasmPath`, `JTG_OPENCL_PTXAS`, `JTG_OPENCL_CUOBJDUMP`, and `JTG_OPENCL_NVDISASM`. External inspection is isolated behind a timeout and temporary files. Parsed register, spill, and stack text is normalized for the existing compiler-feedback provider. Missing tools, unsupported binary formats, non-zero exits, and timeouts produce diagnostic statuses only.

`backend-compiler-feedback.properties` exposes `diagnosticCompilation.present`, `status`, `source`, `options`, and `diagnostic`, plus `diagnosticCompilation.binary.*` and `diagnosticCompilation.binaryInspection.*`. Expected build-log states are `recorded`, `completed-empty`, `failed`, and `skipped-no-options`. Binary inspection may additionally report `captured`, `tools-unavailable`, `skipped-non-nvidia`, `inspection-failed`, `timed-out`, or another explicit query/tool failure. These states are evidence about diagnostic availability, not kernel execution failures.

JavaToGpu also queries standard kernel resource information after every successful OpenCL kernel creation. This path does not depend on vendor build-log behavior and records maximum work-group size, preferred work-group multiple, compiler-reported local memory, and compiler-reported private memory. Drivers may still report zero or reject individual queries; each metric remains independent. When the kernel maximum is available, it is enforced for explicit local launch sizes. Preferred multiples and memory values remain advisory.

The standard values appear in `backend-compiler-feedback.properties` as `selected.localMemoryBytes` plus raw fields `privateMemoryBytes`, `maxWorkGroupSize`, and `preferredWorkGroupSizeMultiple`. They provide useful spill/private-memory and launch-shape evidence but are not a replacement for exact SGPR/VGPR or NVIDIA register counts.

When runtime artifact dumping is enabled, each kernel directory also receives `runtime-launch-advisory.properties` for the latest accepted launch. Its status is `aligned`, `non-preferred-multiple`, `driver-selected`, or `unavailable`. The artifact records the requested local shape and total size, kernel maximum, preferred multiple, whether a comparison was performed, and whether it matched. `blocking=false` is invariant: a non-preferred multiple remains a performance diagnostic and never rejects the launch. A launch rejected by the hard kernel maximum clears any older advisory so stale accepted-launch evidence is not retained.

`openClValidationReport` aggregates only the kernels listed by the real-workload promotion gate into the `Kernel Launch Advisories` section. It reports status counts and a per-kernel table, and writes the same compact fragment to `runtime-launch-advisory-summary.md` for CI step summaries.

Validation history stores the aggregate counts plus a versioned encoded per-kernel snapshot in `kernelLaunchAdvisoryStatus`. The snapshot retains resource, status, local shape/size, kernel maximum, preferred multiple, match result, and blocking state. The Markdown history table still displays only the compact aggregate summary.

When a compatible previous entry exists for the same backend, device, vendor, and lane, the reporter adds `Kernel Launch Advisory Drift`. It compares counts and matches per-kernel snapshots by resource even across driver versions. Individual status degradation, blocking activation, preferred-match loss, kernel-maximum reduction, or a missing resource is a regression even when aggregate counts are unchanged. Preferred-multiple and launch-shape changes are reported as non-blocking changes. Baselines created before snapshot support continue through aggregate fallback. The CI Markdown includes the previous driver, signed deltas, and a per-kernel change table when snapshots are available.

The same validation history now stores a versioned per-kernel compiler-resource snapshot from `backend-compiler-feedback.properties`. Each entry retains resource identity, availability, provider, inspection tool, effective register count, spill store/load bytes, and stack-frame bytes. The report adds `Compiler Resource Metrics` and `Compiler Resource Drift`, while `runtime-compiler-resource-drift.properties` provides the machine-readable comparison used by CI.

Compiler-resource drift is fail-closed for meaningful regressions without treating small allocation noise as a failure. Losing a previously available metric, losing a kernel, adding or increasing spill bytes, or adding/increasing a stack frame is a regression. Register growth is a regression only when it exceeds `max(2, 10% of the baseline register count)` for that kernel; smaller changes remain visible as `changed`. Register reductions, spill/stack reductions, and newly available metrics are improvements. A history cache created before compiler snapshots reports `no-baseline` for one successful run and is then upgraded through the normal immutable cache staging flow.

The reporter also writes `runtime-launch-advisory-drift.properties`. `validateOpenClKernelLaunchAdvisoryDrift` accepts `stable`, `changed`, `improved`, `no-baseline`, and `unavailable`, but fails on `regressed`, a missing artifact, or an unsupported status. The vendor workflow captures artifacts and then fails the lane when this validator fails.

GitHub Actions restores an immutable per-lane/per-branch validation-history cache before the workload runs. The reporter seeds the current history from that baseline but always computes drift from the separate baseline file, so repeated report generation inside one workflow cannot hide a cross-run regression. After a successful validation and drift gate, the updated history is staged and saved under a unique run key for the next run; regressed or failed runs do not replace the previous baseline.

Manual `workflow_dispatch` runs can enable `launch_advisory_negative_fixture`. This mode requires a baseline containing per-kernel snapshots, increases one kernel maximum only in the restored baseline, and expects the normal drift validator to reject the resulting current-vs-baseline reduction. History staging and cache save are disabled unconditionally in this mode. The workflow succeeds only when fixture preparation succeeds, the drift step fails, and both cache steps remain skipped.

The same dispatch form exposes `validation_lane=all|nvidia|nvidia-rtx5070|nvidia-rtx3060|amd`. Selecting `nvidia` runs both NVIDIA devices, while a device-specific selector builds only that self-hosted runner entry so an offline unrelated GPU does not leave the validation run queued.

## Runtime Failures And Fallbacks

All structured runtime failures extend `GpuRuntimeException`. Use the base type when every GPU failure should take the same fallback path:

```java
try (GpuRuntimeScope ignored = GpuRuntime.useOpenClSharedCache()) {
    DemoKernel.transform(input, output);
} catch (GpuRuntimeException exception) {
    System.err.println(exception.diagnosticText());
    CpuFallback.transform(input, output);
}
```

Use a specific subtype when recovery differs by phase:

```java
try (GpuRuntimeScope ignored = GpuRuntime.useOpenClSharedCache()) {
    DemoKernel.transform(input, output);
} catch (GpuRuntimeDeviceSelectionException | GpuRuntimeMethodVariantSelectionException exception) {
    CpuFallback.transform(input, output);
} catch (GpuRuntimeKernelCompilationException exception) {
    reportBrokenKernel(exception.context(), exception.getCause());
    throw exception;
}
```

The public hierarchy includes:

- `GpuRuntimeDeviceSelectionException` - no compatible device or active-session mismatch.
- `GpuRuntimeMethodVariantSelectionException` - no compatible fallback method implementation.
- `GpuRuntimeBackendUnavailableException` - native backend/session initialization failed.
- `GpuRuntimeCompileOptionsException` - backend compile arguments are invalid.
- `GpuRuntimeInvocationException` - Java arguments or execution configuration do not match the kernel ABI.
- `GpuRuntimeCapabilityException` - the selected device lacks a required capability or resource budget.
- `GpuRuntimeKernelCompilationException` - driver/backend kernel build failed.
- `GpuRuntimeKernelExecutionException` - binding, enqueue, synchronization, or readback failed.

Every exception provides:

- `code()` - stable identifier such as `JTG-RUNTIME-COMPILE-001`.
- `phase()` - a `GpuRuntimeFailurePhase` value.
- `summary()` - concise failure description.
- `context()` - kernel, resource, backend, device, compile args, optimization profile, GPU method location, and original Java call site.
- `diagnosticText()` - pre-rendered Rust-like diagnostic suitable for logs.
- `getCause()` - the original backend/driver failure when one exists.

The annotation processor records calls to local and dependency-provided `@GPU` methods in `META-INF/javatogpu/call-sites/<binary-class-name>.properties`. At runtime, JavaToGpu matches the active caller stack frame against this index and prefers the exact original Java invocation expression in `diagnosticText()`. The `IrGpu` method source location remains available separately in `context().sourceLocation()`, while `context().callSite()` exposes the caller class/method, file, range, expression, target method, and whether the anchor came from the compiler index or stack-trace fallback.

Method-body rewriting preserves the caller's original exception table. A rewritten GPU call remains inside the same user-authored `try/catch` region, so `catch (GpuRuntimeException exception)` can reliably invoke a CPU, device, method-variant, or backend fallback.

### IrGpu Source Review Lane

`IrGpu` is the backend-neutral artifact that JavaToGpu is moving toward as the runtime source of truth. The normal runtime path still compiles the generated descriptor OpenCL source by default, even when an `IrGpu` artifact is packaged beside it.

Use the explicit review preset when you want to smoke-test reconstructed `IrGpu -> OpenCL` source without enabling production source switching:

```java
GpuRuntimeCompileOptions compileOptions =
        GpuRuntimeCompileOptions.openClIrGpuSourceReview(List.of());

GpuRuntime.invokeWithCompileOptions(
        GpuExecutionConfig.oneDimensional(itemCount),
        compileOptions,
        descriptor,
        input,
        output
);
```

The preset enables the reconstructed-source review mode. Production runs keep using the normal generated OpenCL source unless you explicitly opt into future source-switching features.

### Production Source Acceptance

Production `IrGpu -> OpenCL` source switching requires more than `opencl.productionSourceSwitching=enabled` and a `production-enabled` promotion decision. The operator acceptance must be identity-bound to the exact backend, device vendor and label, driver version, optimization profile, kernel resource, and decision mode.

```java
GpuRuntimeCompileOptions compileOptions =
        GpuRuntimeCompileOptions.openClProductionIrGpuSource(List.of(), "vendor-tuned")
                .withProductionPromotionDecision(promotionDecision);

GpuProductionPromotionOperatorAcceptance acceptance =
        GpuProductionPromotionOperatorAcceptance.forContext(
                "acceptance:rtx5070-reviewed-2026-07-10",
                GpuBackendTarget.OPENCL,
                reviewedDeviceProfile,
                compileOptions.optimizationProfile(),
                descriptor,
                promotionDecision.mode()
        );

compileOptions = compileOptions.withProductionPromotionOperatorAcceptance(acceptance);
```

The legacy `withProductionPromotionOperatorAccepted(true)` flag is retained for compatibility and diagnostics, but it does not authorize the OpenCL production source path without the matching acceptance fields. Acceptance alone is also insufficient: the controlled runtime path requires a matching `GpuProductionActivationToken`. A device, driver, profile, kernel, backend, decision-mode, activation-scope, or token mismatch fails closed.

Hardware validation joins the real-workload promotion evidence with controlled identity-bound acceptance in `backend-source-promotion-candidate-gate.properties`. The candidate gate is `review-ready` only when every workload kernel is review-ready, source-parity matched, runtime-equivalent, present in the controlled source-switching run, operator-accepted, and bound to the recorded device identity.

This artifact is a review boundary, not a runtime enablement switch. A review-ready candidate still records `defaultProductionSourceSwitching=disabled` and `productionMutation=disabled`; normal application execution continues to use the default generated OpenCL source.

### Manual Promotion Manifest

After reviewing a candidate artifact, generate a pending manifest template bound to its exact bytes, Git commit, device identity, driver, and kernel resources:

```powershell
.\gradlew.bat :processor:writeOpenClBackendSourcePromotionManifestTemplate `
  -PopenClPromotionGitSha=<full-git-sha> `
  --console=plain --no-daemon
```

The template is written to `processor/build/reports/opencl/backend-source-promotion-manifest-template.properties`. Change `status` to `approved` and fill `approval.id`, `approval.approvedBy`, and `approval.approvedAtUtc`; do not alter any `binding.*` or `authorization.*` fields.

Validate the reviewed manifest with:

```powershell
.\gradlew.bat :processor:validateOpenClBackendSourcePromotionManifest `
  -PopenClPromotionManifestFile=<manifest-path> `
  -PopenClPromotionGitSha=<candidate-run-full-git-sha> `
  --console=plain --no-daemon
```

The candidate Git SHA is the `binding.gitSha` written by the template run, not the later commit that adds the approved manifest to the repository. Validation writes `backend-source-promotion-manifest-validation.properties` before failing closed on any mismatch. The candidate-artifact SHA-256 prevents the later validation commit from changing source-promotion evidence while retaining the original reviewed SHA binding.

An approved manifest remains device- and driver-specific and records `manual-review-only`, `defaultProductionSourceSwitching=disabled`, and `productionMutation=disabled`. It is auditable approval evidence for a later activation design, not runtime authorization by itself.

### Controlled Activation Gate

The next operational boundary combines the approved manifest validation, the production candidate gate, and controlled identity-bound source-switching evidence:

```powershell
.\gradlew.bat :processor:validateOpenClBackendSourcePromotionActivationGate `
  -PopenClPromotionManifestFile=<manifest-path> `
  -PopenClPromotionGitSha=<candidate-run-full-git-sha> `
  --console=plain --no-daemon
```

The task writes `backend-source-promotion-activation-gate.properties` and `backend-source-promotion-activation-gate.properties.sha256`. A successful result is `controlled-activation-ready` with full kernel coverage and accepted/bound operator evidence. It explicitly records `activationScope=controlled-opt-in-only`, `defaultRuntimeActivation=false`, `defaultProductionSourceSwitching=disabled`, and `productionMutation=disabled`. New tooling should prefer the portable `runtime.production.activationGate.*` mirrors for gate status, scope, backend, default-disabled switches, approval identity, device identity, kernel coverage, operator acceptance, blockers, and diagnostics; the unprefixed fields remain compatibility keys.

This gate does not modify `GpuRuntimeCompileOptions`, does not enable the default source path, and is not consumed automatically by application runtime code. A controlled caller must load the exact artifact and expected digest, then attach the resulting token alongside the identity-bound operator acceptance:

```java
Path activationArtifact = Path.of(
        "processor/build/reports/opencl/backend-source-promotion-activation-gate.properties"
);
String expectedSha256 = Files.readString(
        activationArtifact.resolveSibling(activationArtifact.getFileName() + ".sha256")
).trim();

GpuProductionActivationToken activationToken =
        GpuProductionActivationToken.fromArtifact(activationArtifact, expectedSha256);

compileOptions = compileOptions
        .withProductionPromotionOperatorAcceptance(acceptance)
        .withProductionActivationToken(activationToken);
```

The OpenCL production lowerer validates the token against the runtime backend, device vendor/label, driver, activation scope, and kernel resource. Any mismatch rejects the production source path while normal application execution remains on the default generated OpenCL source.

Run the end-to-end hardware check with the same manifest and candidate SHA used for activation:

```powershell
.\gradlew.bat :processor:openClProductionActivationTokenSmokeTest `
  -PopenClPromotionManifestFile=<manifest-path> `
  -PopenClPromotionGitSha=<candidate-run-full-git-sha> `
  --console=plain --no-daemon
```

The task depends on the activation gate, loads its exact artifact and sidecar, and executes every approved real workload kernel: Perlin, packed blob, packed numeric, synthetic 3D packed grid, and image. It writes per-kernel status to `production-activation-token-smoke.properties` and still records all default runtime and production mutation switches as disabled.

Run the hardware negative controls against the same activation artifact:

```powershell
.\gradlew.bat :processor:openClProductionActivationTokenNegativeTest `
  -PopenClPromotionManifestFile=<manifest-path> `
  -PopenClPromotionGitSha=<candidate-run-full-git-sha> `
  --console=plain --no-daemon
```

This task verifies that `GpuProductionActivationToken.fromArtifact(...)` rejects a mismatched SHA-256 and that a valid token rejects an unapproved kernel resource before GPU output changes. It writes `production-activation-token-negative.properties` with the rejection and safe-default states.

The OpenCL validation reporter includes controlled source-switching, mutation-readiness, and activation-token artifacts in `production-promotion-explainability.properties` and its compact CI summary. New tooling should read `runtime.production.sourceSwitching.controlled.*`, `runtime.production.mutation.controlled.*`, `runtime.production.activationToken.smoke.*`, and `runtime.production.activationToken.negative.*`; older `controlledProductionSourceSwitching.*`, `controlledProductionMutation.*`, `controlledProductionActivationTokenSmoke.*`, and `controlledProductionActivationTokenNegative.*` keys remain compatibility mirrors. Separate readiness items require full real-workload coverage and successful negative controls. Neither item sets `productionSourceSwitchingAllowed`, enables the default source path, or authorizes production mutation.

## ABI Debug

Enable ABI diagnostics with:

```java
System.setProperty("javatogpu.opencl.debugAbi", "true");
```

Use ABI debug when diagnosing struct layout, vector array, image, or readback issues.

## Related Documents

- [Validation and Operations](Validation-and-Operations.md)
- [OpenCL Data Model](OpenCL-Data-Model.md)
- [Troubleshooting](Troubleshooting.md)

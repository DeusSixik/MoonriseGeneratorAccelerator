# Public API And Extension Contract

This page explains the parts of JavaToGpu that are meant to be used, configured, or extended by applications and third-party modules.

It is intentionally practical: start with the quick-start tables, copy the small examples, and only use the deeper contracts when you are writing an extension or production gate.

JavaToGpu has one public Java GPU dialect: the `net.sixik.ga_utils.javatogpu.api.GPU` facade. It uses OpenCL-style names today because OpenCL is the first backend, but user code should stay backend-neutral. Future CUDA, Vulkan/SPIR-V, and Metal support should lower the same Java source dialect instead of introducing separate CUDA-style or Vulkan-style Java APIs.

## Read This If

| You are... | Use this page for | Better first page |
| --- | --- | --- |
| Application user | Logging, lifecycle, fallback, and generated-launcher boundaries | [Getting Started](Getting-Started.md) |
| Extension author | ServiceLoader interfaces, permissions, and harnesses | [Cookbook](Cookbook.md) for simple usage first |
| Backend author | Provider stages, execution receipts, hook contracts | [Backend Adapter Authoring](Backend-Adapter-Authoring.md) |
| Maintainer | Compatibility rules and production safety vocabulary | [Validation and Operations](Validation-and-Operations.md) |

If you only want to run kernels, you do not need most of this page. Use `JavaToGpu`, generated launchers, and the Cookbook first.

## Runtime Package Boundaries

Use the smallest layer that solves the problem:

| Audience | Prefer | Avoid by default |
| --- | --- | --- |
| Normal application code | `api.JavaToGpu`, `api.GpuScope`, `api.GPU`, `api.annotations`, grouped `api.types` / `api.pointers` / `api.images` | Browsing root `runtime` classes first |
| Advanced runtime configuration | `runtime.selection`, `runtime.launch`, selected root compatibility types such as `GpuRuntimeCompileOptions` | Backend implementation packages |
| Extension authors | `runtime.spi`, `runtime.hooks`, `runtime.observability`, `runtime.optimization`, `runtime.memory` | Direct callbacks or global mutable registries |
| Maintainers and CI | `runtime.validation` harnesses and contract validators | Application-facing docs as a test harness |
| Backend implementors | `runtime.opencl`, `runtime.cuda`, backend provider SPI | Adding backend-specific helpers directly to root `runtime` |

The root `net.sixik.ga_utils.javatogpu.runtime` package remains supported for compatibility, generated launchers, and public SPI/value contracts. New code should prefer the domain packages above unless a root compatibility facade is deliberate.

## ServiceLoader Extension Points

Register extensions by creating `META-INF/services/<service-type>` and putting one implementation class name per line. The source-of-truth catalog in code is `GpuRuntimeExtensionPointCatalog`; this table mirrors the public entries from that catalog.

| Need | Service type | Domain | Permission model | Hardware-free check |
| --- | --- | --- | --- | --- |
| Runtime logs | `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLogService` | `runtime.observability` | Read-only | `GpuRuntimeObservabilityServiceHarness` |
| Lifecycle tracing | `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLifecycleService` | `runtime.observability` | Read-only | `GpuRuntimeObservabilityServiceHarness` |
| Low-level lifecycle events | `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLifecycleEventListener` | `runtime.observability` | Read-only | `GpuRuntimeObservabilityServiceHarness` |
| IR validation | `net.sixik.ga_utils.javatogpu.frontend.ir.validation.GpuIrValidationProvider` | `frontend.ir.validation` | Read-only | `GpuIrValidationProviderHarness` |
| IR optimizer pass | `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeIrOptimizationPass` | `runtime.optimization` | Mutation proposal | Optimizer report/artifact gates |
| IR peephole rule | `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeIrPeepholeRule` | `runtime.optimization` | Mutation proposal | Peephole report/artifact gates |
| Legacy IR optimizer | `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeIrOptimizer` | `runtime.optimization` | Legacy mutation proposal | Optimizer report/artifact gates |
| Compiler feedback parser | `net.sixik.ga_utils.javatogpu.runtime.GpuBackendCompilerFeedbackProvider` | `runtime.diagnostics` | Read-only | `GpuBackendCompilerFeedbackHarness` |
| Backend provider | `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeBackendProvider` | `runtime.spi` | Stage-specific | `GpuRuntimeBackendProviderCatalog` |
| Native memory provider | `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeNativeMemoryService` | `runtime.memory` | Production-affecting | `GpuRuntimeNativeMemoryServiceRegistry` |
| Device policy | `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeDevicePolicy` | `runtime.selection` | Read-only with hard rejections | `GpuRuntimeDevicePolicyHarness` |
| Generic backend hook | `net.sixik.ga_utils.javatogpu.runtime.GpuBackendHook` | `runtime.hooks` | Read-only by default | `GpuBackendHookTestHarness` |
| Backend policy facts | `net.sixik.ga_utils.javatogpu.runtime.GpuBackendPolicyContributor` | `runtime.hooks` | Read-only | `GpuBackendHookTestHarness` |
| Backend score contribution | `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeBackendScoreContributor` | `runtime.hooks` | Read-only | `GpuBackendHookTestHarness` |
| Discovery facts | `net.sixik.ga_utils.javatogpu.runtime.GpuBackendDiscoveryContributor` | `runtime.hooks` | Read-only | `GpuBackendHookTestHarness` |
| Lowering observation | `net.sixik.ga_utils.javatogpu.runtime.GpuBackendLoweringHook` | `runtime.hooks` | Read-only observer unless authorized | `GpuBackendHookTestHarness` |
| Compilation observation | `net.sixik.ga_utils.javatogpu.runtime.GpuBackendCompilationHook` | `runtime.hooks` | Read-only observer unless authorized | `GpuBackendHookTestHarness` |
| Invocation observation | `net.sixik.ga_utils.javatogpu.runtime.GpuBackendInvocationHook` | `runtime.hooks` | Read-only observer unless authorized | `GpuBackendHookTestHarness` |
| Artifact fields | `net.sixik.ga_utils.javatogpu.runtime.GpuBackendArtifactHook` | `runtime.hooks` | Read-only | `GpuBackendHookTestHarness` |
| Method fallback variants | `net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeMethodVariantProvider` | `runtime.variants` | Generated or advanced provider | `GpuRuntimeMethodVariantRegistry` |

`net.sixik.ga_utils.javatogpu.frontend.ir.passes.GpuIrPass` is also loaded through ServiceLoader by the compiler frontend, but it is treated as compiler-internal and is not a normal application extension API.

## Quick Start

| I want to... | Use this | Start here |
| --- | --- | --- |
| Print runtime lifecycle logs locally | Built-in `GpuRuntimeLogService` console sink | `-Djavatogpu.runtime.log=system-out` |
| Route logs to Log4J, SLF4J, or another logger | `GpuRuntimeLogService` | Implement a ServiceLoader service |
| Observe compile/runtime stages | `GpuRuntimeLifecycleService` | Implement a read-only lifecycle service |
| Check lifecycle/log services without a GPU | `GpuRuntimeObservabilityServiceHarness` | Run a synthetic ServiceLoader check |
| Add a company IR validator | `GpuIrValidationProvider` | Emit read-only validation reports |
| Check IR validators without javac/GPU | `GpuIrValidationProviderHarness` | Run synthetic IR methods |
| Add an optimizer pass | `GpuRuntimeIrOptimizationPass` or `GpuRuntimeIrPeepholeRule` | Produce optimizer evidence first |
| Parse backend compiler resource logs | `GpuBackendCompilerFeedbackProvider` | Keep parsed metrics advisory |
| Check compiler-feedback parsers without a compiler | `GpuBackendCompilerFeedbackHarness` | Run synthetic compiler logs |
| Add or preview a backend family | `GpuRuntimeBackendProvider` | Start discovery-only, then add execution stages |
| Check built-in backend contracts | `validateBackendAdapterContracts` | Run all metadata-only backend adapter gates |
| Check built-in OpenCL backend SPI | `validateOpenClBackendSpiContract` | Verify provider/factory metadata without OpenCL |
| Check CUDA inventory contract | `validateCudaInventoryContract` | Verify CUDA provider/adapter metadata without `nvidia-smi` |
| Check CUDA execution gate | `validateCudaExecutionReadiness` | Verify CUDA skeleton exists and native execution still fails closed |
| Check CUDA launch contract | `validateCudaLaunchContract` | Verify launch-shape guardrails without opening CUDA |
| Check CUDA image/sampler contract | `validateCudaImageSamplerContract` | Verify image/sampler parameters fail closed and expose planned runtime slot preflight |
| Check CUDA image/sampler ABI plan | `validateCudaImageSamplerAbiPlan` | Verify the planned texture/surface/sampler mapping without enabling runtime binding |
| Check CUDA image/sampler object boundary | `validateCudaImageSamplerObjectCreationContract` | Verify planned texture/surface symbols without creating CUDA objects |
| Check CUDA image/sampler descriptor boundary | `validateCudaImageSamplerDescriptorContract` | Verify planned resource/texture descriptors without native descriptor allocation |
| Check CUDA image/sampler native layout | `validateCudaImageSamplerNativeDescriptorLayout` | Verify planned native descriptor layout fields without native memory allocation |
| Check CUDA image/sampler descriptor build plan | `validateCudaImageSamplerDescriptorBuildPlan` | Verify Java wrapper handle/metadata preflight without native descriptor payloads |
| Check CUDA image/sampler payload model | `validateCudaImageSamplerDescriptorPayloadModel` | Verify Java-only descriptor payload objects without native descriptor allocation |
| Check CUDA image/sampler encoding plan | `validateCudaImageSamplerNativeDescriptorEncodingPlan` | Verify planned native descriptor field writes without writing native memory |
| Check CUDA image/sampler descriptor allocation preflight | `validateCudaImageSamplerNativeDescriptorAllocationPreflight` | Verify planned native descriptor allocation/ownership without allocating native memory |
| Check CUDA image/sampler descriptor allocation transaction plan | `validateCudaImageSamplerNativeDescriptorAllocationTransactionPlan` | Verify planned descriptor owners and cleanup/rollback order without applying native allocation |
| Check opt-in CUDA native descriptor allocation | `validateCudaImageSamplerNativeDescriptorAllocationResult` | Verify ServiceLoader-friendly native host memory allocation and cleanup without object creation |
| Check CUDA image/sampler descriptor encoding transaction plan | `validateCudaImageSamplerNativeDescriptorEncodingTransactionPlan` | Verify planned descriptor field writes are mapped to owner slots without writing native memory |
| Check CUDA image/sampler object request plan | `validateCudaImageSamplerObjectCreationRequestPlan` | Verify planned texture/surface object requests without calling Driver API object creation |
| Check CUDA image/sampler native object preparation | `validateCudaImageSamplerNativeObjectPreparationPreflight` | Verify native descriptor/object-handle prerequisites stay blocked before Driver API object creation |
| Check CUDA image/sampler runtime object binding plan | `validateCudaImageSamplerRuntimeObjectBindingPlan` | Verify planned texture/surface object kernel slots without binding object handles |
| Check CUDA image/sampler runtime object binding preflight | `validateCudaImageSamplerRuntimeObjectBindingTransactionPreflight` | Verify native/object/kernel-write prerequisites stay blocked before real object binding |
| Check CUDA image/sampler fail-closed contract | `validateCudaImageSamplerFailClosedContract` | Verify every staged image/sampler gate is ready while native mutation remains zero |
| Add backend-stage facts or hooks | `GpuBackendHook` family | Keep defaults read-only and fail-soft |
| Influence backend/device selection | `GpuRuntimeDevicePolicy` | Add evidence or hard rejections |
| Check device policy behavior without a GPU | `GpuRuntimeDevicePolicyHarness` | Run synthetic CPU/iGPU/dGPU candidates |
| Smoke-test all hardware-free SPI examples | `runExtensionHarnessExamples` | Run one Gradle task before native runtime tests |
| Add device-specific method variants | `@GPUFallbackVariant` | Keep the Java launch ABI identical |

For normal application code, you usually only need annotations, runtime options, and optional logging. Most SPI hooks are for libraries, tooling, CI, or backend integrations.

To run the full hardware-free SPI smoke suite from the examples app:

```powershell
.\gradlew.bat :examples-app:runExtensionHarnessExamples --console=plain
```

That task runs the backend hook, authorization, IR-validation, lifecycle/log, device-policy, and compiler-feedback harness examples. It is intended as a fast classpath/API check before running real OpenCL/CUDA validation.

## Pick The Right Hook

| Need | Extension point | Permission model | Notes |
| --- | --- | --- | --- |
| Human logs | `GpuRuntimeLogService` | Read-only | Use for Log4J/SLF4J/System.out adapters. |
| Tracing and metrics | `GpuRuntimeLifecycleService` | Read-only | Observes immutable lifecycle events. |
| Extra IR checks | `GpuIrValidationProvider` | Read-only | Should report diagnostics, not mutate IR. |
| IR optimization proposal | `GpuRuntimeIrOptimizationPass` | Mutation proposal | Cannot bypass proof, equivalence, or production gates. |
| One peephole pattern | `GpuRuntimeIrPeepholeRule` | Mutation proposal | Runs inside the built-in peephole host. |
| Compiler log parser | `GpuBackendCompilerFeedbackProvider` | Read-only | Resource metrics are advisory. |
| Backend family registration | `GpuRuntimeBackendProvider` | Stage-specific | Discovery can be read-only; compile/invoke becomes production-affecting. |
| Native host-memory allocation | `GpuRuntimeNativeMemoryService` | Production-affecting | Default is LWJGL; future Panama providers can return the same address + `ByteBuffer` view contract. |
| Backend policy/discovery/stage hooks | `GpuBackendPolicyContributor`, `GpuRuntimeBackendScoreContributor`, `GpuBackendDiscoveryContributor`, `GpuBackendLoweringHook`, `GpuBackendCompilationHook`, `GpuBackendInvocationHook`, `GpuBackendArtifactHook` | Read-only by default | Observer/enricher contracts for backend receipts; production-affecting hooks must opt into stronger permission explicitly. |
| Device ranking facts | `GpuRuntimeDevicePolicy` | Read-only | Rejections are hard; score changes are evidence. |
| Backend source generation | `GpuBackendLowerer` | Production-affecting | Must stay auditable and fail closed. |

If you are unsure, prefer the most restricted hook. Read-only services are easier to test, safer to ship, and less likely to be blocked by production rules.

## ServiceLoader Pattern

Runtime extensions should be installed as services, not by ad-hoc application callbacks like `Journal.listenerAdd(...)`.

1. Implement the service interface.
2. Add a file under `src/main/resources/META-INF/services/`.
3. Put the implementation class name in that file.
4. Put the module or jar on the runtime classpath.

Example service file for runtime logging:

```text
src/main/resources/META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLogService
```

File contents:

```text
com.example.gpu.Log4jGpuRuntimeLogService
```

Every public extension also exposes common metadata through `GpuExtension`: `extensionId()`, `extensionVersion()`, `extensionCapabilities()`, `extensionPhase()`, `extensionPermission()`, and `extensionOrder()`. Use stable ids and versions because artifacts and diagnostics record them.

Native-memory providers use the same ServiceLoader deployment style, but through `GpuRuntimeNativeMemoryService`. The
runtime chooses providers deterministically by `serviceOrder()`, `serviceId()`, and `serviceVersion()`. A provider must
return a closeable `GpuRuntimeNativeMemoryAllocation` with a native address and a `ByteBuffer` view; this keeps current
LWJGL code working while leaving room for a future Panama implementation that exposes a `MemorySegment` as a buffer view.
The built-in LWJGL provider is under `runtime.memory`; extension authors should still implement the root
`GpuRuntimeNativeMemoryService` SPI for compatibility.

## Runtime Logging

For local diagnostics, enable the built-in console sink:

```powershell
./gradlew.bat :examples-app:runMethodTestProbeExample --console=plain "-Pjavatogpu.runtimeLog=system-out"
```

For application launches, the runtime property is:

```text
-Djavatogpu.runtime.log=system-out
```

Use `system-err` if you want logs on stderr.
The built-in console and lifecycle-journal implementations are now under `runtime.observability`; root classes with the
same names are compatibility facades.

For a real application logger, implement `GpuRuntimeLogService`:

```java
package com.example.gpu;

import net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLogRecord;
import net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLogService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Log4jGpuRuntimeLogService implements GpuRuntimeLogService {
    private static final Logger LOGGER = LogManager.getLogger("JavaToGpu");

    @Override
    public void log(GpuRuntimeLogRecord record) {
        String message = record.fields().isEmpty()
                ? record.message()
                : record.message() + " " + record.fields();

        switch (record.level()) {
            case TRACE -> LOGGER.trace(message, record.throwable());
            case DEBUG -> LOGGER.debug(message, record.throwable());
            case INFO -> LOGGER.info(message, record.throwable());
            case WARN -> LOGGER.warn(message, record.throwable());
            case ERROR -> LOGGER.error(message, record.throwable());
        }
    }

    @Override
    public String extensionId() {
        return "example.log4j-runtime-log";
    }
}
```

Register it in:

```text
META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLogService
```

The core runtime emits immutable `GpuRuntimeLogRecord` values and does not depend on Log4J, SLF4J, java.util.logging, or any concrete logging framework.

Before wiring logging into a real kernel run, check that ServiceLoader can discover and call the service:

```java
import net.sixik.ga_utils.javatogpu.runtime.validation.GpuRuntimeObservabilityServiceHarness;
import net.sixik.ga_utils.javatogpu.runtime.validation.GpuRuntimeObservabilityServiceHarnessReport;

GpuRuntimeObservabilityServiceHarnessReport report = GpuRuntimeObservabilityServiceHarness
        .loadFromServiceLoader()
        .runSyntheticOpenCl();

System.out.println(report.toMarkdown());
```

The examples app includes a no-op-unless-configured `ExampleRuntimeLogTraceService`. Set
`-Djavatogpu.examples.runtimeLogTraceFile=...` if you want that example service to write human-readable log lines.

## Runtime Lifecycle

Use `GpuRuntimeLifecycleService` when you want a journal, metrics, tracing, or test instrumentation around runtime stages.
For normal generated-launcher calls with `withStandardBackendDevicePreflight()`, lifecycle services can already observe
facade preflight start/completion, backend selection, device discovery, backend compilation, invocation, artifact dumps,
and shutdown where the active backend emits them.
For lifecycle tooling, prefer stable `runtime.*` fields such as `runtime.kernel.name`, `runtime.backend.target`,
`runtime.device.label`, `runtime.irgpu.present`, `runtime.module.format`, `runtime.ir.selectedStage`,
`runtime.ir.fallbackDecision`, `runtime.fallback.decision`, `runtime.work.globalShape`, and `runtime.status`;
backend-specific legacy fields may exist, but they are not the portable trace contract.
Compile, invocation, and shutdown events also expose portable backend state counters such as
`runtime.backend.cache.mode`, `runtime.backend.cache.compiledKernel.count`,
`runtime.backend.cache.compileHit.count`, `runtime.backend.compile.count`, and
`runtime.backend.invocation.count`; `runtime.backend.state.present` marks events that carry this typed runtime-state
summary. Backend-compilation events expose `runtime.compilation.*` summaries for cache-key presence, module
presence/format, compile-log presence, binary-artifact count, and validation-evidence count.
Invocation events additionally expose backend-neutral binding summaries under
`runtime.invocation.binding.*`, including `runtime.invocation.binding.buffer.count`,
`runtime.invocation.binding.local.count`, `runtime.invocation.binding.scalar.count`, and
`runtime.invocation.binding.argument.count`; OpenCL keeps older `bufferBinding.count` / `argumentBinding.count`
aliases only for compatibility. Artifact-dump events expose `runtime.artifactDump.*` summaries for planned output
directories and completed text/binary/source-location artifact counts.
Backend source-selection events expose portable `runtime.backend.source.*` fields such as
`runtime.backend.source.status`, `runtime.backend.source.decision`, `runtime.backend.source.selection`,
`runtime.backend.source.available`, `runtime.backend.source.promotionFirstBlocker`, and
`runtime.backend.source.runtimeLoadMode`. Use these fields to understand why a backend compiled descriptor source,
selected reconstructed `IrGpu` source, or failed closed before native compilation.
Workload source-promotion artifacts mirror the same vocabulary under `kernel.N.runtime.backend.source.*`, while legacy
`kernel.N.sourceSwitching.*` fields remain available for compatibility. Report and history readers prefer the portable
fields first, so new backend adapters can emit `kernel.N.runtime.backend.source.*` without copying OpenCL-specific
`sourceSwitching.*` keys.
Workload-level source-decision aggregates should use indexed `runtime.backend.source.decision.*` fields; compact CI
summaries expose the same aggregate as `runtime.backend.source.decisions` while retaining `sourceSwitching.decisions`
for older consumers. Compact summaries also expose `runtime.backend.source.promotionFirstBlockers` and
`runtime.backend.source.promotionFirstBlockerFamilies` alongside the unprefixed compatibility summaries.
Workload-level blocker aggregates should use `runtime.backend.source.promotionFirstBlocker.*` and
`runtime.backend.source.promotionFirstBlockerFamily.*`; legacy `sourceSwitching.sourcePromotionFirstBlocker.*` and
`sourceSwitching.sourcePromotionFirstBlockerFamily.*` keys are compatibility mirrors for older OpenCL tooling.
Aggregate production source-readiness evidence follows the same rule: emit
`runtime.backend.source.productionSwitchingEnabled.*`,
`runtime.backend.source.productionPromotionDecisionMode.productionEnabled.*`,
`runtime.backend.source.productionPromotionOperatorAccepted.*`, and
`runtime.backend.source.productionDecision.*` for workload/explainability artifacts. Keep
`productionSourceSwitchingEnabled.*`, `productionPromotionDecisionMode.productionEnabled.*`,
`productionPromotionOperatorAccepted.*`, and `sourceSwitching.productionDecision.*` only as compatibility mirrors, and
read the portable fields first when both are present.
Runtime IR production-mutation evidence uses `runtime.ir.productionMutation.*`. The most useful fields are
`runtime.ir.productionMutation.enabled`, `runtime.ir.productionMutation.status`,
`runtime.ir.productionMutation.productionGateStatus`, `runtime.ir.productionMutation.selectedStage`,
`runtime.ir.productionMutation.optimizedSelected`, `runtime.ir.productionMutation.optimizedDiffersFromOriginal`,
`runtime.ir.productionMutation.optimizedIrRejected`, `runtime.ir.productionMutation.fallbackDecision`, and
`runtime.ir.productionMutation.diagnostic`. New reports should read those fields before falling back to the older
`runtimeProductionMutationSafety.*` keys.
Controlled production validation evidence uses `runtime.production.*`. Source-switching readiness lives under
`runtime.production.sourceSwitching.controlled.*`, mutation-readiness lives under
`runtime.production.mutation.controlled.*`, activation-token smoke lives under
`runtime.production.activationToken.smoke.*`, and negative controls live under
`runtime.production.activationToken.negative.*`. Older `controlledProductionSourceSwitching.*`,
`controlledProductionMutation.*`, `controlledProductionActivationTokenSmoke.*`, and
`controlledProductionActivationTokenNegative.*` keys are compatibility mirrors.
Controlled activation-gate artifacts use `runtime.production.activationGate.*` for the gate status, readiness, scope,
safe default switches, approval/device identity, kernel coverage, operator acceptance, blockers, and diagnostics. The
runtime token loader reads those portable fields first and falls back to the older unprefixed activation-gate fields.
Production-candidate and manual-approval artifacts follow the same rule with `runtime.production.candidateGate.*` and
`runtime.production.manifest.*`; manifest, activation-gate, and validation-report readers prefer those portable fields
before falling back to legacy unprefixed, `binding.*`, or `authorization.*` keys.
New runtime artifact readers and writers should use `GpuRuntimeArtifactProperties` for portable-first lookup,
`StringBuilder` property writing, `Properties` artifacts, and map field writing instead of hand-rolling
backend-specific fallback order.
Automatic backend/device preflight events use that same vocabulary and add `runtime.backendDevicePreflight.*` plus
`runtime.failure.*` when the scoped preflight backend fails. Failure fields keep the legacy `type` / `message` keys and
also expose backend-neutral `code`, `phase`, `category`, `summary`, `catchable`, `cause.*`, `suppressed.count`, optional
`help.*`, and `context.*` facts when the failure is a `GpuRuntimeException`.
Selection/discovery events also expose portable fields such as `runtime.selection.status`,
`runtime.backend.selection.matched`, `runtime.device.discovery.available`, and selected `runtime.device.*` facts.
CUDA device discovery additionally exposes `runtime.device.cuda.runtimeVersion` and
`runtime.device.cuda.computeCapability` when those facts are available from `nvidia-smi`.
Backend adapter artifact maps use the same convention with `runtime.backend.adapter.*` and
`runtime.backend.lowerer.*`, allowing tools to inspect OpenCL, the CUDA source-preview/skeleton adapter, and planned
adapters uniformly.
Backend selection, device discovery, and combined runtime-selection artifact maps carry those portable fields beside
their older prefixed compatibility keys.
Backend compile, invocation, runtime-state, and artifact-dump lifecycle events should be composed through
`GpuRuntimeLifecycleFields` so adapters share the same `runtime.cache.*`, `runtime.module.*`, `runtime.work.*`,
`runtime.compilation.*`, `runtime.invocation.binding.*`, `runtime.artifactDump.*`, `runtime.backend.state.*`,
`runtime.backend.*`, and `runtime.failure.*` contract while keeping any backend-specific compatibility aliases separate.
Lifecycle event reports preserve indexed event fields and additionally copy `runtime.*` fields to direct
`runtimeLifecycle.event.runtime.*` properties for simpler journal consumers.

```java
package com.example.gpu;

import net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLifecycleEvent;
import net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLifecycleService;

public final class TraceLifecycleService implements GpuRuntimeLifecycleService {
    @Override
    public void onRuntimeLifecycleEvent(GpuRuntimeLifecycleEvent event) {
        System.out.println(event.kind() + " " + event.message() + " " + event.fields());
    }

    @Override
    public String extensionId() {
        return "example.lifecycle-trace";
    }
}
```

Register it in:

```text
META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLifecycleService
```

Lifecycle services are observers. They should not mutate runtime state, depend on execution order for correctness, or throw exceptions as control flow. The event bus isolates listener failures so diagnostics do not break ordinary runtime execution.

To check lifecycle and logging services together without opening OpenCL/CUDA, run the observability harness:

```powershell
.\gradlew.bat :examples-app:runRuntimeObservabilityServiceHarnessExample --console=plain
```

The harness publishes one synthetic lifecycle event and one synthetic log record, then reports listener/service counts,
success flags, artifact fields, and Markdown. Use it in extension-module tests when you need to verify ServiceLoader
registration, ordering, and failure isolation before touching a native runtime.

## Backend Provider SPI

Use `GpuRuntimeBackendProvider` when a module wants to register a backend family such as CUDA, Vulkan/SPIR-V, Metal, or
a company-specific runtime adapter. A provider is intentionally lightweight: it reports a stable provider id/version,
backend target, deterministic order, and creates a `GpuRuntimeBackendAdapter` without opening native sessions during
catalog inspection.

If you are building a real backend module, start with the shorter [Backend Adapter Authoring](Backend-Adapter-Authoring.md)
guide, then return here for exact SPI type details.

Register it with ServiceLoader:

```text
META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeBackendProvider
```

Minimal discovery-only provider shape:

```java
package com.example.gpu;

import net.sixik.ga_utils.javatogpu.api.GpuBackendTarget;
import net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeBackendAdapter;
import net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeBackendProvider;

public final class ExampleBackendProvider implements GpuRuntimeBackendProvider {
    @Override
    public GpuBackendTarget backendTarget() {
        return GpuBackendTarget.CUDA;
    }

    @Override
    public String providerId() {
        return "example.cuda-provider";
    }

    @Override
    public String providerVersion() {
        return "1";
    }

    @Override
    public int providerOrder() {
        return 1_000;
    }

    @Override
    public GpuRuntimeBackendAdapter createAdapter() {
        return new ExampleCudaDiscoveryOnlyAdapter();
    }
}
```

For a runnable checklist of the full provider path, use:

```powershell
.\gradlew.bat :examples-app:runBackendProviderAuthoringExample --console=plain
```

It prints three local providers that model the expected progression: discovery-only, lowering-only, and production
pipeline with a shared runner factory.

To inspect what an application will actually see after built-ins and ServiceLoader providers are merged, use the
provider catalog:

```java
import net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeBackendProviderCatalog;

String report = GpuRuntimeBackendProviderCatalog
        .standardWithPlannedBackends()
        .toMarkdown();

System.out.println(report);
```

The catalog does not create native runtime sessions. It validates provider ids/orders, keeps deterministic ordering,
reports execution availability for each provider, and exposes artifact fields for CI or diagnostics.

Provider ids must be unique. Built-in OpenCL, CUDA inventory, Vulkan placeholder, and Metal placeholder providers use
the same registry path as external providers, and duplicate ids fail fast with a clear diagnostic.

Every provider should also be honest about execution readiness through `executionSupport()`:

| Provider state | What to return | Meaning |
| --- | --- | --- |
| Discovery-only | `GpuRuntimeBackendExecutionSupport.discoveryOnly(...)` | The backend can appear in catalogs/device reports, but must not execute kernels. |
| Lowering-only | `GpuRuntimeBackendExecutionSupport.loweringOnly(...)` | The backend can produce native source/artifacts, but compile/invoke is not ready. |
| Production pipeline | `GpuRuntimeBackendExecutionSupport.productionPipeline(...)` | The backend can compile, prepare, invoke, read back, and clean up through the shared stage vocabulary. |

The same support card should declare the backend-family module formats and capability vocabulary it can report or consume.
That metadata is safe to inspect without opening native sessions:

```java
return GpuRuntimeBackendExecutionSupport.discoveryOnly(
        GpuBackendTarget.CUDA,
        "example.cuda-provider",
        Set.of(GpuBackendModuleFormat.CUDA_C, GpuBackendModuleFormat.PTX),
        Set.of(
                GpuRuntimeCapability.DRIVER_VERSION,
                GpuRuntimeCapability.RUNTIME_VERSION,
                GpuRuntimeCapability.COMPUTE_CAPABILITY,
                GpuRuntimeCapability.GLOBAL_MEMORY
        ),
        "CUDA discovery is available; this custom provider has no execution pipeline yet"
);
```

This still does not mean every device supports every optional feature. Device-specific truth stays on
`GpuRuntimeDeviceProfile` and runtime capability checks, including OpenCL-discovered facts such as image support,
3D image writes, int32 atomics, subgroups, local memory, and vector width. Provider metadata answers a different question: which module
formats and capability facts this backend family knows how to expose through the shared API.

When a policy is built from `GpuRuntimeBackendCatalog` entries, this provider metadata is attached to every backend
candidate as `GpuRuntimeBackendCandidateMetadata`. The selection explanation keeps the old compact candidate summary,
then adds readable lines such as `moduleFormats: cubin,cuda-c,fatbin,ptx`, `capabilityVocabulary: compute-capability,...`, and
`executionPipeline: available=true/false`. Built-in CUDA reports `available=true` for its non-production skeleton;
custom discovery-only providers should report `available=false`. Artifact maps expose the same facts under candidate-local
`*.executionSupport.*` fields, so CLIs, CI reports, and future backend scoring can reason about backend readiness
without opening a native runtime session.

Applications can also turn that metadata into hard selection checks:

```java
GpuRuntimeBackendPolicy policy = GpuRuntimeBackendPolicy.builder()
        .requireExecutionPipelineAvailable()
        .requireDeclaredModuleFormat(GpuBackendModuleFormat.OPENCL_C)
        .preferStandardBackendsWithPlannedDiagnostics()
        .build();
```

Use `requireDeclaredModuleFormat(...)` to keep a policy on `opencl-c`, `cuda-c`, `ptx`, `spir-v`, or another declared
artifact family. Use `requireDeclaredCapability(...)` when the backend family must know how to report a portable fact
such as compute capability or local memory. Use `requireExecutionPipelineAvailable()` when backends without a shared
pipeline should be rejected before a native compile path is attempted. CUDA currently passes this pipeline metadata gate
but remains `productionExecution=false`, so production callers should still require an explicit production adapter when
native CUDA execution is needed.

Selection explanations also attach `GpuRuntimeBackendCandidateScore` to every candidate. The score records fallback
preference order, provider metadata readiness, runtime report facts, and a reserved policy-adjustment slot, then renders
those values under `*.score.*` artifact fields and a compact Markdown line. Runtime facts currently include API version,
portable runtime capability count, legacy feature flags, local memory, and max work-group size. By default the selector
still uses explicit fallback order and selects the first candidate that passes hard checks. Applications that want
score-based ordering must opt in:

```java
GpuRuntimeBackendPolicy rankedPolicy = GpuRuntimeBackendPolicy.builder()
        .rankCandidatesByScore()
        .preferStandardBackendsWithPlannedDiagnostics()
        .build();
```

In score mode, all candidates are evaluated, hard failures still reject candidates, and the highest total score among
passing candidates wins. Owned candidates that pass hard checks but lose ranking are closed automatically. This mode is
intended for deliberate backend selection experiments and future workload-aware placement; it is not enabled silently.

For workload-specific evidence, attach read-only score contributors explicitly:

```java
GpuRuntimeBackendScoreContributor cudaEvidence = new GpuRuntimeBackendScoreContributor() {
    @Override
    public GpuRuntimeBackendScoreContribution scoreCandidate(GpuRuntimeBackendScoreContext context) {
        if (context.report().backendTarget() != GpuBackendTarget.CUDA) {
            return GpuRuntimeBackendScoreContribution.none();
        }
        return GpuRuntimeBackendScoreContribution.of(25_000, "cached method probe favored CUDA +25000");
    }
};

GpuRuntimeBackendPolicy rankedPolicy = GpuRuntimeBackendPolicy.builder()
        .rankCandidatesByScore()
        .scoreCandidatesWith(cudaEvidence)
        .scoreCandidatesForCompileOptions(GpuRuntimeCompileOptions.cuda(List.of(), Map.of(), "probe-ranked"))
        .preferStandardBackendsWithPlannedDiagnostics()
        .build();
```

Score contributors are not hard gates. They fill the `policyAdjustment` bucket, add diagnostics to the candidate score,
and remain fail-soft. Use `require...` helpers for mandatory backend constraints.

For warmed `@GPUTest` selection-probe evidence, use the built-in cache-only bridge instead of writing a custom
contributor:

```java
GpuRuntimeBackendPolicy rankedPolicy = GpuRuntimeBackendPolicy.builder()
        .rankCandidatesByScore()
        .scoreCandidatesForCompileRequest(request)
        .scoreCandidatesWithCachedMethodTestProbeEvidence()
        .preferStandardBackendsWithPlannedDiagnostics()
        .build();
```

`scoreCandidatesWithCachedMethodTestProbeEvidence()` reads only the configured method-test probe cache from the compile
options stored in `request`. It never compiles or executes probes during backend selection. If the request uses
`withPersistentMethodTestProbeEvidenceRanking(path, maxEntryAge)`, expired entries are treated as misses and old but
still-valid entries are down-weighted in `policyAdjustment`; candidate diagnostics include freshness and age-limit facts.

For compiler-resource evidence that was already produced by a compile artifact/log, use the built-in compiler feedback
bridge:

```java
GpuBackendCompilerFeedbackReport compilerFeedback =
        GpuBackendCompilerFeedbackRegistry.loadWithBuiltIns().inspect(snapshot);

GpuRuntimeBackendPolicy rankedPolicy = GpuRuntimeBackendPolicy.builder()
        .rankCandidatesByScore()
        .scoreCandidatesWithCompilerFeedback(compilerFeedback)
        .preferStandardBackendsWithPlannedDiagnostics()
        .build();
```

`scoreCandidatesWithCompilerFeedback(report)` is read-only and precomputed. It does not compile candidate backends during
selection, applies only to matching backend targets unless the report target is `UNKNOWN`, and contributes bounded
resource diagnostics to `policyAdjustment`. Use it for ranking hints such as lower register pressure, no spills, no stack
frame, known local-memory usage, and occupancy. It is not a hard requirement and intentionally has much lower weight than
cached `@GPUTest` correctness evidence.

For caller-owned workload intent, use `GpuRuntimeWorkloadHints` and the built-in workload-hints bridge:

```java
GpuRuntimeWorkloadHints hints = GpuRuntimeWorkloadHints.builder()
        .expectedItemCount(1_000_000L)
        .preferredWorkGroupSize(256)
        .memoryIntensity(GpuRuntimeWorkloadIntensity.HIGH)
        .arithmeticIntensity(GpuRuntimeWorkloadIntensity.HIGH)
        .requireCapability(GpuRuntimeCapability.COMPUTE_CAPABILITY)
        .preferModuleFormat(GpuBackendModuleFormat.PTX)
        .build();

GpuRuntimeBackendPolicy rankedPolicy = GpuRuntimeBackendPolicy.builder()
        .rankCandidatesByScore()
        .scoreCandidatesWithWorkloadHints(hints)
        .preferStandardBackendsWithPlannedDiagnostics()
        .build();
```

`scoreCandidatesWithWorkloadHints(hints)` fills `policyAdjustment` from expected parallelism, preferred work-group size,
memory/arithmetic intensity, preferred module formats, and portable capability intent. It can read candidate reports,
provider metadata, and optional device profile context. It stays advisory and fail-soft; mandatory constraints should use
the explicit `require...` APIs.

For metadata-derived placement, use the inferred workload bridge instead of duplicating descriptor scanners in user code:

```java
GpuRuntimeBackendPolicy rankedPolicy = GpuRuntimeBackendPolicy.builder()
        .rankCandidatesByScore()
        .scoreCandidatesWithInferredWorkloadHints(descriptor, irGpuArtifact)
        .preferStandardBackendsWithPlannedDiagnostics()
        .build();
```

`scoreCandidatesWithInferredWorkloadHints(...)` is implemented as the built-in
`GpuRuntimeInferredWorkloadHintBackendScoreContributor`. It derives conservative `GpuRuntimeWorkloadHints` from the
descriptor and optional `IrGpu` artifact, including visible global/local/constant address-space requirements, then routes them through the same workload-hints scoring formula. The bridge is
read-only, fail-soft, and does not load, compile, or execute backend candidates. Its diagnostics use the `inferred
workload ...` prefix so reports can distinguish automatic method-derived evidence from caller-owned hints.

If a backend has a compile -> prepare -> invoke slice, expose it with `executionPipelineFactory()`. OpenCL already does
this through an `OpenClBackendExecutionPipelineFactory`. CUDA publishes a non-production skeleton factory so diagnostics,
artifact receipts, and CI can exercise the shared path. Its compile stage can produce a typed CUDA compile-preview
artifact today. The built-in `cuda.moduleLoader=driver` bridge can load the CUDA Driver API and create/unload PTX
module/function handles from PTX/CUBIN/FATBIN payloads, record driver-version/PTX metadata receipts, reject known PTX ISA versions that need a newer
CUDA driver API, and reject PTX targets that are newer than the selected CUDA device compute capability before native
module load. The built-in `cuda.argumentBinder=driver` bridge can
preflight those handles, prepare an empty argument frame for zero-argument kernels, bind non-empty primitive array buffer
shapes, GPU vector array buffer shapes, and `@GPUStruct[]` buffer shapes through CUDA device memory allocation plus host-to-device upload, bind
primitive scalar and `@GPUStruct` `VALUE` slots, and map primitive or `@GPUStruct[]` array `LOCAL` arguments to CUDA dynamic shared memory. Unsupported
buffer shapes, unsupported local types, image/sampler payloads, and broader readback remain separate fail-closed stages.

CUDA native compiler bridges implement `CudaNativeCompilerBridge` and are loaded through ServiceLoader plus built-ins.
The built-in `nvcc` process bridge is opt-in via `GpuRuntimeCompileOptions.cudaNvcc(...)` or
`cuda.compilerBridge=nvcc`; it can emit PTX, CUBIN, or FATBIN and does not load a CUDA module or launch kernels.
CUDA module/function loaders implement `CudaModuleLoaderBridge` and are also ServiceLoader-backed. They are requested
separately with `.withCudaDriverModuleLoader()` or `cuda.moduleLoader=driver`, so module emission, module loading, argument
binding, and kernel launch remain independently testable stages. The built-in `driver` bridge currently stops after
CUDA Driver API library/symbol probing only when the driver is unavailable or required symbols are missing; otherwise it
calls `cuInit`, reads `cuDriverGetVersion`, parses PTX `.version` / `.target`, performs PTX ISA-vs-driver and
target-vs-device compute capability preflights, loads compatible PTX through `cuModuleLoadDataEx`, resolves the function
with `cuModuleGetFunction`, and owns cleanup through `CudaDriverLoadedModule.close()` / `cuModuleUnload`. Successful
receipts include stable `runtime.cuda.loadedModule.driver.version.*`, `runtime.cuda.ptxCompatibility.*`,
`runtime.cuda.ptxDriverCompatibility.*`, and `runtime.cuda.ptxDeviceCompatibility.*` fields; incompatible PTX ISA versions
return `cuda-driver-ptx-version-unsupported:*`, and incompatible targets return `cuda-ptx-target-too-new:*` before driver
module load.
CUDA argument binders implement `CudaArgumentBinderBridge` and are requested separately with
`.withCudaDriverArgumentBinder()` or `cuda.argumentBinder=driver`. The built-in driver binder requires a
`CudaDriverLoadedModule`, returns `cuda-driver-module-handle-missing` when the module loader supplied only synthetic
handles, and uses `CudaKernelArgumentFrame` as the closeable prepared-argument receipt. It succeeds for zero-argument
kernels and for non-empty primitive/vector/struct array `READ_ONLY` / `READ_WRITE` descriptor arguments. For descriptor arguments it receives
shallow-copied Java invocation values through `CudaExecutionPlan`, reports `cuda-driver-argument-values-missing` when no
payload exists, and reports `cuda-driver-argument-count-mismatch` when descriptor and payload disagree. For supported
buffers, the built-in binder resolves `cuMemAlloc_v2`, `cuMemcpyHtoD_v2`, and `cuMemFree_v2`, packs vector arrays using
declared storage width, packs struct arrays and `@GPUStruct` `VALUE` arguments using the same primitive/vector/nested-struct field layout as the OpenCL ABI slice, records `CudaDriverDeviceAllocation` receipts, builds the host-side kernel parameter table, and frees device memory when the
argument frame closes. Primitive scalar `VALUE` arguments are stored as native-order host slots too. One primitive or `@GPUStruct[]` array
`LOCAL` argument is recorded as dynamic shared-memory bytes and intentionally omitted from the kernel parameter table.
Multiple `LOCAL` arguments share one dynamic allocation; the CUDA lowerer emits hidden unsigned byte-offset parameters
after visible non-`LOCAL` parameters, and the driver binder appends matching offset slots after normal device-pointer and
scalar slots. Empty buffers,
unsupported scalar/buffer types, image/sampler payloads, non-`@GPUStruct` object `VALUE` payloads, unsupported local shapes, and unsupported readback shapes remain fail-closed. Rejected image/sampler payloads also carry
`runtime.cuda.imageSamplerRuntimeBindingPlan.*` fields that record planned texture/surface/sampler slots, argument
metadata, and active runtime slot count `0`. A successful binder reports portable binding counts through
`GpuRuntimeInvocationBindingSummary` plus CUDA-specific `runtime.cuda.argumentBinding.*`,
`runtime.cuda.argumentFrame.*`, and `runtime.cuda.executionPlan.*` fields, but it still does not imply production CUDA
execution support.
CUDA kernel launchers implement `CudaKernelLauncherBridge` and are requested separately with
`.withCudaDriverKernelLauncher()` or `cuda.kernelLauncher=driver`. The built-in driver launcher resolves
`cuLaunchKernel`, requires a real loaded module/function handle, successful native argument binding, a prepared argument
frame, and an explicit `GpuExecutionConfig`, then submits the Driver API launch with CUDA grid/block dimensions derived
from the portable global/local work shape plus any prepared dynamic shared-memory byte size. A successful launcher reports `runtime.cuda.kernelLaunch.*` and portable
`runtime.backend.invoke.*` fields. Keep readback separate: launch success means the bridge submitted work, not that
host-visible output copying has completed.
Use `validateCudaLaunchContract` to check this boundary without CUDA hardware. The synthetic contract accepts valid 1D/3D
launch shapes and dynamic shared memory, and expects stable fail-closed blockers for auto-local, non-divisible shapes,
oversized blocks, and shared-memory limit violations.
Use `validateCudaImageSamplerContract` to check the current image/sampler boundary. It proves Java image/sampler wrapper
parameters are recognized by CUDA binder preflight but return stable unsupported receipts until a CUDA texture/surface/sampler
ABI is implemented. Those unsupported receipts now also carry `runtime.cuda.imageSamplerRuntimeBindingPlan.*` fields with
planned texture/surface/sampler slots, argument metadata, and active runtime slots set to `0`.
Use `validateCudaImageSamplerAbiPlan` when changing the planned CUDA mapping. It keeps the future texture/surface/sampler
ABI explicit as metadata (`CUtexObject`, `CUsurfObject`, and folded sampler descriptor state) while production binding stays
disabled. The same gate also reports source-preview kernel/metadata slot counts, planned runtime slot counts, and keeps
`runtimeBindingEnabled=0` / `runtimeBindingKernelParameterSlots=0` visible for CI drift checks.
Use `validateCudaImageSamplerObjectCreationContract` when touching the future Driver API object boundary. It resolves the
planned texture/surface object symbols and supporting array/copy symbols through synthetic module handles, but must keep
`objectCreationEnabled=false`, `objectOwnershipBoundary=prepared`, and `activeObjectCount=0`. Missing
`cuTexObjectCreate` or `cuSurfObjectCreate` is reported as a stable
`cuda-image-sampler-object-creation-symbol-missing:*` blocker instead of silently enabling runtime binding. The owner
close path exists for future handles, but normal runtime binding still creates no CUDA image/sampler objects.
Use `validateCudaImageSamplerDescriptorContract` when touching planned `CUDA_RESOURCE_DESC` / `CUDA_TEXTURE_DESC` work.
It fixes the metadata vocabulary for resource descriptors, texture descriptors, sampler defaults, and native-layout
pending state while keeping `descriptorBuildEnabled=false`, `nativeDescriptorAllocationEnabled=false`, and
`activeDescriptorCount=0`. This prevents descriptor planning changes from silently becoming runtime image support.
Use `validateCudaImageSamplerNativeDescriptorLayout` when touching the future native descriptor builder boundary. It
pins the preview-only logical layout (`resourceLayouts=16`, `resourceLayoutFields=35`, `textureLayouts=9`,
`textureLayoutFields=54`) while keeping `nativeLayoutBuildEnabled=false`, `nativeDescriptorAllocationEnabled=false`, and
`activeNativeDescriptorCount=0`; it must not allocate CUDA descriptor memory or create texture/surface objects.
Use `validateCudaImageSamplerDescriptorBuildPlan` when touching invocation-time descriptor preflight. It proves valid
2D image/sampler wrapper payloads are recognized, missing handles/closed samplers/incomplete metadata are rejected with
stable blockers, and descriptor payload/native descriptor counts stay at `0` until the native builder is implemented.
Use `validateCudaImageSamplerDescriptorPayloadModel` when touching the Java-only payload shape above that preflight. It
builds logical resource/texture descriptor payload objects for ready plans, records 16 built-in resource payloads, 9
texture payloads, and 1 folded sampler payload, and keeps native allocation, object creation, and runtime binding
disabled. This is the last safe Java-side layer before a real native descriptor encoder exists.
Use `validateCudaImageSamplerNativeDescriptorEncodingPlan` when touching native descriptor field encoding. It records
logical field-write intent for ready payloads (`resType`, resource-handle fields, texture address modes, filter mode,
flags, and read mode), pins 35 resource field writes and 54 texture field writes, and keeps native writes, SDK struct
byte encoding, native allocation, object creation, and runtime binding disabled.
Use `validateCudaImageSamplerNativeDescriptorAllocationPreflight` when touching native descriptor allocation or lifecycle
ownership. It records only allocation, ownership, cleanup, and rollback intent (`resourceDescriptorAllocations=16`,
`textureDescriptorAllocations=9`, `plannedNativeDescriptors=25`) and must keep allocated descriptors, SDK struct byte
encoding, object creation, runtime binding, and active native descriptors at `0`.
Use `validateCudaImageSamplerNativeDescriptorAllocationTransactionPlan` when touching the future allocation transaction
shape. It records Java-side descriptor owner skeletons and deterministic cleanup/rollback order (`descriptorOwners=25`)
while keeping native addresses, allocation apply, cleanup apply, rollback apply, SDK struct byte encoding, object
creation, runtime binding, and active native descriptors at `0`.
Use `validateCudaImageSamplerNativeDescriptorEncodingTransactionPlan` when touching the future native descriptor write
transaction. It connects logical field-write intent to planned owner slots (`descriptorWrites=25`,
`resourceFieldWrites=35`, `textureFieldWrites=54`, `fieldWrites=89`, `ownersPresent=25`) while keeping native writes,
SDK struct byte encoding, object creation, runtime binding, and active native descriptors at `0`.
Use `validateCudaImageSamplerObjectCreationRequestPlan` when touching the layer that will eventually call texture/surface
object creation. It records only planned requests (`objectRequests=16`, `textureObjectRequests=8`,
`surfaceObjectRequests=8`, `foldedSamplers=1`) and must keep object creation calls disabled, active objects at `0`, and
blocked descriptor plans at zero requests. Normal runtime binding still must not call `cuTexObjectCreate` or
`cuSurfObjectCreate`.
Use `validateCudaImageSamplerNativeObjectPreparationPreflight` when touching native descriptor/object handle preparation
below request planning. It proves create/destroy symbols are known for planned requests while native descriptor handles,
object handles, ownership, and object creation remain unavailable (`resourceDescriptorsAvailable=0`,
`textureDescriptorsAvailable=0`, `objectHandlesAvailable=0`, `objectCreationCallEnabledCount=0`). It also proves the
owner/write intent is already visible (`resourceDescriptorOwnersPresent=16`, `resourceDescriptorWritesPlanned=16`,
`textureDescriptorOwnersPresent=8`, `textureDescriptorWritesPlanned=8`) while native descriptor addresses and native
writes remain at `0`. This gate should stay blocked until native descriptor allocation and Driver API object creation are
introduced deliberately.
Use `validateCudaImageSamplerRuntimeObjectBindingPlan` when touching the future kernel-argument binding layer for
texture/surface objects. It connects planned object requests to planned kernel slots (`objectBindings=16`,
`plannedObjectKernelParameterSlots=16`, `plannedMetadataKernelParameterSlots=28`, `plannedKernelParameterSlots=44`) and
must keep `runtimeBindingKernelParameterSlots=0`, object creation calls disabled, active objects at `0`, and blocked
descriptor plans at zero slots. Normal runtime binding still must not pass `CUtexObject` / `CUsurfObject` handles to
CUDA kernels.
Use `validateCudaImageSamplerRuntimeObjectBindingTransactionPreflight` when touching the final boundary before real
texture/surface object kernel-argument writes. It proves the runtime sees planned transactions but still lacks the native
prerequisites (`objectHandlesAvailable=0`, `nativeDescriptorsAvailable=0`, `resourceDescriptorNativeAddressesPresent=0`,
`resourceDescriptorNativeWritesEnabled=0`, `textureDescriptorNativeAddressesPresent=0`,
`textureDescriptorNativeWritesEnabled=0`, `transactionApplyEnabledCount=0`, and `kernelParameterWriteEnabledCount=0`).
It also proves owner/write intent is already visible (`resourceDescriptorOwnersPresent=16`,
`resourceDescriptorWritesPlanned=16`, `textureDescriptorOwnersPresent=8`, `textureDescriptorWritesPlanned=8`). This
gate should stay blocked until native descriptor allocation, native descriptor writes, Driver API object creation,
object ownership, and kernel parameter writes are implemented as one auditable transaction.
Use `validateCudaImageSamplerFailClosedContract` as the final hardware-free guardrail before native image/sampler work.
It aggregates the staged reports and must keep all components ready (`componentReady=15/15`) while native mutation stays
at `0`: no runtime binding slots, object creation calls, native descriptor availability/addresses/writes, object
handles, active native descriptors, or active objects. Treat this as the quick public API smoke for the whole CUDA
image/sampler fail-closed boundary.
CUDA source lowering for image/sampler methods remains a separate stage. The supported preview slice now covers 2D
texture reads and 2D surface writes (`Image2DReadOnly` -> `cudaTextureObject_t`, `Image2DWriteOnly` ->
`cudaSurfaceObject_t`, `read_imagef/i/ui` -> `tex2D<T>`, `write_imagef/i/ui` -> `surf2Dwrite(...)`, folded `Sampler`,
and explicit width/height metadata parameters). Non-2D image shapes, unsupported metadata, and runtime binding still fail
closed until their CUDA ABI evidence exists.
CUDA readback bridges implement `CudaKernelReadbackBridge` and are requested separately with `.withCudaDriverReadback()`
or `cuda.readback=driver`. The built-in driver readback bridge resolves `cuMemcpyDtoH_v2`, copies supported
`READ_WRITE` primitive/vector/struct array allocations back into the original Java arrays, records allocation-level readback status, reports
`runtime.cuda.readback.*`, and completes the portable `runtime.backend.invoke.readback.*` counts. Keep this separate from
launch so async launch, blocking readback, and future zero-copy paths can be tested independently.

The factory should bind the shared runner to the concrete backend instance's own compiler, preparer, and invoker. Do not
create a second buffer registry, native session, cache, or extension-hook path inside the factory; otherwise provider
execution can diverge from the production backend path.

For catalog UIs, examples, and CI reports, call `executionAvailability()` on the provider. It returns a compact
`GpuRuntimeBackendExecutionAvailability` card with status, summary, blockers, diagnostics, artifact fields, and Markdown.
For discovery-only providers, the same card can produce `unsupportedExecutionResult(...)`, a typed non-success pipeline
receipt that says exactly which execution stages are missing.

Backend execution should be added stage-by-stage: discovery first, then lowering, compilation, preparation/binding,
invocation, readback, and cleanup. Until a stage exists, return explicit unsupported/skipped diagnostics and portable
`runtime.failure.*` fields instead of throwing backend-specific exceptions.

Backend stages use the shared `GpuBackendPipelineStage` vocabulary: `DISCOVER`, `SELECT`, `LOWER`, `COMPILE`, `LOAD`,
`PREPARE`, `BIND`, `INVOKE`, `READBACK`, and `CLOSE`. Stage results should report a `GpuBackendStageStatus` such as
`SUCCEEDED`, `SKIPPED`, `UNSUPPORTED`, or `FAILED`, then render portable artifact fields through `GpuBackendStageResult`.
Use the typed wrappers as the public receipt for each stage: `GpuBackendLoweringResult`, `GpuBackendCompilationResult`,
`GpuBackendPreparationResult`, and `GpuBackendInvocationResult`. They keep the backend target, status, blockers,
diagnostics, module format, binding summary, launch shape, and readback facts under stable `runtime.*` keys, so tools can
read one journal shape for OpenCL now and CUDA/Vulkan/Metal later. Lowerers can already expose the first wrapper through
`GpuBackendLowerer.lowerWithStageResult(...)` while keeping their existing `lower(...)` implementation intact.

OpenCL already emits the compile/prepare/invoke receipts into its lifecycle events. Adapter authors should mirror that
shape: keep compatibility aliases if an existing backend has them, but always add the typed receipt fields so journal
readers can depend on `runtime.backend.compilation.*`, `runtime.backend.prepare.*`, and `runtime.backend.invoke.*`.

### Execution Handles

Keep native objects private, but expose the shared handle contracts:

| Contract | Purpose |
| --- | --- |
| `GpuBackendCompiledKernel` | A compiled backend module/entrypoint plus cache key and artifact snapshot. |
| `GpuPreparedKernel` | A compiled kernel after arguments, buffers, locals, and scalars are prepared. |
| `GpuBackendKernelCompiler` | The stage that turns a lowered module artifact into a compiled kernel handle. |
| `GpuBackendKernelPreparer` | The stage that binds backend resources before launch. |
| `GpuBackendKernelInvoker` | The stage that launches a prepared kernel and reports readback completion. |
| `GpuRuntimeBackendExecutionSupport` | Provider-level metadata saying which stages are available. |
| `GpuRuntimeBackendExecutionAvailability` | User-facing provider card for execution status, blockers, artifacts, and Markdown. |
| `GpuRuntimeBackendProviderCatalog` | Merged provider catalog for built-ins plus ServiceLoader entries, without native runtime creation. |
| `GpuRuntimeBackendCandidateMetadata` | Catalog/provider metadata copied onto one backend-selection candidate. |
| `GpuBackendExecutionPipelineFactory` | Provider-level factory that binds a shared runner to a concrete backend instance. |
| `GpuBackendExecutionPipeline` | A small runner for compile -> prepare -> invoke when a backend can execute those stages as one slice. |
| `GpuBackendExecutionPipelineResult` | The combined stage receipt returned by the runner. |

OpenCL already adapts its existing `OpenClCompiledKernel`, `OpenClPreparedExecution`, and `OpenClExecutionPreparer` to
these contracts. Its backend routes compile, prepare, and invoke through `kernelCompiler()`, `kernelPreparer()`, and
`kernelInvoker()` accessors backed by small OpenCL adapters, while preserving older protected hooks. New backends should
follow the same pattern: keep CUDA/OpenCL/Vulkan handles strongly typed internally, but expose the portable handle
interfaces so lifecycle, diagnostics, fallback, cache reporting, and future policy logic can read one shape.

Use `GpuBackendExecutionPipeline` when a backend wants the shared runner to perform the simple compile -> prepare ->
invoke sequence and return `GpuBackendCompilationResult`, `GpuBackendPreparationResult`, and `GpuBackendInvocationResult`
together. A backend may still keep richer orchestration around it for cache lookup, lifecycle events, validation,
artifact dumping, and fallback decisions.

OpenCL now follows that model in production: the runner is used for compile -> prepare -> invoke, while OpenCL-specific
wrappers preserve the existing compiled-kernel cache, lifecycle events, artifact dumps, launch validation, and failure
formatting. New backends should use the same split instead of embedding policy, logging, or cache semantics inside native
compile/invoke code.

There are two runner modes:

| Method | Behavior |
| --- | --- |
| `execute(...)` | Strict mode. Stage exceptions propagate to the caller, which is useful inside an already-owned production runtime. |
| `executeSafely(...)` | Diagnostic mode. Compile, prepare, or invoke exceptions become typed `FAILED` receipts, and later stages become `SKIPPED`. |

For planned or discovery-only backends, use `GpuBackendExecutionPipelineResult.unsupported(...)` to render a complete
non-success result with `COMPILE=UNSUPPORTED`, `PREPARE=SKIPPED`, and `INVOKE=SKIPPED`. This gives CI, lifecycle journals,
and adapter bring-up tools a clear answer like "CUDA execution is not implemented yet" without pretending that a CUDA
compiler or invoker exists.

### Backend Hook Contracts

Backend hooks are the ServiceLoader-facing contracts for A8 adapter hardening. Most hooks are still read-only observers,
but public backend discovery and the OpenCL runtime now execute the first hook families at stable result/lifecycle
boundaries so tools can observe and enrich backend receipts without changing runtime behavior.

All backend hooks extend `GpuBackendHook`, which provides:

| Contract field | Meaning |
| --- | --- |
| `backendTargets()` | Empty means all backend families; otherwise the hook is filtered to explicit targets such as `OPENCL` or `CUDA`. |
| `failurePolicy()` | Defaults to `CONTINUE`; current OpenCL hook execution is fail-soft and records failures in lifecycle fields. |
| `artifactFields(...)` / `lifecycleFields(...)` | Stable metadata for catalogs, CI receipts, and lifecycle journals. |
| `extensionOrder()` | Deterministic ordering when hooks are loaded through ServiceLoader. |

The concrete hook families are:

| Hook | Default phase | Default permission | Purpose |
| --- | --- | --- | --- |
| `GpuBackendPolicyContributor` | `BACKEND_POLICY` | `READ_ONLY` | Add backend-selection requirements or policy facts. |
| `GpuRuntimeBackendScoreContributor` | `BACKEND_POLICY` | `READ_ONLY` | Add explainable score adjustments for one candidate. |
| `GpuBackendDiscoveryContributor` | `BACKEND_DISCOVERY` | `READ_ONLY` | Observe or contribute backend/device discovery facts. |
| `GpuBackendLoweringHook` | `BACKEND_LOWERING` | `READ_ONLY` | Observe lowering results before future registries decide whether mutation is allowed. |
| `GpuBackendCompilationHook` | `BACKEND_COMPILATION` | `READ_ONLY` | Observe compilation receipts and compiler-stage facts. |
| `GpuBackendInvocationHook` | `BACKEND_INVOCATION` | `READ_ONLY` | Observe invocation/readback receipts. |
| `GpuBackendArtifactHook` | `ARTIFACT_EMISSION` | `READ_ONLY` | Add backend-specific artifact fields. |

Default hook methods are no-ops. Today, only hooks whose `extensionPermission()` is `READ_ONLY` are executed. If a hook
returns a replacement discovery, lowering, compilation, or invocation result, the runtime ignores that replacement,
records `mutation-ignored`, and continues with the original production result. Hooks with stronger permissions are
skipped until an explicit production-affecting registry exists.

`GpuBackendHookRegistry` is both the inspection layer and the read-only execution boundary for these hooks. It can load
services registered under either the base `GpuBackendHook` interface or the concrete hook interfaces, applies
deterministic ordering, validates duplicate extension ids and concrete hook family contracts, filters by backend
target/capability, renders artifact fields or Markdown, and records execution facts under
`runtime.backend.hookExecution.*`.

Contract validation is fail-fast for setup mistakes that would make ServiceLoader behavior ambiguous: duplicate
`extensionId()` values, `backendTargets()` containing `null`, and concrete hook implementations that override the wrong
phase or omit the expected capability. Catalog artifacts also include `runtime.backend.hookRegistry.contract.*` fields.
Those fields show which hooks are read-only and which hooks are loaded but require future production authorization before
they can execute.

For authorization diagnostics, use `authorizationCatalog(...)` when you want the standard discovery/lowering/
compilation/invocation/artifact view, or `authorizationReport(...)` for one stage. These are preview/report APIs, not
mutating execution switches: the current runner still executes only read-only hooks.

```java
GpuBackendHookRegistry registry = GpuBackendHookRegistry.loadWithServiceLoader();
GpuBackendHookAuthorizationCatalog catalog = registry.authorizationCatalog(GpuBackendTarget.OPENCL);

System.out.println(catalog.toMarkdown());
```

`GpuBackendHookAuthorizationPolicy.previewExplicitAuthorization(...)` can model a future policy decision for tests and
review tools. Even when a stronger hook id is explicitly authorized by that policy, the report marks it as
`AUTHORIZED_BUT_EXECUTION_DISABLED` until a separate production-affecting runner exists. Reports also expose
`firstBlocker()` and `*.firstBlocker*` artifact fields so CI and examples can show the first actionable authorization
problem without parsing every decision.

For a CI-style pass/fail gate, use `GpuBackendHookAuthorizationValidator`. It is still hardware-free: it only inspects
the loaded hook contracts and does not open OpenCL, CUDA, or any native runtime.

```java
import net.sixik.ga_utils.javatogpu.runtime.validation.GpuBackendHookAuthorizationValidationResult;
import net.sixik.ga_utils.javatogpu.runtime.validation.GpuBackendHookAuthorizationValidator;

GpuBackendHookAuthorizationValidationResult result =
        GpuBackendHookAuthorizationValidator.validateReadOnlyClasspath(GpuBackendTarget.OPENCL);

System.out.println(result.toMarkdown());
System.exit(result.recommendedExitCode());
```

The default validator expects the classpath to be runtime-ready for the current registry, which means no blocked hooks
and no future-authorized-but-disabled hooks. If a tool wants to review an explicit preview policy without failing on
`AUTHORIZED_BUT_EXECUTION_DISABLED`, call `GpuBackendHookAuthorizationValidator.validate(..., true)` and keep the result
clearly labeled as preview-only.

Current wiring is intentionally narrow:

| Stage | What runs now | Behavior |
| --- | --- | --- |
| Backend/device discovery helpers | `GpuBackendDiscoveryContributor.afterDiscovery(...)` and `discoveryFacts(...)` | Read-only observer; receipts are stored on `GpuRuntimeDeviceDiscoveryResult` artifact fields. |
| OpenCL lowerer selection lifecycle fields | `GpuBackendLoweringHook.afterLowering(...)` | Read-only observer; replacement results are ignored and reported. |
| Compilation lifecycle fields | `GpuBackendCompilationHook.afterCompilation(...)` | Read-only observer; replacement results are ignored and reported. |
| Invocation lifecycle fields | `GpuBackendInvocationHook.afterInvocation(...)` | Read-only observer; failures are captured instead of failing the kernel. |
| Artifact/lifecycle enrichment | `GpuBackendArtifactHook.contributeArtifactFields(...)` | Returned fields are captured as contribution metadata, not merged directly into root fields. |

Discovery helpers also expose explicit overloads that accept a `GpuBackendHookRegistry`, which keeps tests and tools from
depending on ServiceLoader setup when they need deterministic hook receipts.

Example service file for a policy hook:

```text
META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuBackendPolicyContributor
```

Example service file for a score hook that should appear in hook catalogs:

```text
META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeBackendScoreContributor
```

Example service files for stage hooks:

```text
META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuBackendDiscoveryContributor
META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuBackendLoweringHook
META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuBackendCompilationHook
META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuBackendInvocationHook
META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuBackendArtifactHook
```

The examples app includes runnable ServiceLoader hook implementations under `net.sixik.ga_utils.examples`:

```powershell
.\gradlew.bat :examples-app:runBackendHookServiceLoaderExample --console=plain
```

It also includes a hardware-free authorization preview for the blocked/default and preview-authorized cases:

```powershell
.\gradlew.bat :examples-app:runBackendHookAuthorizationPreviewExample --console=plain
```

And a small CI-style validator over the ServiceLoader classpath:

```powershell
.\gradlew.bat :examples-app:runBackendHookAuthorizationValidatorExample --console=plain
```

That example uses discovery/lowering/compilation/invocation hooks as read-only observers and an artifact hook as a
metadata contributor. It is intentionally hardware-free and uses `GpuBackendHookTestHarness` to generate synthetic
OpenCL discovery/lowering/compile/invoke/artifact receipts, so extension authors can verify registration and receipt
fields before touching native runtimes.

For library tests, use the harness directly instead of shelling out to the examples app:

```java
import net.sixik.ga_utils.javatogpu.runtime.validation.GpuBackendHookTestHarness;
import net.sixik.ga_utils.javatogpu.runtime.validation.GpuBackendHookTestHarnessReport;

GpuBackendHookTestHarnessReport report = GpuBackendHookTestHarness
        .of(List.of(new MyBackendArtifactHook()))
        .runSyntheticOpenCl();

System.out.println(report.toMarkdown());
```

The harness also exposes `runSynthetic(GpuBackendTarget)` and static synthetic receipt factories for discovery,
lowering, compilation, preparation, and invocation. Its report includes execution fields plus stage-by-stage
`runtime.backend.hookAuthorization.discovery/lowering/compilation/invocation/artifact.*` fields. This lets a hook module
test target filters, fail-soft behavior, `mutation-ignored` reporting, contribution fields, contract diagnostics,
authorization gates, first-blocker summaries, and ServiceLoader registration without requiring OpenCL hardware.

Catalog inspection:

```java
GpuBackendHookRegistry registry = GpuBackendHookRegistry.loadWithServiceLoader();
System.out.println(registry.toMarkdown());
```

## Module Formats And Capabilities

Backend adapters should avoid inventing new free-form names for common module formats and device facts. Use the shared
vocabulary first, then add backend-specific details only when the portable field is not enough.

For backend source/binary artifacts, keep `GpuBackendModuleArtifact.format()` as the stable artifact string and use
`GpuBackendModuleArtifact.moduleFormat()` when code needs the typed meaning:

```java
GpuBackendModuleArtifact module = GpuBackendModuleArtifact.cudaSource(
        "extern \"C\" __global__ void kernel() {}",
        "generated/kernel.cu",
        "example-cuda-lowerer:1"
);

System.out.println(module.format());        // cuda-c
System.out.println(module.moduleFormat());  // CUDA_C
```

The current canonical module-format keys are:

| Key | Meaning |
| --- | --- |
| `opencl-c` | OpenCL C source. |
| `cuda-c` | CUDA C source. |
| `ptx` | NVIDIA PTX text/intermediate module. |
| `cubin` | NVIDIA CUDA device binary module. |
| `fatbin` | NVIDIA CUDA fat binary bundle. |
| `spir-v` | SPIR-V binary/intermediate module. |
| `metal-shading-language` | Metal shader source. |
| `native-binary` | Backend-owned binary format. |
| `unknown` | Format was not reported or is not recognized yet. |

For device/backend facts, use `GpuRuntimeCapability` for portable checks. Existing `GpuRuntimeFeature` values still work,
but `GpuRuntimeCapability` is the broader vocabulary intended for backend selection, diagnostics, and future CUDA/PTX/
SPIR-V policy work.

```java
boolean hasFp64 = report.supports(GpuRuntimeCapability.FP64);
boolean hasLocalMemory = profile.supportsCapability(GpuRuntimeCapability.LOCAL_MEMORY);
Map<String, String> facts = profile.capabilityFacts();
```

Device profiles now expose indexed capability fields such as `runtime.device.capability.0=fp64` and direct flags such as
`runtime.device.capability.local-memory=true`. OpenCL profiles also carry direct normalized flags such as
`compilerVersion`, `supportsImage3dWrites`, and `supportsAtomics`, which are mirrored into portable capability fields when supported. Keep backend-specific facts namespaced, for example
`runtime.device.cuda.computeCapability`, rather than replacing the portable capability field.

## Backend Metadata

Prefer portable annotations first. They become backend-neutral `IrGpu` metadata, and each backend can lower that metadata into its native representation.

```java
@GPU
@GPUWorkGroupSize(x = 8, y = 8, z = 1)
@GPUWorkGroupSizeHint(x = 8, y = 8, z = 1)
static void kernel(@GPUGlobal float[] output) {
    output[GPU.get_global_id(0)] = 1.0f;
}
```

| Use | When |
| --- | --- |
| `@GPUWorkGroupSize` | Required work-group size. |
| `@GPUWorkGroupSizeHint` | Preferred work-group size. |
| `@GPUVectorTypeHint` | Backend vectorization hint. |
| `@GPUPacked` / `@GPUAligned` | Struct/layout intent. |
| `@GPUAlwaysInline` | Portable inlining intent. |
| `@GPUAttribute` | Last-resort backend-specific escape hatch. |

Use `@GPUAttribute` only when no portable annotation exists yet:

```java
@GPU
@GPUAttribute(backend = GpuBackendTarget.OPENCL, value = "vec_type_hint(float4)")
static void kernel(@GPUGlobal float[] output) {
    output[GPU.get_global_id(0)] = 1.0f;
}
```

For vendor or device-specific metadata, narrow the selector explicitly:

```java
@GPUAttribute(
        backend = GpuBackendTarget.OPENCL,
        vendor = GpuVendorTarget.NVIDIA,
        deviceClass = GpuDeviceClassTarget.DGPU,
        value = "some_vendor_hint"
)
```

`@OpenCLAttributes` and `@OpenCLQualifiers` remain compatibility surfaces for OpenCL-only expert code. New code should prefer portable annotations or `@GPUAttribute` with explicit selectors.

## Runtime Selection

Runtime device selection is deterministic by default. JavaToGpu prefers supported discrete GPUs, applies validation evidence when available, and allows explicit user overrides for advanced deployments.

Use `@GPUDeviceConstraint` when a kernel is only valid on specific backends, vendors, device classes, or capabilities:

```java
@GPU
@GPUDeviceConstraint(
        backends = {GpuBackendTarget.OPENCL},
        vendors = {GpuVendorTarget.NVIDIA, GpuVendorTarget.AMD},
        deviceClasses = {GpuDeviceClassTarget.DGPU},
        requiredFeatures = {"fp64"}
)
static void doubleKernel(@GPUGlobal double[] output) {
    output[GPU.get_global_id(0)] = 1.0;
}
```

Current portable feature names are `fp64`, `images`, and `subgroups`. Unknown required features fail closed.

Use `GpuRuntimeDeviceOverride` when a deployment requires a specific device identity, vendor, label, or class:

```java
GpuRuntimeCompileOptions options = GpuRuntimeCompileOptions
        .defaults(GpuBackendTarget.OPENCL)
        .withDeviceOverride(GpuRuntimeDeviceOverride.byVendor("NVIDIA"));

GpuKernelInvocation invocation = new GpuKernelInvocation(
        descriptor,
        arguments,
        options
);
```

Available override helpers include `byDeviceId(...)`, `byVendor(...)`, `byDeviceLabel(...)`, and `byDeviceClass(...)`. Constraints and overrides are required filters, not soft hints. If no candidate matches, selection fails with `GpuRuntimeDeviceSelectionException`.

## Device Policies

Use `GpuRuntimeDevicePolicy` when you want to add device-ranking evidence, capability facts, vendor quirks, or compile-option diagnostics.

Device policies do not directly pick the final device. They return a `GpuRuntimeDevicePolicyDecision` with score adjustments, rejected candidate identities, capability facts, quirks, diagnostics, and machine-readable fields. The registry then resolves conflicts deterministically:

- built-in and ServiceLoader policies are ordered by extension order, policy id, and version;
- duplicate ids or invalid metadata are rejected;
- rejection wins over positive score adjustments;
- equal scores are resolved by stable device identity;
- advisory failures are isolated, while production-profile failures stop selection.

This lets future CUDA, Vulkan, Metal, or multi-GPU backends plug into the same policy model without changing user-facing annotations.

For extension tests, use `GpuRuntimeDevicePolicyHarness` instead of opening a real OpenCL/CUDA session:

```java
import net.sixik.ga_utils.javatogpu.runtime.validation.GpuRuntimeDevicePolicyHarness;
import net.sixik.ga_utils.javatogpu.runtime.validation.GpuRuntimeDevicePolicyHarnessReport;

GpuRuntimeDevicePolicyHarnessReport report = GpuRuntimeDevicePolicyHarness
        .loadWithBuiltIns()
        .runSyntheticOpenCl();

System.out.println(report.toMarkdown());
```

The harness feeds synthetic CPU/iGPU/dGPU candidates through the same registry, returns the normal
`GpuRuntimeDeviceSelection`, and exposes compact artifact fields. The examples app registers a small read-only policy
and includes a runnable check:

```powershell
.\gradlew.bat :examples-app:runDevicePolicyHarnessExample --console=plain
```

## Method Fallback Variants

Use `@GPUFallbackVariant` when one logical operation has multiple implementations for different hardware classes.

```java
@GPU
@GPUFallbackVariant(group = "noise", id = "dgpu", priority = 100)
@GPUDeviceConstraint(deviceClasses = {GpuDeviceClassTarget.DGPU})
static void noiseDiscrete(@GPUGlobal float[] output) {
    output[GPU.get_global_id(0)] = discreteGpuPath();
}

@GPU
@GPUFallbackVariant(group = "noise", id = "igpu", priority = 10)
@GPUDeviceConstraint(deviceClasses = {GpuDeviceClassTarget.IGPU})
static void noiseIntegrated(@GPUGlobal float[] output) {
    output[GPU.get_global_id(0)] = integratedGpuPath();
}
```

Fallback methods must expose the same Java launch ABI: same parameter count, Java parameter types, and `GpuKernelParameterAccess` values. Parameter names may differ.

OpenCL evaluates variants through the normal device policy pipeline. Hard constraints still apply: backend compatibility, explicit device override, `@GPUDeviceConstraint`, and required features. Runtime may select another implementation for the active device, but it will not silently switch an already-created runtime session to different hardware.

## Generated Launcher Contract

Generated launchers are the normal user-facing call site for annotated kernels, but their source is still generated code: do not edit it by hand, and expect it to be regenerated when annotation processing runs. The generated file is intentionally readable so users can inspect what the processor embedded without learning backend internals.

The generated comments identify the important groups:

- kernel/resource/IrGpu/source constants and the runtime descriptor used by `GpuRuntime`;
- fallback-variant descriptors used by method-variant selection;
- default, explicit-size, compile-options, and standard backend/device preflight overloads;
- narrow return-first convenience helpers and `RETURN_VALUE_CONVENIENCE_*` skip metadata;
- generated output-length validation for helper-allocated output buffers.

For normal code, call the generated launcher class directly. For dynamic/framework code, use `GpuGeneratedLauncherInvoker.launcher(ownerClass, methodName)` and keep the returned `GeneratedLauncher` handle for repeated calls. It caches the generated launcher class, descriptor, and convenience metadata, but still invokes the generated overloads so fallback routing and generated validations remain active.

The descriptor and fallback descriptors are stable runtime inputs for the current alpha launcher contract. The exact generated class names and overload surface remain alpha-compatible, not beta-stable; code generators, wrappers, and framework integrations should go through `GpuGeneratedLauncherInvoker` when they need a softer reflection boundary.

## Runtime Failures

Public runtime failures extend `GpuRuntimeException`. Generated launchers do not catch these exceptions; they propagate to the user's call site so application code can choose an explicit fallback.

```java
try (GpuRuntimeScope ignored = GpuRuntime.useOpenClSharedCache()) {
    DemoKernel.transform(input, output);
} catch (GpuRuntimeException exception) {
    CpuFallback.transform(input, output);
}
```

`GpuRuntimeException` carries a stable error code, phase, summary, diagnostic context, help messages, and the original cause. Backend implementations should translate low-level driver/runtime failures once and should not double-wrap an existing `GpuRuntimeException`.

## Optimizer Extensions

Use `@GPUOptimize` to declare optimizer intent at the method level:

```java
@GPU
@GPUOptimize(fastMath = false, enabledFamilies = {"clamp", "step", "mix"})
static void strictKernel(@GPUGlobal float[] output) {
    output[GPU.get_global_id(0)] = 1.0f;
}
```

`@GPUOptimize` records policy hints: enablement, optimization profile, strictness, enabled or disabled families, journal/artifact-dump intent, production intent, vendor adaptation, vectorization preference, and resource-shaping intent.

Important safety rules:

- `fastMath = true` gives permission for proof-backed non-strict rewrites; it does not prove them.
- `productionIntent = true` records intent; it does not bypass production gates.
- Optimizer passes may run as diagnostics without changing production IR.
- Mutating passes need accepted evidence, runtime equivalence, rollback safety, and production authorization before they can affect production code.

Use `GpuRuntimeIrOptimizationPass` for a full staged optimizer pass. Use `GpuRuntimeIrPeepholeRule` for one typed-IR pattern hosted by the built-in peephole pass. Peephole rules currently participate through the same evidence and policy model as larger passes.

## Validation And Feedback

Use `GpuIrValidationProvider` for read-only IR validation. A provider should report `GpuIrValidationReportEntry` values with a stable rule id, severity, source anchor, method identity, and machine-readable fields.

Register providers in:

```text
META-INF/services/net.sixik.ga_utils.javatogpu.frontend.ir.validation.GpuIrValidationProvider
```

For provider tests, use `GpuIrValidationProviderHarness` instead of running javac annotation processing or opening a GPU:

```java
GpuIrValidationProviderHarnessReport report = GpuIrValidationProviderHarness
        .loadFromServiceLoader()
        .runSynthetic();

if (!report.allProvidersCompleted()) {
    throw new IllegalStateException(report.firstBlocker());
}
```

From the examples app:

```powershell
.\gradlew.bat :examples-app:runIrValidationProviderHarnessExample --console=plain
```

The harness runs synthetic helper/kernel IR methods through the same `GpuIrValidationRunner`, captures validation entries,
diagnostics, extension metadata, and failure isolation, and does not mutate IR.

Use `GpuBackendCompilerFeedbackProvider` to parse backend compiler diagnostics such as registers, spills, stack frame bytes, local memory, or occupancy.

Register providers in:

```text
META-INF/services/net.sixik.ga_utils.javatogpu.runtime.GpuBackendCompilerFeedbackProvider
```

Compiler feedback is advisory. It can explain performance, resource drift, and CI regressions, but it cannot satisfy optimizer proof or production-promotion requirements by itself. Distinct register files must remain separate; for example, an AMD parser must not merge SGPR and VGPR counts into a fake total.

For provider tests, use `GpuBackendCompilerFeedbackHarness` instead of invoking a real compiler:

```java
import net.sixik.ga_utils.javatogpu.runtime.validation.GpuBackendCompilerFeedbackHarness;
import net.sixik.ga_utils.javatogpu.runtime.validation.GpuBackendCompilerFeedbackHarnessReport;

GpuBackendCompilerFeedbackHarnessReport report = GpuBackendCompilerFeedbackHarness
        .loadWithBuiltIns()
        .runSyntheticOpenCl();

System.out.println(report.toMarkdown());
```

The examples app registers a small synthetic parser and includes a runnable check:

```powershell
.\gradlew.bat :examples-app:runCompilerFeedbackHarnessExample --console=plain
```

The harness runs the same registry, validates provider ordering and failure isolation, and returns the normal
`GpuBackendCompilerFeedbackReport` plus compact artifact fields.

## Runtime Artifacts

When artifact dumping is enabled, runtime and optimizer stages write properties-style evidence that can be used by CI, reports, and manual review.

| Artifact | Purpose |
| --- | --- |
| `runtime-device-selection.properties` | Selected device, ranked candidates, policy decisions, diagnostics. |
| `runtime-ir-analysis.properties` | Analysis-only IR evidence such as register-pressure estimates. |
| `backend-compiler-feedback.properties` | Parsed compiler feedback and selected resource metrics. |
| `runtime-equivalence.properties` | Pipeline-level reference/pre/post comparison evidence. |
| `runtime-production-mutation-safety.properties` | Fail-closed answer for whether optimized runtime IR may affect production code. |
| `optimizer-report.txt` | Human-readable optimizer diagnostics. |
| `runtime-optimizer-family-equivalence-payload.properties` | Per-family optimizer proof payload index. |

Analysis-only passes must be marked as analysis-only and cannot count as accepted optimizer proof. Missing proof payload components should remain explicit `not-recorded` artifacts instead of being hidden.

## Production Safety

Production-affecting extensions fail closed by design. A production optimizer, backend source switch, or lowerer mutation must not become active just because it exists on the classpath.

Before production mutation or source switching, the pipeline needs matching identity-bound evidence:

- accepted optimizer or source-promotion gate;
- accepted proof artifact;
- passed runtime equivalence;
- clean fallback and rollback state;
- declared rollback support;
- production promotion decision with mutation allowed;
- explicit operator or manifest acceptance bound to the exact backend, device, driver, kernel, profile, and artifact digest;
- activation token where controlled runtime activation is required.

Default runtime source switching and production mutation must remain disabled unless a reviewed activation path explicitly enables them for the exact identity being executed.

## Extension Rules

These rules apply to all public extension points:

- Use unique, stable extension ids and versions.
- Declare the correct phase, capability, and permission.
- Keep read-only hooks read-only.
- Prefer deterministic ordering through `extensionOrder()` instead of relying on classpath order.
- Report diagnostics and machine-readable fields when possible.
- Do not use exceptions for normal control flow.
- Fail closed for strict or production profiles.
- Treat advisory failures as isolated diagnostics when the pipeline allows it.

Registries reject blank metadata, whitespace/control characters in ids or versions, missing capabilities, null entries, duplicate ids, wrong phases, excessive permissions, and missing required capabilities before execution.

## Read Next

- [Runtime Guide](Runtime-Guide.md) - running kernels, runtime scopes, logging, and artifacts.
- [Method Tests](Method-Tests.md) - fixture-based `@GPUTest` checks.
- [IR Validation](IR-Validation.md) - IR validator concepts and reports.
- [IR Optimizer](IR-Optimizer.md) - optimizer profiles, journals, and dump files.
- [API Overview](API-Overview.md) - public annotations and facade overview.

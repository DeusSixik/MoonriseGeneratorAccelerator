# Method Tests

Method tests are small fixture-based checks attached to one `@GPU` method with `@GPUTest`.

They are meant to answer a practical question: "Can this generated GPU method accept these inputs and produce this expected output?" They also provide optional evidence for future backend/device selection.

Method tests are not a replacement for JUnit. Think of them as portable GPU-kernel fixtures that JavaToGpu can inspect, materialize, compare against a CPU reference, and optionally run through a backend.

## Fast Decision Guide

| Goal | Use |
| --- | --- |
| Check fixture files and parameter names | Metadata, fixture readiness, and value binding |
| Compare expected output without a GPU | Invocation materialization + CPU/reference comparison |
| Test a real backend/device explicitly | Optional GPU probe after a runtime scope is installed |
| Reuse probe evidence for placement experiments | Persistent probe cache and cache-only ranking |

The default workflow is manual today: examples and tests call the probe APIs explicitly. Runtime auto-gating from `@GPUTest` evidence is future/opt-in work, not a hidden first-launch behavior.

## Quick Start

Run the numeric example:

```powershell
.\gradlew.bat :examples-app:runMethodTestProbeExample --console=plain
```

Run the struct example:

```powershell
.\gradlew.bat :examples-app:runMethodTestStructProbeExample --console=plain
```

Run the cache-only backend/device ranking walkthrough:

```powershell
.\gradlew.bat :examples-app:runMethodTestProbeEvidenceRankingExample --console=plain
```

The first two examples do not require a real GPU. They inspect generated metadata, load JSON fixtures, create Java invocation arguments, and run a CPU-reference comparison. The ranking example also uses a synthetic backend so it can show persistent probe evidence without depending on local OpenCL hardware.

## What A Method Test Does

A method test has four normal stages:

1. **Metadata** - load `@GPUTest` entries from the generated `IrGpu` artifact.
2. **Fixture readiness** - find JSON fixture resources on the classpath and verify the top-level JSON shape.
3. **Value binding** - match fixture fields to kernel parameter names.
4. **Reference comparison** - materialize Java arguments and compare output values with a CPU/reference callback.

There is also an optional fifth stage:

5. **GPU probe** - run the materialized invocation through the currently installed runtime backend and compare the real GPU output.

The common safe path is stages 1-4. It is useful in tests and examples because it does not allocate GPU buffers, compile OpenCL, or require hardware.

## Anatomy Of `@GPUTest`

`@GPUTest` stores references and metadata. It does not store Java objects directly.

```java
@GPUTest(
        id = "scale-smoke",
        inputs = {"fixtures/scale-smoke.inputs.json"},
        expectedOutputs = {"fixtures/scale-smoke.outputs.json"},
        tolerance = "abs=1e-5",
        tags = {"selection", "smoke"},
        selectionProbe = true
)
```

| Field | What it means |
| --- | --- |
| `id` | Stable name for this case. Use short names such as `scale-smoke` or `vec2-scale-smoke`. |
| `inputs` | Classpath JSON resources containing input values. |
| `expectedOutputs` | Classpath JSON resources containing expected read-write output values. |
| `tolerance` | Optional numeric comparison tolerance, for example `abs=1e-5`. |
| `tags` | Free-form labels for grouping, reporting, or selection-probe intent. |
| `selectionProbe` | Whether this fixture may be used as backend/device placement evidence. Use `false` for cases that are too slow or too large. |

You can add more than one `@GPUTest` to the same method. Keep the first fixtures tiny and boring; bigger edge-case fixtures are better once the basic path is green.

## Numeric Example

Attach `@GPUTest` to a normal `@GPU` method:

```java
import net.sixik.ga_utils.javatogpu.api.GPU;
import net.sixik.ga_utils.javatogpu.api.annotations.GPUGlobal;
import net.sixik.ga_utils.javatogpu.api.annotations.GPUTest;

public final class ScaleKernel {

    @net.sixik.ga_utils.javatogpu.api.annotations.GPU
    @GPUTest(
            id = "scale-smoke",
            inputs = {"fixtures/method-test-probe/scale-smoke.inputs.json"},
            expectedOutputs = {"fixtures/method-test-probe/scale-smoke.outputs.json"},
            tolerance = "abs=1e-5",
            tags = {"selection", "smoke"}
    )
    public static void scaleKernel(
            @GPUGlobal float[] input,
            @GPUGlobal float[] output
    ) {
        int id = GPU.get_global_id(0);
        output[id] = input[id] * 2.0f;
    }
}
```

The input fixture is a JSON object. Field names must match method parameter names:

```json
{
  "input": [1.0, 2.0, 3.0, 4.0]
}
```

The expected-output fixture uses the read-write output parameter name:

```json
{
  "output": [2.0, 4.0, 6.0, 8.0]
}
```

Then inspect and compare the fixture at runtime:

```java
GpuKernelDescriptor descriptor = GpuGeneratedLauncherInvoker.descriptor(ScaleKernel.class, "scaleKernel");
ClassLoader classLoader = ScaleKernel.class.getClassLoader();

GpuRuntimeMethodTestProbePlan plan = GpuRuntimeMethodTestProbes.plan(descriptor, classLoader);
GpuRuntimeMethodTestFixtureReadiness readiness = GpuRuntimeMethodTestProbes.fixtureReadiness(plan, classLoader);
GpuRuntimeMethodTestFixtureValueBindingPlan bindings = GpuRuntimeMethodTestProbes.fixtureValueBindings(
        descriptor,
        plan,
        classLoader
);
GpuRuntimeMethodTestInvocationMaterializationPlan materialization =
        GpuRuntimeMethodTestProbes.fixtureInvocationMaterialization(descriptor, bindings);
GpuRuntimeMethodTestReferenceComparisonPlan referenceComparison = GpuRuntimeMethodTestProbes.compareWithReference(
        materialization,
        plan,
        invocationArguments -> {
            float[] input = (float[]) invocationArguments[0];
            float[] output = (float[]) invocationArguments[1];
            for (int index = 0; index < input.length; index++) {
                output[index] = input[index] * 2.0f;
            }
        }
);
```

Each plan has `toMarkdown()` and `artifactFields(...)` methods. Use `toMarkdown()` for humans and `artifactFields(...)` for reports, logs, or CI artifacts.

## Struct Example

Struct method tests use ordinary JSON objects. The object field names must match the Java `@GPUStruct` fields.

Define a struct type with a no-arg constructor and writable fields:

```java
import net.sixik.ga_utils.javatogpu.api.annotations.GPUStruct;

@GPUStruct
public final class Vec2 {
    public double x;
    public double y;

    public Vec2() {
    }
}
```

Attach `@GPUTest` to a kernel that uses `Vec2[]`:

```java
@net.sixik.ga_utils.javatogpu.api.annotations.GPU
@GPUTest(
        id = "vec2-scale-smoke",
        inputs = {"fixtures/method-test-struct-probe/vec2-scale-smoke.inputs.json"},
        expectedOutputs = {"fixtures/method-test-struct-probe/vec2-scale-smoke.outputs.json"},
        tolerance = "abs=1e-9",
        tags = {"selection", "struct", "smoke"}
)
public static void scaleStructKernel(
        @GPUGlobal Vec2[] input,
        float scale,
        @GPUGlobal Vec2[] output
) {
    int id = GPU.get_global_id(0);
    output[id].x = input[id].x * scale;
    output[id].y = input[id].y * scale;
}
```

The input fixture can contain both the struct array and scalar parameters:

```json
{
  "input": [
    { "x": 1.0, "y": 2.0 },
    { "x": 3.0, "y": 4.0 }
  ],
  "scale": 2.0
}
```

The expected-output fixture contains the read-write struct output:

```json
{
  "output": [
    { "x": 2.0, "y": 4.0 },
    { "x": 6.0, "y": 8.0 }
  ]
}
```

Reference comparison flattens struct fields into deterministic paths such as `[0].x`, `[0].y`, `[1].x`, and `[1].y`. This makes mismatch diagnostics readable instead of reporting an opaque binary struct blob.

## Fixture JSON Rules

Use these rules first. They keep fixtures easy to debug and easy to cache.

- The root JSON value must be an object.
- Top-level keys should match method parameter names.
- Input fixtures can include read-only, read-write, and scalar value parameters.
- Expected-output fixtures should include read-write output parameters.
- Numeric fixture values may be scalars or arrays: `1.0`, `[1.0, 2.0]`, `4`, `[1, 2, 3]`.
- Struct fixture values may be objects or arrays of objects.
- Struct fields may be primitive numeric values or nested `@GPUStruct` objects.
- Arrays inside `@GPUStruct` fields are not supported in the current OpenCL ABI.
- Strings, booleans, nulls, mixed arrays, and object shapes that do not match the Java parameter type are rejected.

Supported primitive fixture types today are:

```text
float, double, int, long, short, byte
float[], double[], int[], long[], short[], byte[]
@GPUStruct
@GPUStruct[]
```

For integer Java types, fixture numbers must be integral. For example, `3` is valid for `int`, while `3.5` is not.

## Choosing Tolerances

Use `tolerance` when floating-point output may differ slightly between CPU reference and GPU/runtime output.

Common forms:

```java
@GPUTest(tolerance = "abs=1e-5")
@GPUTest(tolerance = "rel=1e-4")
@GPUTest(tolerance = "abs=1e-5,rel=1e-4")
```

`abs` is an absolute tolerance. `rel` is relative to the expected value. If no tolerance is provided, comparison is exact.

## Running A Real GPU Probe

The CPU-reference preflight does not run the GPU. To run the same materialized fixtures through the currently installed runtime backend, call `executeGpuProbe(...)`:

```java
GpuRuntimeMethodTestGpuProbePlan gpuProbe = GpuRuntimeMethodTestProbes.executeGpuProbe(
        descriptor,
        materialization,
        plan,
        GpuRuntimeMethodTestGpuProbeOptions.cached()
);
```

Only use this after installing a backend, for example through an OpenCL runtime scope. Keep probe fixtures small because the GPU probe is intended for smoke validation and placement evidence, not full benchmarking.

```java
try (GpuScope ignored = JavaToGpu.useOpenClSharedCache()) {
    GpuRuntimeMethodTestGpuProbePlan gpuProbe = GpuRuntimeMethodTestProbes.executeGpuProbe(
            descriptor,
            materialization,
            plan,
            GpuRuntimeMethodTestGpuProbeOptions.cached()
    );
}
```

For backend/device ranking, warm evidence explicitly and then let selection consume the cache:

```java
GpuRuntimeMethodTestProbeEvidenceWarmup.warmSelectionProbeEvidence(
        descriptor,
        classLoader,
        candidates,
        GpuRuntimeMethodTestGpuProbeOptions.persistentCached(cacheDirectory)
);

GpuRuntimeCompileOptions rankingOptions =
        GpuRuntimeCompileOptions.defaults(GpuBackendTarget.OPENCL)
                .withPersistentMethodTestProbeEvidenceRanking(cacheDirectory, Duration.ofDays(7));
```

The ranking policy is cache-only. It does not secretly run probes during device selection. `maxEntryAge` has two effects:
expired entries become cache misses, and old-but-still-valid entries are down-weighted in backend/device score
diagnostics before they expire.

For application code on the built-in OpenCL runtime, the shortest high-level helper is `warmAndSelectOpenCl(...)`. It
keeps the same boundary, but returns one auditable report containing discovery, explicit warm-up, and follow-up
cache-only device selection:

```java
GpuRuntimeMethodTestProbeEvidenceSelectionPlan selection =
        GpuRuntimeMethodTestProbeEvidenceSelection.warmAndSelectOpenCl(
                descriptor,
                MyKernel.class.getClassLoader(),
                GpuRuntimeMethodTestGpuProbeOptions
                        .persistentCached(Path.of(".javatogpu/method-test-probes"))
                        .withCompileOptions(baseOptions),
                baseOptions,
                2
        );

System.out.println(selection.toMarkdown());
```

`warmAndSelectOpenCl(...)` discovers OpenCL devices, creates warm-up candidates with
`GpuRuntimeMethodTestProbeEvidenceWarmupCandidates.openClGpuDevices(...)`, runs only caller-approved warm-up probes, and
then performs cache-only device selection. Devices that were discovered but not warmed still appear as `missing` and
neutral, instead of pretending they were tested.

During that one-call flow the runtime emits lifecycle events for the selection boundary, OpenCL discovery, chosen
warm-up candidates, and final cache-only selection result. This is meant for optional journal/trace services: implement
`GpuRuntimeLifecycleService`, register it through `META-INF/services`, and the application can observe what happened
without switching to manual listener registration or letting a listener mutate placement.

`GpuRuntimeMethodTestProbeEvidenceWarmupCandidates.openClGpuDevices(...)` is the convenience path for the built-in
OpenCL runtime: it orders discovered devices by the policy ranking when available, skips CPU devices, caps the candidate
count, and creates owned OpenCL backends lazily for the explicit warm-up phase.

If you already have profiles from another discovery layer, use the lower-level `warmAndSelect(...)` overload that accepts
either a `GpuRuntimeDeviceDiscoveryResult` plus explicit warm-up candidates or a plain `List<GpuRuntimeDeviceProfile>`.

Warm-up candidates are pinned to their `deviceProfile.deviceId` while the probe runs. That means a real OpenCL warm-up
candidate records evidence for the same device that later appears in cache-only ranking, instead of accidentally using
whatever device the native runtime would have selected by default.

For a real OpenCL walkthrough, run:

```powershell
.\gradlew.bat :examples-app:runOpenClMethodTestProbeEvidenceSelectionExample --console=plain
```

The example discovers OpenCL devices, creates owned OpenCL backend candidates for discovered GPU devices, warms only
those explicit candidates, and then prints the cache-only selection report. Use
`-Pjavatogpu.methodTestProbeOpenClEvidenceCacheDir=...` for a persistent cache path and
`-Pjavatogpu.methodTestProbeOpenClWarmupLimit=1` when you want to warm only the first eligible GPU.
It also enables service-based lifecycle output by default: `runtime-lifecycle.jsonl` contains the full runtime event
journal, while `opencl-evidence-selection.trace` is a short human-readable trace. The console output filters that trace
to the `warmAndSelectOpenCl(...)` boundary so you can see discovery, candidate selection, and final placement without
reading the whole JSONL file. Override paths with `-Pjavatogpu.lifecycleJournalFile=...` and
`-Pjavatogpu.exampleLifecycleTraceFile=...`.

For the curated OpenCL release walkthrough, run:

```powershell
.\gradlew.bat :examples-app:runOpenClPracticalReleaseExample --console=plain
```

That walkthrough stitches together backend/device explanation, portable `@GPUTest` CPU-reference preflight, real OpenCL
probe evidence warm-up, cache-only placement, launch-shape guidance, vector/struct/packed-root-blob/image workload
smoke, image-helper guidance, optimizer artifact review guidance, and lifecycle trace output. Use
`-Pjavatogpu.practicalOpenClEvidenceCacheDir=...` to choose where the evidence cache and journals are written.
It also points to `OpenClImageWorkflow.rgbaIntToFloat2D(...)` as the short host-side path for the common 2D RGBA image
case.
The optimizer review section points to the separate `runOptimizationJournalExample` command and explains which
`original.backend.opencl-c`, `optimized.backend.opencl-c`, selected `backend.opencl-c`, and
`runtime-ir-optimizer-evidence.properties` files to inspect.

## Runtime Opt-In Modes

Runtime method-test integration is intentionally off by default. This keeps ordinary application startup predictable:
`@GPUTest` metadata can exist in generated artifacts without changing backend or device selection.

The first runtime integration mode is `CACHE_ONLY`:

```java
GpuRuntimeCompileOptions options = GpuRuntimeCompileOptions
        .defaults(GpuBackendTarget.OPENCL)
        .withMethodTestProbeMode(GpuRuntimeMethodTestProbeMode.CACHE_ONLY);
```

`CACHE_ONLY` means: use already-recorded selection-probe evidence if it is present, but never compile or execute a
probe during device selection. In most apps you will use the convenience method instead, because it also points the
runtime at the persistent cache directory:

```java
GpuRuntimeCompileOptions options = GpuRuntimeCompileOptions
        .defaults(GpuBackendTarget.OPENCL)
        .withPersistentMethodTestProbeEvidenceRanking(Path.of(".javatogpu/method-test-probes"));
```

This is enough for runtime selection to consume a warmed cache. Legacy `withMethodTestProbeEvidenceRankingCached()` and
`runtime.methodTestProbeEvidenceRanking=cached` still map to the same `CACHE_ONLY` behavior. Unknown mode values fail
closed instead of being ignored, so a future typo such as `run-before-first-invoke` will reject compile options with a
clear diagnostic rather than unexpectedly running or trusting probes.

Pass `withPersistentMethodTestProbeEvidenceRanking(path, maxEntryAge)` when stale evidence should lose score weight or
expire. Candidate diagnostics include freshness fields such as `freshnessPermille`, `ageLimited`, `oldestAgeMillis`, and
`maxAgeMillis`.

Automatic modes such as "run probe before first invoke" are deliberately future work. They need stronger guardrails for
latency, cache invalidation, backend ownership, and user consent.

## Common Blockers

Most failures are intentionally plain strings so they can be logged and stored in CI artifacts.

| Blocker | Meaning | Fix |
| --- | --- | --- |
| `fixture-resource-not-found` | A fixture path was not found on the classpath. | Put the JSON under resources and check the path in `@GPUTest`. |
| `fixture-payload-json-invalid` | The file is not valid JSON. | Validate the JSON and keep the root as an object. |
| `fixture-value-parameter-not-found` | No fixture field matched a method parameter. | Rename the JSON key or method parameter. |
| `fixture-value-shape-mismatch` | JSON shape does not match Java type. | Use scalar for scalar, array for array, object for struct, array of objects for struct array. |
| `fixture-value-java-type-unsupported` | The parameter type cannot be materialized by fixtures. | Use supported numeric types or `@GPUStruct` / `@GPUStruct[]`. |
| `fixture-value-integral-number-required` | A Java integer type received a fractional JSON number. | Use `3` instead of `3.5`. |
| `fixture-value-struct-field-missing` | A required struct field is absent in JSON. | Add the missing field with the same name as the Java field. |
| `fixture-value-struct-field-type-unsupported` | The struct contains a field fixtures cannot materialize. | Use primitive numeric fields or nested `@GPUStruct`; move arrays out to kernel parameters. |
| `fixture-reference-output-mismatch` | CPU/reference output differs from expected fixture output. | Check the expected JSON, reference callback, and tolerance. |

## Where To Look In The Repo

- Numeric example: `examples-app/src/main/java/net/sixik/ga_utils/examples/MethodTestProbeExample.java`
- Numeric fixtures: `examples-app/src/main/resources/fixtures/method-test-probe/`
- Struct example: `examples-app/src/main/java/net/sixik/ga_utils/examples/MethodTestStructProbeExample.java`
- Struct fixtures: `examples-app/src/main/resources/fixtures/method-test-struct-probe/`
- Ranking example: `examples-app/src/main/java/net/sixik/ga_utils/examples/MethodTestProbeEvidenceRankingExample.java`
- Real OpenCL ranking example: `examples-app/src/main/java/net/sixik/ga_utils/examples/OpenClMethodTestProbeEvidenceSelectionExample.java`
- Lower-level runtime details: [Runtime Guide](Runtime-Guide.md#method-test-vector-metadata-preview)

## Recommended Workflow

1. Start with a tiny fixture: two to four elements.
2. Run `fixtureReadiness(...)` and fix classpath/resource problems first.
3. Run `fixtureValueBindings(...)` and fix field names/types next.
4. Run `fixtureInvocationMaterialization(...)` and inspect argument kinds.
5. Add `compareWithReference(...)` and tune tolerance only when needed.
6. Add `executeGpuProbe(...)` only after the CPU-reference preflight is green.
7. Use persistent probe evidence only for explicit warm-up or CI/device-selection flows.

This keeps problems small and makes each failure point easy to understand.

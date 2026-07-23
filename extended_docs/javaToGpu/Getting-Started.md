# Getting Started

This guide gets you from an ordinary Java method to a GPU-backed OpenCL call. If you want the shortest possible path first, start with [User Quickstart](User-Quickstart.md).

## 1. Add JavaToGpu

Add JavaToGpu as both a dependency and an annotation processor:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.deussixik:javatogpu:0.1.0-alpha.4'
    annotationProcessor 'io.github.deussixik:javatogpu:0.1.0-alpha.4'
}
```

You need a JDK compatible with the project and a working OpenCL runtime for GPU execution. Many compiler tests can run without a GPU, but real kernel execution needs OpenCL drivers and hardware.

## 2. Write A Kernel

Mark a static method with `@GPU`. Use explicit GPU-facing parameter shapes such as `@GPUGlobal float[]`, and write results into output parameters.

```java
import net.sixik.ga_utils.javatogpu.api.GPU;
import net.sixik.ga_utils.javatogpu.api.annotations.GPUGlobal;
import net.sixik.ga_utils.javatogpu.api.annotations.GPUOptimize;
import net.sixik.ga_utils.javatogpu.api.annotations.GPUWorkGroupSize;
import net.sixik.ga_utils.javatogpu.api.annotations.GPUWorkGroupSizeHint;

public final class DemoKernel {

    @net.sixik.ga_utils.javatogpu.api.annotations.GPU
    @GPUWorkGroupSize(x = 64)
    @GPUWorkGroupSizeHint(x = 64)
    @GPUOptimize(fastMath = false)
    public static void transform(
            @GPUGlobal float[] input,
            @GPUGlobal float[] output
    ) {
        int id = GPU.get_global_id(0);
        output[id] = GPU.sin(input[id]) + 2.0f;
    }
}
```

Keep first kernels simple:

- `@GPU` entry methods return `void`.
- Use output arrays or supported output parameters for results.
- Use `GPU.*` for GPU builtins instead of arbitrary Java library calls.
- Use portable annotations such as `@GPUWorkGroupSize`, `@GPUWorkGroupSizeHint`, `@GPUVectorTypeHint`, `@GPUPacked`, `@GPUAligned`, `@GPUAlwaysInline`, and `@GPUOptimize` before raw backend attributes.
- Avoid object allocation, exceptions, recursion, virtual dispatch, and heap-heavy Java patterns inside kernels.

`@GPUOptimize(fastMath = false)` is the safe default. Use `fastMath = true` only when you explicitly allow proof-backed optimizer passes to use non-strict floating-point rewrites. The annotation can also record optional optimizer intent such as `profile`, `enabledFamilies`, `disabledFamilies`, `journal`, `dumpArtifacts`, `vendorAdaptation`, `vectorization`, and `resourceShaping`; family toggles gate optional optimizer-provider participation, while mutation and optimized-source selection remain fail-closed unless runtime evidence and selection gates allow more.

## 3. Run The Kernel

For a one-off call, install the OpenCL runtime scope around the generated launcher call:

```java
import net.sixik.ga_utils.javatogpu.api.GpuScope;
import net.sixik.ga_utils.javatogpu.api.JavaToGpu;

try (GpuScope ignored = JavaToGpu.useOpenCl()) {
    DemoKernel.transform(input, output);
}
```

For repeated calls, use the shared cache so the OpenCL session and compiled kernels stay warm:

```java
try (GpuScope ignored = JavaToGpu.useOpenClSharedCache()) {
    DemoKernel.transform(input, output);
    DemoKernel.transform(input, output);
} finally {
    JavaToGpu.shutdownOpenClSharedCache();
}
```

## 4. Validate Your Setup

Run the normal test suite:

```powershell
.\gradlew.bat :processor:test --console=plain
```

On a machine with OpenCL hardware and drivers, run the operational routine:

```powershell
.\gradlew.bat :processor:openClOperationalRoutine --rerun-tasks --console=plain
```

OpenCL validation reports are written under:

```text
processor/build/reports/opencl/
```

## 5. Optional: Add IR Validation

The optional IR validation module gives stricter compiler diagnostics and CI-friendly reports.

```groovy
dependencies {
    annotationProcessor 'io.github.deussixik:javatogpu-ir-validation:0.1.0-alpha.4'
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += '-Ajavatogpu.irValidation=diagnostic'
    options.compilerArgs += '-Ajavatogpu.irValidationDiagnostics=summary'
    options.compilerArgs += '-Ajavatogpu.irValidationReport=reports/javatogpu-ir-validation.properties'
}
```

Start with `diagnostic` mode. Move to strict modes only when you want builds to fail on unsupported or optimizer-unsafe IR shapes.

## Read Next

- [Cookbook](Cookbook.md) for copyable patterns.
- [Method Tests](Method-Tests.md) for fixture-based `@GPUTest` checks, including `@GPUStruct[]` examples.
- [Performance Basics](Performance-Basics.md) for cold compile, warm cache, launch overhead, and practical sizing.
- [Runtime Guide](Runtime-Guide.md) for launch sizes, fallback policies, compile options, and review-lane options.
- [OpenCL Data Model](OpenCL-Data-Model.md) for structs, vectors, pointers, images, and packed data.
- [Known Limitations](Known-Limitations.md) before relying on JavaToGpu in larger projects.

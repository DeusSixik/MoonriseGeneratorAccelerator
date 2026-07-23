# Cookbook

Small copyable patterns for common JavaToGpu tasks.

## Run A Kernel With Shared OpenCL Cache

Use this for repeated calls:

```java
try (GpuScope ignored = JavaToGpu.useOpenClSharedCache()) {
    DemoKernel.transform(input, output);
    DemoKernel.transform(input, output);
} finally {
    JavaToGpu.shutdownOpenClSharedCache();
}
```

## Run One Call With Backend/Device Preflight

Use this when you want the generated launcher to select and install the standard backend/device scope for a single call:

```java
DemoKernel_transform_GpuLauncher.invokeWithStandardBackendAndDevice(
        GpuRuntimeCompileOptions.defaults(GpuBackendTarget.OPENCL),
        input,
        output
);
```

This is scoped to that invocation. If you already installed a runtime scope, keep using the normal generated launcher overloads.

## Run With An Explicit Launch Size

Use this when buffer length is not the logical work size:

```java
GpuRuntime.invoke(
        JavaToGpu.launch1D(itemCount),
        descriptor,
        input,
        output
);
```

## Launch Dynamically By Owner Method

Use this in frameworks, examples, or test tools that know the owner class and method name only at runtime:

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

Keep the `GeneratedLauncher` handle for repeated calls. It reuses launcher resolution and metadata while still routing through the generated overloads.

## Return The First Output Value

Use this for scalar-style kernels that still follow the real `void + output buffer` GPU ABI. The generated helper exists only when the kernel has exactly one primitive read-write output array:

```java
GpuGeneratedLauncherInvoker.GeneratedLauncher launcher =
        GpuGeneratedLauncherInvoker.launcher(DemoKernel.class, "transform");

float first = launcher.invokeReturningFirstWithGlobalWorkSizeAs(
        Float.class,
        itemCount,
        input
);
```

Keep the handle around for repeated calls: it caches the generated launcher class, descriptor, and return-first metadata.
The launcher allocates the output array, runs the normal kernel path, and returns `output[0]`. Use an explicit output buffer when you need the full result array or multiple outputs.

If the helper is not generated, ask the generated launcher for a short reason:

```java
System.out.println(
        launcher.returnValueConvenience().summary()
);
```

Compile-time notes for skipped helpers can be disabled with `-Ajavatogpu.returnValueConvenienceDiagnostics=quiet`.

## Smoke-Test Reconstructed IrGpu Source

This is a review/smoke path. It does not enable production source switching.

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

## Inline Helper

Use `@CCode(inline = true)` for tiny helper functions:

```java
@CCode(inline = true)
static float lerp(float a, float b, float t) {
    return a + (b - a) * t;
}
```

## Mutable Helper Pointer

Use pointer wrappers for helper mutation patterns:

```java
import net.sixik.ga_utils.javatogpu.api.pointers.FloatPtr;

@CCode
static void setValue(FloatPtr ptr) {
    ptr.value = 42.0f;
}
```

## Packed Blob Read

Use address-space pointer views when you need packed binary layouts:

```java
import net.sixik.ga_utils.javatogpu.api.GPU;
import net.sixik.ga_utils.javatogpu.api.pointers.global.GlobalBytePtr;

GlobalBytePtr root = GPU.global(blob);
int value = root.add(view.offset + id * 4).asIntPtr().value;
```

## Struct Value

Use `@GPUStruct` for simple ABI-marshalled values:

```java
@GPUStruct
static final class Point {
    public float x;
    public float y;
}
```

## Runtime Fallback Flow

Use `trySelect(...)` when CPU fallback or graceful skipping matters:

```java
GpuRuntimeSelectionResult result = GpuRuntime.trySelect(policy);
if (result.matched()) {
    try (GpuRuntimeScope ignored = result.install()) {
        DemoKernel.transform(input, output);
    }
} else {
    cpuFallback(input, output);
}
```

## Image Workflow

Create OpenCL image resources through the runtime backend and pass wrappers to the generated kernel:

```java
import net.sixik.ga_utils.javatogpu.api.images.Image2DReadOnly;
import net.sixik.ga_utils.javatogpu.api.images.Image2DWriteOnly;
import net.sixik.ga_utils.javatogpu.api.images.Sampler;

try (OpenClGpuRuntimeBackend backend = new OpenClGpuRuntimeBackend();
     Image2DReadOnly input = backend.createReadOnlyRgbaIntImage(2, 1, pixels);
     Image2DWriteOnly output = backend.createWriteOnlyRgbaFloatImage(2, 1);
     Sampler sampler = backend.createNearestClampToEdgeSampler()) {
    ImageKernel.run(input, output, sampler, sums);
}
```

For the common 2D RGBA signed-int input to RGBA float output path, use the helper workflow so the input image, output
image, sampler, shape validation, capability check, readback, and cleanup stay together:

```java
try (OpenClImageWorkflow.RgbaIntToFloat2D images = OpenClImageWorkflow.rgbaIntToFloat2D(
        backend,
        width,
        height,
        rgbaPixels
)) {
    GpuExecutionConfig config = images.executionConfig();
    ImageKernel_run_GpuLauncher.invokeWithConfig(config, images.input(), images.output(), images.sampler(), sums);
    float[] rgbaOutput = images.readOutputRgbaFloat();
}
```

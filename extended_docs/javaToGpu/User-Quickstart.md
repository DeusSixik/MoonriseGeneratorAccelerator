# User Quickstart

This is the shortest path to a working JavaToGpu kernel. It intentionally avoids backend SPI, CUDA staging, optimizer evidence, and CI gates.

Use this page when you want to answer one question: "Can I write a Java GPU kernel and get an output array back?"

## 1. Add The Dependency

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

You also need a working OpenCL runtime and driver for real GPU execution.

## 2. Write One Kernel

Start with arrays and a `void` method. Write results into an output array.

```java
import net.sixik.ga_utils.javatogpu.api.GPU;
import net.sixik.ga_utils.javatogpu.api.annotations.GPUGlobal;

public final class AddKernel {
    @net.sixik.ga_utils.javatogpu.api.annotations.GPU
    public static void add(
            @GPUGlobal float[] a,
            @GPUGlobal float[] b,
            @GPUGlobal float[] out
    ) {
        int id = GPU.get_global_id(0);
        out[id] = a[id] + b[id];
    }
}
```

Keep first kernels simple:

- use arrays, scalars, supported vectors, or supported `@GPUStruct` values;
- return `void` and write results into output parameters;
- use `GPU.*` helpers for GPU builtins;
- avoid allocation, exceptions, recursion, virtual dispatch, monitors, and heap object graphs inside kernels.

## 3. Run It

Use `JavaToGpu.useOpenClSharedCache()` for repeated calls. It keeps the OpenCL session and compiled kernels warm.

```java
import net.sixik.ga_utils.javatogpu.api.GpuScope;
import net.sixik.ga_utils.javatogpu.api.JavaToGpu;

float[] a = new float[] {1.0f, 2.0f, 3.0f, 4.0f};
float[] b = new float[] {10.0f, 20.0f, 30.0f, 40.0f};
float[] out = new float[a.length];

try (GpuScope ignored = JavaToGpu.useOpenClSharedCache()) {
    AddKernel.add(a, b, out);
} finally {
    JavaToGpu.shutdownOpenClSharedCache();
}
```

After the call, `out` should contain the kernel result.

## 4. If It Fails

Start with these checks:

- OpenCL driver/runtime is installed and visible to the process.
- The method is `static`, marked with `@GPU`, and returns `void`.
- Output data is written through supported parameters such as `@GPUGlobal` arrays.
- Kernel code stays inside the supported Java subset.
- Arrays are non-empty and launch sizing matches the data shape.

For detailed fixes, use [Troubleshooting](Troubleshooting.md) and [Known Limitations](Known-Limitations.md).

## 5. What To Read Next

- [Getting Started](Getting-Started.md) - the longer first guide.
- [Cookbook](Cookbook.md) - copyable patterns for common kernels.
- [OpenCL Data Model](OpenCL-Data-Model.md) - arrays, structs, vectors, pointers, and images.
- [Method Tests](Method-Tests.md) - optional fixture-based `@GPUTest` checks.
- [Performance Basics](Performance-Basics.md) - when GPU execution is worth it and how warm cache changes results.
- [Runtime Guide](Runtime-Guide.md) - launch sizes, runtime scopes, logging, and artifacts.

## Current Alpha Boundary

OpenCL is the active runtime path today. CUDA is an advanced staged preview, not production execution. Optimizer mutation is opt-in and fail-closed by default.

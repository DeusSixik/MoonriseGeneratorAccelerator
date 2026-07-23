# OpenCL Data Model

This page explains the data shapes you can pass between Java and GPU kernels today.

For a first kernel, prefer simple annotated arrays. Move to structs, vectors, pointer views, packed blobs, and images only when the data layout really needs them.

## Start With Arrays

Most kernels should start with array parameters:

```java
@GPU
public static void scale(
        @GPUGlobal float[] input,
        @GPUGlobal float[] output,
        float factor
) {
    int id = GPU.get_global_id(0);
    output[id] = input[id] * factor;
}
```

Common address-space annotations:

- `@GPUGlobal` for normal input/output buffers.
- `@GPUConstant` for read-only constant buffers.
- `@GPULocal` for local/shared OpenCL memory patterns.

## Scalars

Scalar parameters are useful for sizes, factors, flags, and small constants.

Common scalar families include Java primitives such as `int`, `long`, `float`, `double`, and supported unsigned aliases such as `UInt` or `ULong`.

Unsigned aliases are grouped by primitive family, for example `UInt` lives in `net.sixik.ga_utils.javatogpu.api.types.integers` and `ULong` lives in `net.sixik.ga_utils.javatogpu.api.types.longs`.

## Vectors

Use vector wrappers when each work item naturally works with a small fixed-width value:

```java
import net.sixik.ga_utils.javatogpu.api.types.floats.Float4;

Float4 color = new Float4(r, g, b, a);
```

Typical families include:

- `Float2`, `Float3`, `Float4`
- `Int2`, `Int3`, `Int4`
- `UInt2`, `UInt3`, `UInt4`
- `Double2`, `Double3`, `Double4`

Vectors can be used as local values, helper parameters, helper returns, kernel parameters, and buffer element types where supported.

Vector wrappers are grouped by primitive family:

- `net.sixik.ga_utils.javatogpu.api.types.bytes` for `Byte*` and `UByte*`.
- `net.sixik.ga_utils.javatogpu.api.types.shorts` for `Short*` and `UShort*`.
- `net.sixik.ga_utils.javatogpu.api.types.integers` for `Int*` and `UInt*`.
- `net.sixik.ga_utils.javatogpu.api.types.longs` for `Long*` and `ULong*`.
- `net.sixik.ga_utils.javatogpu.api.types.floats` for `Float*`.
- `net.sixik.ga_utils.javatogpu.api.types.doubles` for `Double*`.

## Structs

Use `@GPUStruct` for small value objects with explicit GPU-compatible fields:

```java
@GPUStruct
public static final class Point {
    public float x;
    public float y;
}
```

Supported struct field categories:

- Primitive scalar fields.
- Vector fields.
- Nested `@GPUStruct` fields.

Arrays inside struct fields are not supported in the current alpha. Pass arrays as separate kernel parameters or use a packed-buffer layout.

## Pointer Wrappers

Pointer wrappers are useful for helper mutation patterns:

```java
import net.sixik.ga_utils.javatogpu.api.pointers.FloatPtr;

@CCode
static void writeAnswer(FloatPtr value) {
    value.value = 42.0f;
}
```

Use them when a helper needs pointer-like behavior. For ordinary kernels, arrays are usually easier to read and maintain.

Pointer wrappers are grouped by address-space:

- `net.sixik.ga_utils.javatogpu.api.pointers` for private scalar-by-reference wrappers such as `FloatPtr` and `IntPtr`.
- `net.sixik.ga_utils.javatogpu.api.pointers.global` for `__global` packed-buffer views.
- `net.sixik.ga_utils.javatogpu.api.pointers.constant` for read-only `__constant` packed-buffer views.
- `net.sixik.ga_utils.javatogpu.api.pointers.local` for `__local` memory views.

## Packed Blob Views

Packed blobs are useful when you already have a binary layout or want multiple logical views over one `byte[]` buffer.

Typical shape:

- A root `@GPUGlobal byte[]` buffer.
- A small `@GPUStruct` that stores offsets or layout metadata.
- Address-space pointer views for typed reads.

Example:

```java
import net.sixik.ga_utils.javatogpu.api.GPU;
import net.sixik.ga_utils.javatogpu.api.pointers.global.GlobalBytePtr;

GlobalBytePtr root = GPU.global(blob);
int value = root.add(view.offset + id * 4).asIntPtr().value;
```

Keep packed layouts documented on the Java side. They are powerful, but easier to misuse than typed arrays.

## Constant Data

JavaToGpu supports constant data for lookup tables and static read-only data.

Current forms:

- `@GPUConstantData` for embedded constant tables.
- `@GPUExternConstantData` for external constant declarations.

The current alpha support focuses on primitive scalar arrays.

## Images And Samplers

Use image wrappers when you need OpenCL image memory rather than plain buffers.

Image and sampler wrappers live in `net.sixik.ga_utils.javatogpu.api.images`, for example `Image2DReadOnly`, `Image2DWriteOnly`, and `Sampler`.

Typical image use cases:

- Read-only image parameters.
- Write-only image outputs.
- Sampler-based reads.
- Pixel/channel operations through `GPU.*` helpers.

Image support is still alpha-level, so validate on the target GPU and driver before relying on a specific image format in production-like tests.

For the common 2D RGBA signed-int input to RGBA float output workflow, `OpenClImageWorkflow.rgbaIntToFloat2D(...)`
bundles the host-side input image, output image, nearest clamp-to-edge sampler, readback helper, shape validation, and
cleanup into one try-with-resources object. The workflow also exposes `pixelCount()`, `rgbaElementCount()`, `summary()`,
and `executionConfig()` for one-work-item-per-pixel 2D kernels, so examples and diagnostics do not need to duplicate the
`width * height * 4` math. Use the lower-level `createReadOnly...`, `createWriteOnly...`, and `read...` methods when you
need a less common image family or custom sampler behavior.

## Choosing A Data Shape

- Use annotated arrays for most numeric workloads.
- Use vectors for fixed-width lane data.
- Use `@GPUStruct` for small records with explicit fields.
- Use pointer views for packed or low-level memory layouts.
- Use images only when OpenCL image semantics are actually needed.

## Read Next

- [Language Contract](Language-Contract.md)
- [Cookbook](Cookbook.md)
- [Runtime Guide](Runtime-Guide.md)
- [Known Limitations](Known-Limitations.md)

# Language Contract

JavaToGpu works best when GPU code is written as small, explicit kernels. This page describes the Java shapes that are intended to work today.

Use this as a practical checklist while writing `@GPU` methods. For a shorter first example, start with [Getting Started](Getting-Started.md).

## Good Kernel Shape

A good first kernel usually looks like this:

```java
@GPU
public static void transform(@GPUGlobal float[] input, @GPUGlobal float[] output) {
    int id = GPU.get_global_id(0);
    output[id] = GPU.sqrt(input[id]);
}
```

Keep these rules in mind:

- `@GPU` entry methods are `static` and return `void`.
- Results are written into output arrays or other supported output parameters.
- Array parameters should declare their address space with annotations such as `@GPUGlobal`, `@GPUConstant`, or `@GPULocal`.
- GPU builtins should come from `GPU.*`, not from arbitrary Java library calls.
- Kernels should avoid allocation, exceptions, recursion, virtual dispatch, and heap-heavy Java patterns.

## Entry Points

Supported `@GPU` entry-point parameters include:

- Primitive scalar values such as `int`, `long`, `float`, and `double`.
- Annotated arrays such as `@GPUGlobal float[] output`.
- Vector wrappers such as `Float2`, `Float3`, `Float4`, `Int4`, and related families.
- `@GPUStruct` value types and struct arrays where supported by the current data model.
- Pointer/view wrappers for low-level packed-buffer workflows.
- Image and sampler wrappers for OpenCL image workflows.

Entry methods should write outputs through parameters instead of returning a value.

## Control Flow

The common structured control-flow forms are supported:

- `if` / `else`
- `for`
- `while`
- `do while`
- `switch`
- `break`
- `continue`

Prefer simple loops and clear bounds. If a loop is hard for a human to reason about, it will usually be harder to validate and optimize later.

## Expressions

Supported expression patterns include:

- Arithmetic, comparison, logical, and bitwise operators.
- Scalar casts between supported scalar types.
- Ternary expressions.
- Array reads and writes.
- Struct and vector field access.
- Calls to `GPU.*` builtins.
- Calls to `@CCode` helpers.
- Calls to explicit `@GPUIntrinsic` helpers.

For math, prefer `GPU.sin(...)`, `GPU.sqrt(...)`, `GPU.pow(...)`, and related helpers so the generated backend code is predictable.

## Types

Common supported type families include:

- Java primitive scalars used by the current subset.
- Unsigned aliases such as `UByte`, `UShort`, `UInt`, and `ULong`.
- Vector wrappers such as `Float4` and `Int4`.
- Pointer wrappers such as `FloatPtr` and `DoublePtr` for helper mutation patterns.
- Address-space views such as `GlobalBytePtr` for packed-buffer access.
- `@GPUStruct` values for small ABI-safe records.

Use the grouped API packages for imports: `api.types.*` for scalar aliases/vectors, `api.pointers.*` for pointer wrappers, and `api.images` for image/sampler wrappers. The root `api` package is reserved for the main facade classes such as `GPU`, `JavaToGpu`, and `GpuScope`.

See [OpenCL Data Model](OpenCL-Data-Model.md) for examples of structs, vectors, pointers, packed blobs, and images.

## Helpers

Use `@CCode` for reusable helper methods that should become GPU helper code:

```java
@CCode(inline = true)
static float lerp(float a, float b, float t) {
    return a + (b - a) * t;
}
```

Keep helpers GPU-friendly too. They should use supported parameter/return types and avoid normal Java object behavior.

## Unsupported Java Shapes

These patterns are intentionally outside the current alpha subset:

- Non-`void` `@GPU` entry methods.
- General object allocation inside kernels.
- Arbitrary Java library calls inside kernels.
- Virtual dispatch, interface dispatch, and dynamic method dispatch.
- Exceptions and `try` / `catch` logic.
- Recursion.
- Monitors and synchronization blocks.
- General object arrays.
- Arrays inside `@GPUStruct` fields.
- General union-style source authoring.

When you need one of these patterns, usually the best path is to move the complex Java logic to the CPU side and pass a simpler data shape into the GPU kernel.

## Practical Advice

- Start with one kernel and a CPU reference test.
- Add one GPU feature at a time.
- Keep memory layout explicit.
- Prefer arrays and small structs over object graphs.
- Run validation on tiny input sizes before benchmarking larger workloads.

## Read Next

- [Getting Started](Getting-Started.md)
- [OpenCL Data Model](OpenCL-Data-Model.md)
- [Cookbook](Cookbook.md)
- [Known Limitations](Known-Limitations.md)
- [Troubleshooting](Troubleshooting.md)

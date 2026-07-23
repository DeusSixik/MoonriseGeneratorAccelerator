# JavaToGpu Documentation

JavaToGpu lets you write a restricted Java method, mark it as a GPU kernel, and run it through the OpenCL runtime.

This documentation is written for users of the current alpha. It starts with practical setup and examples, then separates deeper runtime, extension, and maintainer material so the first OpenCL run is not blocked by internal contracts.

## What You Can Do Today

- Write `@GPU` Java methods that operate on arrays, scalars, vectors, structs, pointers, images, and samplers.
- Use `GPU.*` builtins for OpenCL-style indexing, math, images, barriers, atomics, and low-level helper operations.
- Run generated kernels through `JavaToGpu.useOpenCl()` or `JavaToGpu.useOpenClSharedCache()`.
- Pass compile options and explicit launch sizes when you need lower-level control.
- Enable optional IR validation for stricter compiler diagnostics and CI reports.
- Smoke-test reconstructed `IrGpu` source through the opt-in review lane without changing the production default path.

## Start Here

1. [User Quickstart](User-Quickstart.md) - the shortest path from dependency setup to one OpenCL-backed output array.
2. [Getting Started](Getting-Started.md) - install the dependency, write a first kernel, and run it with more context.
3. [Cookbook](Cookbook.md) - copy small patterns for common tasks.
4. [Troubleshooting](Troubleshooting.md) - map first-run failures to short fixes.
5. [Performance Basics](Performance-Basics.md) - understand cold compile, warm cache, launch overhead, and when GPU execution pays off.
6. [Method Tests](Method-Tests.md) - add fixture-based `@GPUTest` checks for numeric and `@GPUStruct` kernels.
7. [Runtime Guide](Runtime-Guide.md) - choose runtime scopes, launch sizes, compile options, and review-lane options.
8. [API Overview](API-Overview.md) - learn the public packages and the most-used types.
9. [Known Limitations](Known-Limitations.md) - understand the alpha boundaries before using it seriously.

## Choose Your Path

| If you want to... | Read these first | Skip for now |
| --- | --- | --- |
| Run one kernel | [Quickstart](User-Quickstart.md), [Getting Started](Getting-Started.md), [Troubleshooting](Troubleshooting.md) | Backend SPI, optimizer proof, promotion gates |
| Write real kernels | [Language Contract](Language-Contract.md), [OpenCL Data Model](OpenCL-Data-Model.md), [Cookbook](Cookbook.md) | ASM and backend adapter docs |
| Add confidence tests | [Method Tests](Method-Tests.md), [IR Validation](IR-Validation.md) | Production promotion internals |
| Tune runtime behavior | [Runtime Guide](Runtime-Guide.md), [Performance Basics](Performance-Basics.md), [API Overview](API-Overview.md) | CUDA staging details unless you are testing them |
| Extend JavaToGpu | [Public API And Extension Contract](Public-API-And-Extension-Contract.md), [Backend Adapter Authoring](Backend-Adapter-Authoring.md) | User quickstart repetition |
| Release or validate hardware | [Validation and Operations](Validation-and-Operations.md), [Device Quirks](Device-Quirks.md), [Publishing Guide](Publishing.md) | First-run tutorials |

## Data And Runtime

- [Runtime Guide](Runtime-Guide.md) - runtime scopes, launch sizes, logging, artifacts, and advanced options.
- [Method Tests](Method-Tests.md) - fixture-based method checks and future placement evidence.
- [OpenCL Data Model](OpenCL-Data-Model.md) - arrays, structs, vectors, pointers, packed blobs, and images.
- [Language Contract](Language-Contract.md) - what Java shapes are supported inside GPU kernels.
- [FAQ](FAQ.md) - short answers to common user questions.

## Advanced Extensions

- [IR Validation](IR-Validation.md) - optional strict IR checks and read-only optimizer diagnostics.
- [IR Optimizer](IR-Optimizer.md) - optional backend-neutral optimizer layer plus separate vendor-provider SPI.
- [Backend Adapter Authoring](Backend-Adapter-Authoring.md) - add or preview backend families through the shared provider/SPI path.
- [Public API And Extension Contract](Public-API-And-Extension-Contract.md) - extension services and compatibility boundaries.
- [Diagnostics Reference](Diagnostics-Reference.md) - diagnostic categories and how to interpret them.

## Maintainer And Operations

- [Validation and Operations](Validation-and-Operations.md) - local validation routines and OpenCL evidence artifacts.
- [ASM Contract](ASM-Contract.md) - bytecode input guidance for advanced integrations.
- [OpenCL Runner Contract](OpenCL-Runner-Contract.md) - self-hosted runner expectations for vendor validation.
- [Device Quirks](Device-Quirks.md) - hardware/runtime-specific notes discovered from validation.

## Current Alpha Position

JavaToGpu is a public alpha / developer preview.

NVIDIA OpenCL and AMD OpenCL now have green real-hardware validation lanes. Intel validation is still pending. Treat generated code shape and runtime APIs as evolving until beta.

## Maintainer Notes

Planning notes and historical implementation details are kept outside this user manual.

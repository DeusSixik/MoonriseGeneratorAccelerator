# OpenCL Runner Contract

This page describes the expected contract for future Intel, NVIDIA, and AMD validation runners.

## Required Machine Traits

- Windows host
- working OpenCL ICD/runtime
- Java 17
- enough disk space for Gradle caches and OpenCL report artifacts

## Expected Labels

- `self-hosted`
- `Windows`
- `X64`
- vendor-specific label such as `intel`, `nvidia`, or `amd`
- device-specific label for CI matrix routing, for example `rtx3060`, `rtx5070`, or `rx7800xt`

The current GitHub workflow routes NVIDIA RTX 3060 machines with:

```text
self-hosted, Windows, X64, nvidia, rtx3060
```

## Expected Task Set

The runner should be able to execute:

1. `:processor:openClVendorValidation`
2. `:processor:integrationOpenClSmokeTest`
3. `:processor:openClWorkloadValidationTest`
4. `:processor:openClLongRunningStabilityTest`
5. `:processor:benchmarkTest`
6. `:processor:openClValidationReport`

or the combined routine:

- `:processor:openClOperationalRoutine`

## Expected Artifact Bundle

Upload the full `processor/build/reports/opencl/` directory.

## Failure Recording Rule

If a lane fails:

- keep the artifact bundle
- record the failing bucket
- capture device/vendor/driver/runtime strings
- add confirmed vendor-specific deviations to the quirks registry

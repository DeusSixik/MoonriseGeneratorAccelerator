# Device Quirks

This page tracks confirmed vendor-specific or driver-specific OpenCL behavior that affects JavaToGpu.

Only add an entry after a problem is reproduced on real hardware. If a failure is still being investigated, keep it in the validation report or issue tracker until the device-specific cause is clear.

## Current Status

No confirmed public vendor-specific quirks are recorded yet.

The current green validation baseline includes NVIDIA RTX 3060, NVIDIA RTX 5070, and AMD RX 7800 XT. Intel validation should be recorded here once a runner produces repeatable results or confirmed issues.

## Clean Baselines

Use this section for device stacks that have passed the operational routine without confirmed vendor-specific issues.

### NVIDIA OpenCL / RTX 3060

- Status: clean baseline.
- Device: NVIDIA GeForce RTX 3060.
- Platform: NVIDIA CUDA OpenCL.
- Validated command: `:processor:openClOperationalRoutine --rerun-tasks --console=plain`.
- Covered areas: compile, runtime, ABI, images, local memory, workloads, stress, and validation report generation.
- Result: no confirmed vendor-specific issue recorded.
- Main report: `processor/build/reports/opencl/validation-report.md`.

### NVIDIA OpenCL / RTX 5070

- Status: clean baseline.
- Device: NVIDIA GeForce RTX 5070.
- Platform: NVIDIA CUDA OpenCL.
- Validated command: `:processor:openClOperationalRoutine --rerun-tasks --console=plain`.
- Covered areas: compile, runtime, ABI, images, local memory, workloads, stress, and validation report generation.
- Result: no confirmed vendor-specific issue recorded.
- Main report: `processor/build/reports/opencl/validation-report.md`.

### AMD OpenCL / RX 7800 XT

- Status: clean baseline.
- Device: AMD RX 7800 XT / `gfx1101`.
- Platform: AMD Accelerated Parallel Processing OpenCL.
- Validated command: `:processor:openClOperationalRoutine --rerun-tasks --console=plain`.
- Covered areas: compile, runtime, ABI, images, local memory, workloads, stress, and validation report generation.
- Result: no confirmed vendor-specific issue recorded.
- Main report: `processor/build/reports/opencl/validation-report.md`.

## Pending Coverage

### Intel OpenCL

- Status: pending broader validation.
- Required evidence: full operational routine on a real Intel OpenCL runner.
- Add entries here only for failures that reproduce and appear Intel-specific.

## How To Add A Quirk

Use one clear entry per issue.

Template:

```text
## <Vendor> / <Device> / <Short Issue Name>

- Status: open | mitigated | driver-fixed
- Vendor:
- Device:
- Driver / runtime:
- Platform:
- Affected validation bucket:
- Symptom:
- Minimal reproducer:
- Workaround:
- First confirmed in:
- Last checked in:
- Matching validation artifact:
```

Good entries should answer:

- What device and driver reproduced the issue?
- Which validation bucket or kernel failed?
- Does the same kernel pass on another vendor stack?
- Is there a workaround in JavaToGpu or does it need a driver update?
- Which report artifact proves the behavior?

## Recording Rules

- Do not add generic "works on my machine" notes as quirks.
- Do not mark an issue vendor-specific until it is reproduced and isolated.
- Keep the original history if a driver update fixes the issue; change status to `driver-fixed`.
- Prefer report artifacts and minimal repro details over screenshots.
- Link related CI or validation reports when possible.

## Read Next

- [Validation and Operations](Validation-and-Operations.md)
- [Troubleshooting](Troubleshooting.md)
- [OpenCL Runner Contract](OpenCL-Runner-Contract.md)

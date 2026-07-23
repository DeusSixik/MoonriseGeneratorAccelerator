# Alpha Release Checklist

Use this checklist before publishing a public alpha build.

## Release Label

Recommended current public alpha version:

```text
v0.1.0-alpha.4
```

Recommended positioning:

```text
Public alpha / developer preview for experimental Java-to-OpenCL kernels.
```

Avoid calling the project stable or production-ready. NVIDIA and AMD OpenCL validation are green on real hardware, but Intel validation is still pending.

## Must Be True

- `README.md` clearly says this is an alpha/developer preview.
- `docs/` explains the supported subset, runtime setup, validation flow, and known limitations.
- The normal processor test suite is green.
- The optional `ir-validation` module test suite is green when releasing `javatogpu-ir-validation`.
- The OpenCL operational routine is green on the currently available hardware.
- The latest OpenCL validation report is generated and can be referenced in release notes.
- `validateMavenCentralReleaseReadiness` passes for every artifact being published.
- Maven local staging has been generated for every published artifact: `javatogpu`, `javatogpu-ir-validation`, `javatogpu-ir-optimizer`, and `javatogpu-ir-vendor-optimizer`.
- Known unsupported features are documented instead of implied to work.
- Public docs do not include private planning-only notes as user-facing promises.

## Recommended Validation Command

```powershell
.\gradlew.bat :processor:openClOperationalRoutine --rerun-tasks --console=plain
```

Expected artifact folder:

```text
processor/build/reports/opencl/
```

Key files:

- `validation-report.md`
- `validation-history.md`
- `bucket-status.properties`
- `workload-summary.properties`
- `long-running-summary.properties`

## Release Notes Template

```markdown
# JavaToGpu v0.1.0-alpha.4

This is a public alpha of JavaToGpu, an experimental Java-to-OpenCL compiler and runtime for GPU-safe Java kernels.

## Validated Environment

- Backend: OpenCL
- Current proven devices: NVIDIA GeForce RTX 3060, NVIDIA GeForce RTX 5070, and AMD RX 7800 XT / `gfx1101`
- NVIDIA status: operationally validated on RTX 3060 and RTX 5070 lanes
- AMD status: operationally validated on RX 7800 XT lane
- Cross-vendor status: Intel validation pending

## Highlights

- Java source kernels through `@GPU`
- OpenCL runtime dispatch through `JavaToGpu` and generated launchers
- Practical `GPU.*` builtin surface
- Struct, vector, pointer, image, sampler, and packed-data support
- Operational validation report bundle

## Known Limitations

- Not arbitrary Java-to-GPU execution
- No stable API guarantee before beta
- CUDA is staged/experimental and not a production user backend
- Structured ASM frontend expects canonical supported bytecode
```

## Good Alpha Promise

The right promise for this stage is: useful for experiments, compiler integrations, GPU-kernel prototyping, and feedback from early adopters.

The current NVIDIA and AMD OpenCL paths are operationally validated for repo-local alpha confidence, but this is not a production-ready claim and Intel validation remains pending.

The wrong promise is: automatic acceleration for arbitrary Java applications.

# Validation And Operations

This page explains how to validate JavaToGpu locally, how to capture useful evidence, and what the current alpha validation status means.

## Who This Is For

| Reader | Start here |
| --- | --- |
| Normal contributor | `:processor:test` and the first part of this page |
| GPU-machine owner | `:processor:openClOperationalRoutine` and report capture |
| CI maintainer | bucket status, validation history, and runner contracts |
| Release maintainer | Practical Release Checklist and Publishing Guide |

For first-run application failures, use [Troubleshooting](Troubleshooting.md) before opening the full validation artifact list.

## What To Run First

For normal development, run the processor tests:

```powershell
.\gradlew.bat :processor:test --console=plain
```

For real OpenCL validation on a GPU machine, run the operational routine:

```powershell
.\gradlew.bat :processor:openClOperationalRoutine --rerun-tasks --console=plain
```

The OpenCL reports are written under:

```text
processor/build/reports/opencl/
```

Start with `validation-report.md`. Keep the `.properties` files when debugging CI or comparing runs.

## Current Alpha Position

The current validated OpenCL baseline covers NVIDIA and AMD hardware.

This means:

- NVIDIA RTX 3060 and RTX 5070 validation lanes are green.
- AMD RX 7800 XT validation is green.
- Intel should still be validated on real hardware before making broad cross-vendor claims.
- A green local validation run proves the tested commit, machine, driver, and backend, not universal OpenCL behavior.
- The RTX 5070 lane has a production-ready OpenCL promotion checkpoint for approval `approval:rtx5070-75081921-20260711`, bound to candidate SHA `75081921bd9f3a85337172e93ce7b4629437e89f`, with manifest validation, activation gate, activation-token smoke, activation-token negative controls, source parity, runtime equivalence, and the production readiness checklist all passing.

## Main Validation Buckets

The operational routine combines focused buckets for compile, runtime, ABI, images, local memory, stress, workloads, and report generation.

Important buckets include:

- `:processor:benchmarkTest`
- `:processor:integrationOpenClSmokeTest`
- `:processor:openClLongRunningStabilityTest`
- `:processor:compileOnlyTest`
- `:processor:imageOpenClTest`
- `:processor:localMemoryTest`
- `:processor:runtimeOpenClTest`
- `:processor:structAbiTest`
- `:processor:openClVendorValidation`
- `:processor:openClWorkloadValidationTest`
- `:processor:openClBackendSourcePromotionCandidateGate`
- `:processor:writeOpenClBackendSourcePromotionManifestTemplate`
- `:processor:validateOpenClBackendSourcePromotionManifest`
- `:processor:validateOpenClBackendSourcePromotionActivationGate`
- `:processor:openClProductionActivationTokenSmokeTest`
- `:processor:openClProductionActivationTokenNegativeTest`
- `:processor:openClOptimizerFamilyPayloadFixtureTest`
- `:processor:openClValidationReport`
- `:processor:validateOpenClRuntimeIrOptimizerEvidence`

You usually do not need to run buckets one by one unless you are narrowing down a failure.

## Reports To Keep

When a validation run fails, keep these artifacts if they exist:

```text
processor/build/reports/opencl/validation-report.md
processor/build/reports/opencl/validation-history.md
processor/build/reports/opencl/bucket-status.properties
processor/build/reports/opencl/workload-summary.properties
processor/build/reports/opencl/long-running-summary.properties
processor/build/reports/opencl/backend-source-promotion-gate.properties
processor/build/reports/opencl/backend-source-promotion-workload-gate.properties
processor/build/reports/opencl/production-source-switching-validation.properties
processor/build/reports/opencl/backend-source-promotion-candidate-gate.properties
processor/build/reports/opencl/backend-source-promotion-manifest-template.properties
processor/build/reports/opencl/backend-source-promotion-manifest-validation.properties
processor/build/reports/opencl/backend-source-promotion-activation-gate.properties
processor/build/reports/opencl/backend-source-promotion-activation-gate.properties.sha256
processor/build/reports/opencl/production-activation-token-smoke.properties
processor/build/reports/opencl/production-activation-token-negative.properties
<custom-dir-from-javatogpu.opencl.runtimeCompileArtifactDirectory>/**/original.irgpu.properties
<custom-dir-from-javatogpu.opencl.runtimeCompileArtifactDirectory>/**/optimized.irgpu.properties
<custom-dir-from-javatogpu.opencl.runtimeCompileArtifactDirectory>/**/original.backend.opencl-c
<custom-dir-from-javatogpu.opencl.runtimeCompileArtifactDirectory>/**/optimized.backend.opencl-c
<custom-dir-from-javatogpu.opencl.runtimeCompileArtifactDirectory>/**/backend.opencl-c
processor/build/reports/opencl/runtime-compile-artifacts/**/runtime-ir-optimizer-evidence.properties
processor/build/reports/opencl/runtime-compile-artifacts/**/runtime-method-test-evidence.properties
processor/build/reports/opencl/runtime-compile-artifacts/**/runtime-optimizer-family-equivalence-payload/
processor/build/reports/opencl/optimizer-family-payload-fixture/
processor/build/test-results/
```

These files are more useful than a screenshot because they preserve bucket status, device details, and machine-readable failure state.

For local pre/post optimizer inspection, set `-Djavatogpu.opencl.runtimeCompileArtifactDirectory=<directory>` on the runtime process. This writes the same per-kernel artifact bundle independently of the validation report path, including `original.irgpu.properties`, `optimized.irgpu.properties`, `original.backend.opencl-c`, `optimized.backend.opencl-c`, selected `backend.opencl-c`, `runtime-ir-handoff.properties`, and optimizer evidence when present.

Method-level `@GPUTest` evidence is recorded in `runtime-method-test-evidence.properties` inside each runtime compile
artifact directory. The validation report aggregates it into `Method Test Evidence`, including metadata counts,
selection-probe counts, cache-evidence status, and cached passed/failed/missing evidence totals. This is diagnostic-only:
the report does not run probes; it summarizes metadata and cache-ranking facts already recorded by the runtime path.

The candidate gate combines the real-workload gate with controlled source-switching acceptance for the same kernel resources and device identity. `review-ready` means the candidate evidence is complete; default production source switching and production mutation remain disabled.

Manual `workflow_dispatch` runs expose `production_promotion_manifest_mode=skip|template|validate|activate`. Use `template` to archive a pending device-specific manifest bound to that run's `github.sha`. After approving and committing the manifest, use `validate` with `production_promotion_manifest_file`, the original SHA in `production_promotion_candidate_git_sha`, and a single matching device `validation_lane` such as `nvidia-rtx5070`, `nvidia-rtx3060`, or `amd`. Candidate SHA-256 and identity bindings prevent reuse for another GPU, driver, candidate artifact, or source state.

Use `activate` only after manifest validation succeeds. The workflow then writes and validates the controlled activation gate plus its SHA-256 sidecar, loads the exact artifact into a `GpuProductionActivationToken`, and runs every approved real workload kernel on the selected device. The positive hardware result is written to `production-activation-token-smoke.properties` with per-kernel status and workload coverage. A required negative lane then verifies that a mismatched SHA-256 is rejected and that an unapproved kernel is blocked before output mutation; its result is written to `production-activation-token-negative.properties`. The `activate` lane fails if either check fails. This mode does not enable default runtime activation, default production source switching, or production mutation.

`openClValidationReport` folds both activation-token artifacts into production-promotion explainability. A successful controlled activation records token loading, approved-kernel execution, full real-workload coverage, digest-mismatch rejection, unapproved-kernel rejection, unchanged rejected output, and safe defaults as separate readiness evidence. The RTX 5070 promotion checkpoint now demonstrates the complete production-ready path: `production-promotion-explainability.properties` records `status=production-ready`, `contract.status=valid`, `decision.mode=production-enabled`, `readinessChecklist.ready.count=11`, and `blocker.count=0` while still requiring explicit manifest-bound activation evidence.

The vendor workflow also runs `openClOptimizerFamilyPayloadFixtureTest` after the main validation bucket. It must produce `fixture-summary.properties` with two complete families and fourteen durable files. This fixture proves the nested artifact contract is uploadable and path-safe; it does not alter real-workload optimizer-family counts or production readiness.

`validateOpenClRuntimeIrOptimizerEvidence` checks `runtime-ir-optimizer-evidence.properties` under `runtime-compile-artifacts` when optimizer evidence is recorded. The operational and NVIDIA routines invoke it with `--allow-missing`, because IR optimizer evidence is optional in normal vendor CI and may legitimately be `not-recorded`. When evidence is present, it fails if runtime-equivalence review, optimized-artifact candidate, or review-package guardrails stop being fail-closed, if production mutation or selected-IR replacement becomes enabled, if candidate selection is applied, if a required review package has no blocker, or if a package is marked complete before manual-review activation exists. When materialization-family evidence is present, it also verifies family-specific review-ready invariants: safe-local-CSE needs existing-local reuse or introduced-temp evidence, dominance/side-effect proof, matching text replacement, required runtime-equivalence/approval gates, and passed payloads; fast-math `mad/fma` needs matching text replacement, fast-math policy evidence, required runtime-equivalence/approval gates, and passed payloads; `clamp` needs matching text replacement, strict-float/no-fast-math safety evidence, required runtime-equivalence/approval gates, and passed payloads; `step` needs matching text replacement, direct/inverted step accounting, strict comparison/equality/NaN safety evidence, required runtime-equivalence/approval gates, and passed payloads; `mix` needs matching text replacement, canonical/expanded/MAD-expanded accounting, fast-math/reassociation evidence when required, required runtime-equivalence/approval gates, and passed payloads; loop vectorization needs matching text replacement, fixed trip-count proof, contiguous-load proof, ordered-reduction preservation, typed-body materialization/invalidation accounting, required runtime-equivalence/approval gates, and passed payloads; typed dead-code removal needs consistent node counts, side-effect proof, required runtime-equivalence/approval gates, and passed payloads. Run the CLI without `--allow-missing` when a local or release gate must require at least one optimizer evidence artifact.

## Optional IR Validation

For stricter compiler diagnostics or CI evidence, add `javatogpu-ir-validation` and start with diagnostic mode:

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += '-Ajavatogpu.irValidation=diagnostic'
    options.compilerArgs += '-Ajavatogpu.irValidationDiagnostics=summary'
    options.compilerArgs += '-Ajavatogpu.irValidationReport=reports/javatogpu-ir-validation.properties'
}
```

Use `strictSafety` when CI should fail on safety diagnostics. Use `strictOptimizer` only for compiler hardening or optimizer-readiness experiments.

See [IR Validation](IR-Validation.md) for details.

## Vendor Validation

When adding or checking a self-hosted GPU runner, run the full operational routine on that machine and archive the OpenCL report directory.

Record:

- Vendor and device name.
- Driver/runtime version.
- Command used.
- Whether all buckets passed.
- Any confirmed device-specific failures.

If a failure reproduces only on one vendor stack, document it in [Device Quirks](Device-Quirks.md). Current green lanes include NVIDIA RTX 3060, NVIDIA RTX 5070, and AMD RX 7800 XT.

## What Green Validation Means

A green operational routine means the current repo, selected backend, driver, and hardware passed the current alpha evidence suite.

It does not prove:

- Every OpenCL implementation behaves the same.
- CUDA, Vulkan, or Metal support.
- Arbitrary Java bytecode support.
- Stable beta/production API compatibility.

## Practical Release Checklist

Before publishing or announcing a new alpha build:

1. Run `:processor:test`.
2. Run `:processor:openClOperationalRoutine --rerun-tasks` on at least one GPU machine.
3. Inspect `validation-report.md`.
4. Keep report artifacts for the release notes or CI logs.
5. Update [Device Quirks](Device-Quirks.md) if a vendor-specific issue is confirmed.

## Read Next

- [Troubleshooting](Troubleshooting.md)
- [IR Validation](IR-Validation.md)
- [Device Quirks](Device-Quirks.md)
- [OpenCL Runner Contract](OpenCL-Runner-Contract.md)

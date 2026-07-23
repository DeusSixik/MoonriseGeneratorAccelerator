# IR Validation

`javatogpu-ir-validation` is an optional module that adds stricter diagnostics for JavaToGpu builds.

Most users can start without it. Add it when you want CI reports, earlier validation failures, or clearer diagnostics while preparing kernels for future optimizer work.

## Fast Decision Guide

| Situation | Recommended mode |
| --- | --- |
| Trying JavaToGpu for the first time | Leave IR validation off |
| Developing kernels and wanting extra hints | `diagnostic` + `summary` |
| CI should reject unsafe kernel shapes | `strictSafety` |
| Compiler/optimizer hardening branch | `strictOptimizer` |

IR validation is read-only. It does not change generated OpenCL or runtime source selection.

## When To Use It

Use IR validation when you want to:

- Catch unsupported kernel shapes earlier.
- Produce CI-friendly validation reports.
- See conservative optimizer-readiness diagnostics.
- Track whether kernels are ready for future CSE or auto-vectorization work.
- Debug compiler/runtime behavior before investigating device-specific OpenCL issues.

For quick experiments, the main `javatogpu` artifact is enough.

## Add The Module

Use the same version as the main JavaToGpu artifact:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.deussixik:javatogpu:0.1.0-alpha.4'
    annotationProcessor 'io.github.deussixik:javatogpu:0.1.0-alpha.4'

    annotationProcessor 'io.github.deussixik:javatogpu-ir-validation:0.1.0-alpha.4'
}
```

Start in diagnostic mode:

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += '-Ajavatogpu.irValidation=diagnostic'
    options.compilerArgs += '-Ajavatogpu.irValidationDiagnostics=summary'
    options.compilerArgs += '-Ajavatogpu.irValidationReport=reports/javatogpu-ir-validation.properties'
}
```

The module is inert unless `javatogpu.irValidation` is enabled, so it can live on a CI annotation-processor path without changing normal local builds.

## Modes

Available modes:

```text
off
diagnostic
strictSafety
strictOptimizer
```

- `off` disables optional validation providers. This is the default.
- `diagnostic` runs validation and prints/report diagnostics without failing builds for optimizer-readiness findings.
- `strictSafety` fails the build on safety validation errors.
- `strictOptimizer` also fails on conservative optimizer-readiness blockers. Use this mainly for compiler development or hardening branches.

Recommended adoption path:

1. Start with `diagnostic` locally or in CI.
2. Fix safety diagnostics first.
3. Move CI to `strictSafety` when kernels should fail closed.
4. Use `strictOptimizer` only when you intentionally want optimizer-readiness gates.

## Diagnostic Output

`javatogpu.irValidationDiagnostics` controls javac note output:

```text
quiet
summary
detailed
```

- `quiet` runs validation but suppresses javac notes.
- `summary` prints compact counters and the most useful first diagnostic. This is the recommended default.
- `detailed` prints nested context for debugging noisy CI failures.

## Report Artifact

`javatogpu.irValidationReport` writes a `.properties` report under the generated-source output directory.

Example:

```text
reports/javatogpu-ir-validation.properties
```

Use this file in CI when you want a machine-readable artifact with method-level validation status, optimizer-readiness summaries, first blockers, and no-candidate buckets.

You usually do not need to parse every key. Start with the top-level verdict/summary fields and only drill into detailed fields when a gate fails.

## Developer Workflow Example

Use this flow when you are developing kernels and want actionable feedback without changing generated OpenCL or runtime behavior.

### 1. Add IR validation to the annotation processor path

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.deussixik:javatogpu:0.1.0-alpha.4'
    annotationProcessor 'io.github.deussixik:javatogpu:0.1.0-alpha.4'

    // Optional: adds read-only IR validation providers.
    annotationProcessor 'io.github.deussixik:javatogpu-ir-validation:0.1.0-alpha.4'
}
```

The module is optional. If this dependency is absent, JavaToGpu works normally and no extra validators run.

### 2. Enable diagnostic mode first

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += '-Ajavatogpu.irValidation=diagnostic'
    options.compilerArgs += '-Ajavatogpu.irValidationDiagnostics=summary'
    options.compilerArgs += '-Ajavatogpu.irValidationReport=reports/javatogpu-ir-validation.properties'
}
```

`diagnostic` is the safest development mode: it records findings and prints a compact javac summary, but normal optimizer-readiness warnings do not become production rewrites.

### 3. Run a normal build

```powershell
.\gradlew.bat clean build
```

For a smaller local loop, run the module that contains your `@GPU` kernels:

```powershell
.\gradlew.bat :your-module:compileJava
```

### 4. Read the report from generated sources

The `javatogpu.irValidationReport` path is written as a generated-source resource path. With the example above, look under the JavaCompile generated-source output for:

```text
reports/javatogpu-ir-validation.properties
```

Start with these fields:

```properties
format=javatogpu.ir.validation.v1
entry.count=...
entry.0.severity=...
entry.0.ruleId=...
entry.0.methodName=...
entry.0.entryPoint=...
entry.0.sourceAnchor=...
```

Recommended triage order:

1. Fix `ERROR` entries first. These usually indicate unsupported or unsafe IR shape.
2. Review `WARNING` entries next. These are useful for CI hardening and future optimizer readiness.
3. Treat `INFO` entries as hints or optimizer-readiness notes.
4. Use `sourceAnchor` / method fields to map the finding back to the Java kernel or helper.

### 5. Move CI to strict safety only after diagnostics are stable

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += '-Ajavatogpu.irValidation=strictSafety'
    options.compilerArgs += '-Ajavatogpu.irValidationDiagnostics=summary'
    options.compilerArgs += '-Ajavatogpu.irValidationReport=reports/javatogpu-ir-validation.properties'
}
```

Use `strictSafety` when safety findings should fail the build. Keep `strictOptimizer` for compiler-development or hardening branches, because it also fails on conservative optimizer-readiness blockers.

## Custom Validator Example

A custom validator is a separate optional JAR. It participates through Java `ServiceLoader`, reads compiler IR, and reports diagnostics. It must not rewrite IR.

Minimal provider:

```java
package com.example.gpu.validation;

import net.sixik.ga_utils.javatogpu.frontend.ir.validation.GpuIrValidationProvider;
import net.sixik.ga_utils.javatogpu.frontend.ir.validation.GpuIrValidationReportEntry;
import net.sixik.ga_utils.javatogpu.frontend.ir.validation.GpuIrValidationRequest;

import java.util.Map;

public final class CompanyGpuIrValidationProvider implements GpuIrValidationProvider {
    @Override
    public String extensionId() {
        return "com.example.company-ir-validation";
    }

    @Override
    public String extensionVersion() {
        return "1";
    }

    @Override
    public void validate(GpuIrValidationRequest request) {
        String methodName = request.method().emittedName();

        if (methodName.contains("Experimental")) {
            request.reportEntry(new GpuIrValidationReportEntry(
                    "company-policy",
                    methodName,
                    request.entryPoint(),
                    Map.of("policy", "experimental-method-name")
            ));
            request.reportDiagnostic("Company IR policy matched experimental GPU method: " + methodName);
        }
    }
}
```

ServiceLoader registration:

```text
src/main/resources/META-INF/services/net.sixik.ga_utils.javatogpu.frontend.ir.validation.GpuIrValidationProvider
```

File contents:

```text
com.example.gpu.validation.CompanyGpuIrValidationProvider
```

Add the custom validator JAR to the annotation-processor path of the project being compiled:

```groovy
dependencies {
    annotationProcessor 'com.example:company-javatogpu-ir-validation:1.0.0'
}
```

The runner validates extension metadata before execution. A validator is expected to use the `IR_VALIDATION` phase, `IR_VALIDATION` capability, and `READ_ONLY` permission. If you need to propose IR rewrites, implement a future optimizer/proposal module instead of extending the validator contract.

## What It Checks

The validation module checks for safety issues that should be caught before backend compilation, including:

- Unknown variable references.
- Duplicate local declarations.
- Invalid assignment targets.
- Invalid `break` / `continue` placement.
- Helper-call metadata and reachability issues.
- Intrinsic metadata and placeholder consistency issues.
- Struct-initializer and return-shape mismatches.
- Invalid private-array sizes.
- Pure expression statements with no observable side effect.

It also reports read-only optimizer previews for common-subexpression reuse and auto-vectorization readiness. These previews do not change generated OpenCL code.

## Optimizer Readiness

IR validation is preparing the ground for future optimization passes, but normal validation is still read-only.

Today it can report things like:

- Repeated expressions that might become CSE candidates later.
- Candidate groups blocked by control flow or mutation between occurrences.
- Auto-vectorization candidates and blockers.
- Missing runtime-equivalence evidence for promotion-style experiments.
- No-candidate buckets that explain why a method has nothing vectorizable yet.

This lets CI track optimizer readiness without enabling production IR mutation.

## Dogfooding Reports

The repository has optional example-report tasks for maintaining validation baselines.

Generate and validate current example reports:

```powershell
.\gradlew.bat validateIrValidationExampleReports `
  -Pjavatogpu.enableIrValidationExamples=true `
  -Pjavatogpu.irValidationMode=diagnostic `
  -Pjavatogpu.irValidationDiagnostics=summary
```

Write a compact baseline:

```powershell
.\gradlew.bat writeIrValidationExampleReportBaseline `
  -Pjavatogpu.enableIrValidationExamples=true `
  -Pjavatogpu.irValidationMode=diagnostic `
  -Pjavatogpu.irValidationDiagnostics=summary `
  -Pjavatogpu.irValidationBaselineDir=build/ir-validation-baselines
```

Compare a later run against the baseline:

```powershell
.\gradlew.bat validateIrValidationExampleReportBaseline `
  -Pjavatogpu.enableIrValidationExamples=true `
  -Pjavatogpu.irValidationMode=diagnostic `
  -Pjavatogpu.irValidationDiagnostics=summary `
  -Pjavatogpu.irValidationBaselineDir=build/ir-validation-baselines
```

Write a compact health summary for CI upload:

```powershell
.\gradlew.bat writeIrValidationHealthSummary `
  -Pjavatogpu.enableIrValidationExamples=true `
  -Pjavatogpu.irValidationMode=diagnostic `
  -Pjavatogpu.irValidationDiagnostics=summary
```

## Runtime-Equivalence Evidence

Some optimizer experiments can attach runtime-equivalence evidence. This is opt-in and separate from normal validation.

Use it when you are testing a future optimization and want CI to prove that pre/post behavior still matches for selected input cases. Missing evidence should block promotion-style artifacts, but it should not affect ordinary application builds unless you wire those gates into your own CI.

## Practical CI Setup

For application projects, a good starting point is:

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += '-Ajavatogpu.irValidation=diagnostic'
    options.compilerArgs += '-Ajavatogpu.irValidationDiagnostics=summary'
    options.compilerArgs += '-Ajavatogpu.irValidationReport=reports/javatogpu-ir-validation.properties'
}
```

For stricter compiler or library CI, move to:

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += '-Ajavatogpu.irValidation=strictSafety'
    options.compilerArgs += '-Ajavatogpu.irValidationDiagnostics=summary'
    options.compilerArgs += '-Ajavatogpu.irValidationReport=reports/javatogpu-ir-validation.properties'
}
```

## Current Limits

- Validation is not a proof that all future optimizations are safe.
- CSE and auto-vectorization analysis is intentionally conservative.
- Normal validation does not mutate IR or change generated OpenCL.
- Prototype rewrite/equivalence helpers are for explicit tests and integration experiments.
- Backend-specific runtime optimization is future work.

## Read Next

- [Getting Started](Getting-Started.md)
- [Diagnostics Reference](Diagnostics-Reference.md)
- [Validation and Operations](Validation-and-Operations.md)
- [Known Limitations](Known-Limitations.md)

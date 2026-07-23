# IR Optimizer

IR Optimizer is an optional optimization layer for generated GPU IR and backend
code. Think of it as a safe "try to make this kernel cleaner" step: it can look
at the generated IR, propose improvements, dump before/after files, and explain
why each optimization was or was not applied.

The most important rule is simple:

> By default, the optimizer is review-only. It can generate an optimized
> candidate, but the runtime still compiles the normal selected backend code.

That makes it useful while the optimizer is still growing. You can inspect what
it would do, compare artifacts, validate the evidence, and only opt into real
application during local experiments.

## Fast Decision Guide

| Goal | Use | Expect |
| --- | --- | --- |
| See before/after code | Diagnostic artifact dumps | `optimized.backend.opencl-c` may exist while `backend.opencl-c` stays original |
| Compile optimized code locally | `openClIrOptimizerExperimentalApply(...)` | Explicit experiment only, not production activation |
| Make optimizer safer | IR validation and evidence reports | Blockers are useful; do not hide them |
| Add a custom rule/provider | Optimizer proposal SPI | Stable ids, proof fields, rollback/equivalence evidence |
| Build a future production optimizer | Promotion/approval gates | Separate work from normal alpha usage |

## When To Use It

Use the IR Optimizer when you want to:

- inspect optimization opportunities in generated OpenCL C;
- dump code before and after optimizer passes;
- test optimizer rules on real kernels without changing production behavior;
- compare optimizer evidence with the generated backend source;
- write custom backend-neutral or vendor-specific optimizer providers.

Do not treat the default optimizer path as a production rewrite switch yet. The
safe workflow is: generate original code, generate an optimized candidate for
review, compare evidence, and then deliberately opt into local apply mode only
when you want to test compiled optimized code.

## Quick Start

For the normal safe path, compile as usual and enable diagnostic artifact output
through the runtime options used by your app or example:

```java
GpuRuntimeCompileOptions.openCl(List.of(), "diagnostic")
```

This keeps the runtime in review-only mode. After running a kernel, inspect the
dumped files:

| File | Meaning |
| --- | --- |
| `original.backend.opencl-c` | Backend code before optimizer review |
| `optimized.backend.opencl-c` | Optimizer candidate, if one was produced |
| `backend.opencl-c` | Code selected for compilation |
| `original.preview.backend.cuda-c` | CUDA-C source preview reconstructed from original IR, if supported |
| `optimized.preview.backend.cuda-c` | CUDA-C source preview reconstructed from optimized IR, if supported |
| `cuda-source-preview.properties` | CUDA preview status, blockers, and selected preview stage |
| `runtime-ir-handoff.properties` | Which runtime stage/source was selected |
| `runtime-ir-optimizer-evidence.properties` | Optimizer pass evidence and blockers |

If `optimized.backend.opencl-c` exists but `backend.opencl-c` still looks like
the original code, that is expected. It means the optimizer produced a review
candidate, but the runtime stayed in safe review-only mode.

## Applying Optimizations Locally

To test optimized code as the compiled backend source, use the explicit local
apply option:

```java
GpuRuntimeCompileOptions.openClIrOptimizerExperimentalApply(List.of(), "diagnostic")
```

This sets the runtime selection mode to:

```properties
runtime.irOptimizerSelection=experimental-apply
```

Use this mode for local experiments, examples, and optimizer development. It is
designed to answer: "What happens if we actually compile the optimized
candidate?"

It is not a production toggle. Production-like paths still remain fail-closed
behind proof, rollback, approval, and promotion gates. Validator tooling also
requires intentional local permission for these artifacts, for example with
`--allow-experimental-apply` where applicable.

## Reading The Output

Start with these checks:

1. Open `original.backend.opencl-c` and `optimized.backend.opencl-c` side by side.
2. Check `backend.opencl-c` to see what was actually compiled.
3. Open `runtime-ir-handoff.properties` and look for the optimizer selection mode.
4. Open `runtime-ir-optimizer-evidence.properties` and search for `optimizerFamily`,
   `outcome`, `firstBlocker`, and `experimentalApply`.

Common interpretations:

| What you see | What it means |
| --- | --- |
| No `optimized.backend.opencl-c` | No materialized candidate was produced |
| Optimized file exists, backend file unchanged | Normal review-only behavior |
| Backend file matches optimized file | Experimental apply selected the candidate |
| Evidence has `firstBlocker` | A rule saw a shape but refused to rewrite safely |
| Evidence says `policy-gate` | `@GPUOptimize` or runtime policy disabled that family |

## What It Can Optimize Today

The current optimizer is intentionally conservative. Most rules target common
generated OpenCL patterns and only rewrite when the matcher can prove the shape
is exactly the one it understands.

| Family | Example before | Example after | Notes |
| --- | --- | --- | --- |
| Constant folding | `((2 + 3) * 4)` | `20` | Safe integer folds and identities like `x + 0`, `x * 1` |
| Safe local CSE | repeated `(value * 1.414F)` | local temp reuse | Reuses existing locals or introduces conservative temps |
| Mad/FMA | `(a * b) + c` | `mad(a, b, c)` | Requires fast-math permission |
| Clamp | `min(max(x, lo), hi)` | `clamp(x, lo, hi)` | Preserves argument order |
| Step | `a > b ? 1.0F : 0.0F` | `1.0F - step(a, b)` | Preserves strict comparison behavior |
| Mix | `a + t * (b - a)` | `mix(a, b, t)` | Expanded algebraic forms may require fast math |
| Loop vectorization | fixed `i < 4` contiguous load reduction | `vload4` + lane reduction | Keeps ordered scalar reduction, no `dot` reassociation |
| Typed dead code | unreachable pure typed nodes | removed from candidate | Review artifact only |

### Clamp

Generated code may contain nested min/max calls:

```c
float clampedWave = min(max(wave1, -1.0F), 1.0F);
```

The clamp rule can materialize the clearer GPU intrinsic:

```c
float clampedWave = clamp(wave1, -1.0F, 1.0F);
```

The rule currently matches the conservative shape `min(max(x, lo), hi)`. It does
not yet rewrite every algebraically equivalent ordering, because argument order
and floating-point edge cases are part of the safety contract.

### Step

Generated masks often start as ternaries:

```c
float isSolid = (blend > threshold) ? 1.0F : 0.0F;
```

The optimizer can express this through OpenCL `step`. For strict comparisons it
uses an inverted form so equality behavior stays correct:

```c
float isSolid = 1.0F - step(blend, threshold);
```

Inclusive comparisons can usually become direct `step(edge, x)` shapes.

### Mix

Linear interpolation patterns can become `mix`:

```c
float y = a + t * (b - a);
```

Candidate output:

```c
float y = mix(a, b, t);
```

The optimizer also recognizes some expanded generated forms, including patterns
that already passed through `mad(...)`. Expanded forms can change operation
ordering, so they require fast-math permission.

### Loop Vectorization

The vectorization rule recognizes a narrow, useful shape:

```c
float sum = 0.0F;
for (int i = 0; i < 4; i = i + 1) {
    sum = sum + input[mad(id, 4, i)];
}
```

The review candidate can use a `vload4`-style load and then reduce lanes in the
same order. The rule intentionally avoids `dot` for now because `dot` would
reassociate floating-point additions.

## Tuning With @GPUOptimize

`@GPUOptimize` is the public policy hook for optimizer behavior. It lets the
kernel author describe what kind of optimization is allowed without tying the
code to one backend.

The policy can control:

- whether optimization is enabled at all;
- optimizer profile, such as `off` or `diagnostic`;
- enabled and disabled optimization families;
- fast-math permission;
- dump/journal hints;
- vendor adaptation permission;
- vectorization and resource-shaping preferences;
- production intent.

The optimizer treats policy as a gate, not a suggestion. If a family is disabled
or fast math is not allowed, matching providers must skip or block the rewrite
and record that decision in evidence.

## Safety Model

The optimizer is designed to fail closed.

- `ir-validation` remains read-only and never mutates `IrGpu`.
- Optimizer providers must not mutate the input artifact in place.
- A real rewrite must return a distinct optimized artifact.
- Missing proof, missing approval, failed validation, or disabled policy keeps
  the original IR selected.
- Review candidates are allowed to exist without being selected for compilation.
- Experimental apply is explicit and recorded in evidence.

This lets new rules be developed in small steps: first detect the pattern, then
dump evidence, then materialize a review candidate, then eventually prove and
promote the rule.

## Optimizer Journal

The optimizer evidence file is the current journal surface. It records which
providers ran, what they proposed, why they skipped, and whether any candidate
was selected.

Useful fields to search for:

| Field | Why it helps |
| --- | --- |
| `optimizerFamily` | Which rule family produced the evidence |
| `RewriteKind` | What rewrite was planned or materialized |
| `firstBlocker` | Why a rule refused to continue |
| `policy.*` | Runtime and annotation policy used for the pass |
| `runtimeEquivalencePayload.*` | Proof payload metadata for review |
| `optimizedArtifactCandidate.*` | Candidate artifact selection state |
| `experimentalApply.*` | Whether local apply mode selected optimized code |

A future higher-level journal can cover the whole run from program discovery to
backend compilation. For now, the optimizer evidence is intentionally structured
so tools can build that user-facing timeline later.

## Vendor Optimizers

Backend-neutral rules live in `ir-optimizer`. Vendor or device-specific rules
should live behind the vendor optimizer SPI instead of being loaded silently into
the default path.

This separation keeps the default optimizer portable while still allowing future
modules such as `ir-vendor-optimizer` to add targeted behavior for a vendor,
driver, device class, or backend.

Vendor providers use the same proposal model and validation sandwich as normal
providers. They must still provide evidence, diagnostics, rollback information,
and proof metadata before a rewrite can be considered safe.

## Troubleshooting

| Problem | Likely cause | What to check |
| --- | --- | --- |
| Optimized file is missing | No rule materialized a candidate | Search evidence for `firstBlocker` |
| `mad(...)` is not emitted | Fast math is disabled | Check `@GPUOptimize(fastMath = true)` / policy fields |
| `mix(...)` is not emitted | Shape is unsupported or fast math is required | Compare expression shape and evidence blockers |
| `backend.opencl-c` is not optimized | Review-only mode is active | Use experimental apply for local testing |
| Validator rejects apply artifacts | Apply mode was not explicitly allowed | Pass the local `--allow-experimental-apply` validator flag |
| Vendor rule does not run | Vendor registry was not explicitly loaded | Check provider registration and runtime wiring |

## Implementation Reference

The rest of this page keeps the lower-level optimizer contract details for
authors and maintainers. Most users can stop at the sections above.

<details>
<summary>Low-level optimizer contract and implementation notes</summary>

### First Alpha Scope

The first alpha is an evidence and contract boundary, not a production rewrite release. It includes the optional `ir-optimizer` / `ir-vendor-optimizer` module split, immutable proposal contracts, the validation sandwich, preview-only optimizer families, backend-neutral source materialization, constrained review-only constant-folding materialization, review-only safe-local-CSE materialization that reuses existing local values or introduces conservative local temporaries, review-only fast-math `mad/fma` materialization, review-only `clamp` materialization for generated `min(max(x, lo), hi)` shapes, review-only `step` materialization for ternary masks, review-only `mix` materialization for interpolation shapes, review-only fixed-width loop-vectorization materialization, typed dead-code materialization for unreachable pure typed nodes, approval-template metadata, optimized-artifact candidate envelopes, and OpenCL evidence/report/validator guardrails.

The first shared typed-body patch scaffold is `GpuIrTypedBodyGraphPatch`. It owns deterministic typed-node lookup, reachable-node scanning with missing-root / missing-child diagnostics, next-node allocation, root replacement, append-only auxiliary node insertion, intrinsic-call node construction, fail-closed `Plan.apply(...)` validation for text + typed-body patches, binding-aware text replacement offsets, and shared patch-blocker taxonomy for provider counters. `clamp`, `step`, `mix`, `mad/fma`, constant-folding, and safe-local-CSE use it for transactional patch mechanics; safe-local-CSE plus typed-dead-code preview/materialization also use the shared reachability report while their matcher logic, runtime-equivalence evidence, and fail-closed selected-IR behavior remain provider-owned.

It intentionally excludes runtime IR selection, selected-IR replacement, production mutation, real vendor rewrites, and any automatic choice of optimized artifacts. Those remain future gates above runtime-equivalence, rollback, approval, and production-promotion evidence.

### Contract

- `ir-validation` remains read-only and must not mutate `IrGpu`.
- `ir-optimizer` owns backend-neutral optimizer contracts and general passes.
- Vendor/device-specific optimizers live behind `GpuIrVendorOptimizationProposalProvider` and are loaded explicitly through `GpuIrVendorOptimizationProposalRegistry`, not through the default backend-neutral proposal bridge.
- Optimizer passes must not mutate the input artifact in place.
- Real rewrites must return a distinct optimized artifact, diagnostics, proof links, and rollback evidence.
- Missing optimizer modules, missing approval, failed proof, or disabled optimization must leave the original IR selected.
- `@GPUOptimize` policy metadata is available to proposal providers through the runtime bridge context, including profile hints, fast-math permission, enabled/disabled family lists, journal/dump hints, production intent, vendor adaptation, vectorization preference, and resource-shaping intent. The runtime bridge now uses `enabledFamilies` / `disabledFamilies` and explicit `enabled = false` to gate provider invocation. Policy skips emit `ir-optimizer.policy-gate` proof fields, are aggregated into `runtime-ir-optimizer-evidence.properties` / markdown `policyGate.*` counters, and keep mutation, optimized-artifact selection, and selected-IR replacement disabled.

### Current Skeleton

`GpuIrNoOpOptimizationPass` reports a skipped optimizer pass at candidate-discovery stage. It proves that projects can add the optional optimizer module without enabling production mutation or changing generated backend source.

The first real proposal provider is `GpuIrTextCanonicalizationProposalProvider`. It only canonicalizes transitional IR text bodies by normalizing line endings and stripping trailing spaces or tabs. It preserves typed IR bodies, backend outputs, metadata, and the original artifact object. By default it remains proposal-only; the validation sandwich selects the canonicalized artifact only when mutation is explicitly allowed.

`GpuIrHelperDependencyDeduplicationProposalProvider` is the first backend-neutral metadata cleanup pass. It removes duplicate helper-dependency entries from method bodies while preserving first-occurrence order and leaving text bodies, typed bodies, backend outputs, and the original artifact unchanged. It is proposal-only by default and exists to prove the real rewrite/proposal path without changing kernel semantics.

`GpuIrBackendNeutralSourceMaterializationProposalProvider` is the first review-only backend-neutral source materialization pass. For OpenCL runtime review requests, it verifies that the `IrGpu` artifact can emit reconstructed OpenCL source, then returns a distinct optimized artifact with `backendNeutralSourceReady` metadata set. The proof records `backend-neutral-source-materialization`, source readiness, source length, no in-place mutation, and disabled production effect. This makes before/after backend dumps compare source-ready IR candidates while `backend.opencl-c` still comes from the selected runtime source.

`GpuIrConstantFoldingPreviewProposalProvider` is a preview-only proof surface for future typed constant folding. It scans typed IR for simple binary expressions with plain decimal literal operands, records candidate counts, operator breakdown, numeric-kind breakdown, first folded value, skipped blocker counts, policy state, and required proof gates, and always returns `NO_CHANGE`. It deliberately does not produce an optimized artifact, even when mutation is allowed.

The preview records blockers such as divide-by-zero, non-even division, unsupported operators, non-literal operands, and non-plain literals. It also records that runtime-equivalence and approval are required before any future rewrite, and that integer-overflow / floating-point-rounding safety is not yet proven. This keeps constant folding useful as evidence while preventing it from silently becoming an applied transform.

`GpuIrConstantFoldingMaterializationProposalProvider` is the first review-only materialized transform candidate. It handles plain 32-bit integer literal unary `-` plus binary `+`, `-`, `*`, and exact `/` expressions where the divisor is non-zero and the division has no remainder, rewrites matching typed nodes into literals, and rewrites matching `ir-text-v1` expressions in the same method body. The pass also includes a constrained symbolic identity slice for deterministic pure retained expressions: `x + 0`, `0 + x`, `x - 0`, `x * 1`, `1 * x`, and `x / 1` can materialize to `x`, and the retained side can now be a named variable, plain int32 literal leaf, arithmetic binary tree, or unary minus tree that the pass can exactly re-render back to `ir-text-v1`. `x * 0`, `1 / x`, and broader division algebra remain intentionally excluded until stronger side-effect / unused-operand and equivalence proof exists. The pass runs safe literal and identity folds to a fixed point over reachable typed nodes, applying one materialized rewrite per pass so nested, mixed, repeated multi-root expressions, and multiple method bodies are recomputed against the updated typed body. Nested shapes such as `((-2) * 4)` and `((2 + 3) * 4)` can materialize as `-8` and `20`, while identity shapes such as `((a + b) + 0)`, `((a + b) / 1)`, `((x / 1) + 0)`, or repeated `x + 0` assignments can materialize as `(a + b)` or `x` in the optimized review artifact without crossing text replacements. If the textual body cannot be updated consistently, if division is unsafe/non-exact, if the retained identity expression contains unsupported / non-deterministic / non-plain-literal nodes, or if OpenCL review source cannot be regenerated for an OpenCL runtime request, it returns `NO_CHANGE`. The proof now records a compact exact-int/symbolic-identity `runtimeEquivalencePayload.*` with CPU/reference, pre-optimization, post-optimization, tolerance, failure-fixture, indexed case fields, per-case `MethodName`, `NodeId`, and `RewriteKind`, `runtimeEquivalencePayload.CaseIdentity=method-name-and-node-id`, `methodBody.rewriteScope=all-method-bodies`, `literalRewrite.count`, `identityRewrite.count`, `safety.identityOperandKind=GpuIrPureExpression`, `safety.identityRetainedExpressionKinds`, `fixedPoint.pass.count`, `fixedPoint.reachableNodeScan`, `fixedPoint.rewriteGranularity`, and `bodyTextReplacement.scope`, allowing the runtime-equivalence review gate to become `review-ready` for this narrow family. By default the bridge still reports the transformed artifact as proposal-only/candidate evidence; `backend.opencl-c` still comes from the runtime-selected source unless future selection gates explicitly allow otherwise.

`GpuIrSafeLocalCsePreviewProposalProvider` is a second preview-only proof surface for broader safe local common-subexpression elimination. It scans typed IR for repeated local expressions, records expression/candidate/duplicate/equivalence-class counts, and reports blockers such as unsupported operators, impure operands, and control-flow boundaries. It always returns `NO_CHANGE`, marks the proof as preview-only, and records that dominance, side-effect freedom, runtime equivalence, and approval must be proven before any future broader rewrite can graduate.

`GpuIrSafeLocalCseMaterializationProposalProvider` is the first review-only safe-local-CSE transform. It reuses already materialized local bindings in straight-line `ir-text-v1` bodies and runs to a fixed point over reachable roots. For example, after `var int tmp = (a + b)`, later matching assignments such as `set output[0] = (a + b)` and `set output[1] = (a + b)` can become `set output[0] = tmp` and `set output[1] = tmp` in the optimized review artifact. It can also introduce a conservative local temporary when the repeated pure binary expression has no existing binding, for example repeated `(value * 1.414F)` can become `var float jtg_cse0 = (value * 1.414F)` followed by uses of `jtg_cse0`. Introduced temporaries are limited to straight-line bodies, require all text occurrences to match the typed occurrences exactly, and are blocked if any input variable is reassigned between the first and last use. Existing-binding rewrites still use the shared transactional `GpuIrTypedBodyGraphPatch.Plan` path; introduced-temp rewrites rebuild the review typed roots with a cloned initializer subtree and validate reachability before returning a proposal. The pass blocks on control-flow boundaries, missing typed bodies, missing child references, unsupported/impure operands, missing text patterns, unsafe local invalidation, or OpenCL review-source emission failures. Proof fields use `optimizerFamily=safe-local-cse-materialization`, `RewriteKind=safe-local-cse-reuse-existing-local` or `safe-local-cse-introduce-local`, `introducedTemporary.count`, `runtimeEquivalencePayload.CaseIdentity=method-name-and-node-id`, `fixedPoint.pass.count`, `safety.newTemporaryIntroduced`, and `provider.mutatesOriginal=false`. By default the bridge still keeps the original runtime IR selected; only `optimized.backend.opencl-c` / `optimized.irgpu.properties` show the review candidate.

Safe-local-CSE runtime evidence now distinguishes existing-local reuse from introduced-local reuse through `safeLocalCseMaterialization.localBinding.count`, `safeLocalCseMaterialization.introducedTemporary.count`, `safety.newTemporaryIntroduced`, and per-case rewrite kinds. Review-ready evidence may use either an existing binding or an introduced local temporary, but production mutation and selected-IR replacement remain disabled.

`GpuIrMadFmaMaterializationProposalProvider` is the first review-only typed peephole materializer. It requires fast-math permission from `@GPUOptimize(fastMath = true)` / optimizer policy, scans reachable typed `a * b + c`, `c + a * b`, and `a * b - c` shapes, rewrites the matching `ir-text-v1` expression to `intrinsic(mad template="" args=[a, b, c])` or `intrinsic(mad template="" args=[a, b, (-c)])`, and replaces the typed root with a `GpuIrIntrinsicCall` review node. The subtract form appends a typed unary-negation node for the third argument in the optimized review artifact. It runs one materialized rewrite per fixed-point pass, requires OpenCL review-source emission for OpenCL requests, and records `optimizerFamily=mad-fma-materialization`, `targetIntrinsic=mad`, `policy.fastMathAllowed`, `safety.fastMathRequired=true`, `safety.strictFloatPreserved=false`, `fixedPoint.pass.count`, `bodyTextReplacement.count`, and per-case `RewriteKind=mad-fma-to-opencl-mad` / `RewriteKind=multiply-subtract-to-opencl-mad`. Strict/default policy, missing typed body, unsupported text format, missing text pattern, or blocked OpenCL review-source emission returns `NO_CHANGE`. This can make `optimized.backend.opencl-c` show `mad(...)`, but `backend.opencl-c` remains the original selected source unless future proof/approval/selection gates explicitly allow otherwise.

`GpuIrClampMaterializationProposalProvider` is the first review-only non-fast-math typed peephole materializer for generated clamp shapes. It scans reachable typed OpenCL intrinsic-call trees for the conservative shape `min(max(x, lo), hi)`, rewrites the matching `ir-text-v1` expression to `intrinsic(clamp template="" args=[x, lo, hi])`, and replaces the typed root with a `GpuIrIntrinsicCall` review node. It intentionally does not match reordered `min(hi, max(...))` or strict comparison / algebraic variants yet, because argument order, equality behavior, and floating-point edge cases must stay explicit. The proof records `optimizerFamily=clamp-materialization`, `targetIntrinsic=clamp`, `safety.fastMathRequired=false`, `safety.strictFloatPreserved=true`, `safety.argumentOrderPreserved=true`, `fixedPoint.pass.count`, `bodyTextReplacement.count`, and per-case `RewriteKind=min-max-to-opencl-clamp`. This can make `optimized.backend.opencl-c` show `clamp(...)`, but `backend.opencl-c` remains the original selected source unless future proof/approval/selection gates explicitly allow otherwise.

`GpuIrStepMaterializationProposalProvider` is a review-only non-fast-math typed peephole materializer for ternary float masks. It scans reachable typed ternary/conditional/select nodes whose condition is a binary `<`, `<=`, `>`, or `>=` comparison and whose branches are exact `1.0` / `0.0` literals. Inclusive shapes materialize directly, for example `a >= b ? 1.0F : 0.0F` becomes `intrinsic(step template="" args=[b, a])`. Strict shapes preserve equality and NaN behavior by using the inverted OpenCL form, for example `a > b ? 1.0F : 0.0F` becomes `(1.0F - intrinsic(step template="" args=[a, b]))`. The proof records `optimizerFamily=step-materialization`, `targetIntrinsic=step`, `directStep.count`, `invertedStep.count`, `safety.fastMathRequired=false`, `safety.strictComparisonPreserved=true`, `safety.equalityBehaviorPreserved=true`, `safety.nanComparisonPreserved=true`, and per-case `RewriteKind=ternary-mask-to-opencl-step` or `RewriteKind=ternary-mask-to-inverted-opencl-step`. This can make `optimized.backend.opencl-c` show `step(...)` review candidates, but `backend.opencl-c` remains the original selected source unless future proof/approval/selection gates explicitly allow otherwise.

`GpuIrMixMaterializationProposalProvider` is a review-only typed peephole materializer for linear interpolation shapes. It materializes canonical `a + t * (b - a)` / `t * (b - a) + a` forms to `intrinsic(mix template="" args=[a, b, t])` without requiring fast-math permission. It also recognizes expanded weighted forms such as `t * b + (1.0F - t) * a` and generated `mad(b, t, (1.0F - t) * a)` shapes, including the optimizer-journal style `mad(blend, isSolid, ((1.0F - isSolid) * (clampedWave * 0.1F)))`; those expanded forms require `fastMathAllowed` because they change floating-point operation ordering. The proof records `optimizerFamily=mix-materialization`, `targetIntrinsic=mix`, `canonicalMix.count`, `expandedMix.count`, `madExpandedMix.count`, `safety.fastMathRequired`, `safety.strictFloatPreserved`, `safety.algebraicReassociationRequired`, and per-case `RewriteKind=linear-interpolation-to-opencl-mix`, `expanded-linear-interpolation-to-opencl-mix`, or `mad-expanded-linear-interpolation-to-opencl-mix`. This can make `optimized.backend.opencl-c` show `mix(...)` review candidates, but `backend.opencl-c` remains the original selected source unless future proof/approval/selection gates explicitly allow otherwise.

`GpuIrLoopVectorizationMaterializationProposalProvider` is the first review-only loop vectorization materializer. It recognizes a strict fixed-width contiguous float reduction shape such as `float sum = 0.0F; for (int i = 0; i < 4; i++) sum += input[id * 4 + i]`, including equivalent `input[mad(id, 4, i)]` / `input[intrinsic(mad ...)]` index forms, `input[(base + i)]` where `base` is a straight-line local `int` binding for the width-scaled base, reversed `input[(i + base)]`, and constant-offset bases such as `input[((id * 4) + 8 + i)]`. Local base aliases are resolved conservatively from preceding straight-line `int` declarations only, are cleared across control-flow boundaries, and are invalidated by later assignment to the same local. The pass rewrites the review `ir-text-v1` body to `vload4` plus an ordered scalar lane reduction through `Float4`. It also reconstructs a matching review typed body for the supported `ir-text-v1` subset, using `GpuIrIntrinsicCall(vload4)`, `GpuIrUnary(&)`, `GpuIrArrayAccess`, `GpuIrFieldAccess` lane reads, and ordered `GpuIrBinary(+)` reduction nodes; the reconstructed typed body must pass the shared `GpuIrTypedBodyGraphPatch` reachability validation before it is counted as materialized. If surrounding body text falls outside that subset or the reconstructed typed graph is incomplete, it fails closed to typed-body invalidation for the review candidate, and runtime evidence keeps loop-vectorization blocked until typed-body rebuild validates. It records `optimizerFamily=loop-vectorization-materialization`, `targetIntrinsic=vload4`, `vectorWidth=4`, `safety.loopTripCountProven=true`, `safety.contiguousLoadProven=true`, `safety.orderedReductionPreserved=true`, `typedBody.materialized.count`, `typedBody.invalidated.count`, `typedBody.rebuild.*` counters/status/first-blocker, and per-case `RewriteKind=fixed-width-contiguous-vload4-ordered-reduction`. The pass intentionally avoids `dot` and reassociation, so it does not require fast-math permission. The original runtime IR remains selected; only `optimized.backend.opencl-c` / `optimized.irgpu.properties` show the `vload4` candidate.

`GpuIrTypedDeadCodePreviewProposalProvider` is a third preview-only proof surface for future typed dead-code cleanup. It walks typed-body roots, records reachable versus unreachable node counts, unreachable-kind breakdown, missing root / missing child-reference blockers, and side-effecting unreachable-node blockers. It always returns `NO_CHANGE`, marks the proof as preview-only, and records that runtime equivalence, approval, and side-effect freedom must be proven before any future cleanup rewrite can graduate.

`GpuIrTypedDeadCodeMaterializationProposalProvider` is the first non-constant review-only materialized transform. It removes only unreachable pure typed nodes, leaves method text bodies unchanged, blocks on missing roots, missing child references, or side-effecting unreachable nodes, and emits runtime-equivalence payload cases keyed by method name and node id. By default the bridge still reports the transformed artifact as proposal-only/candidate evidence; the selected runtime IR remains unchanged.

OpenCL validation aggregates these proof fields into `backendNeutralSourceMaterialization.*`, `constantFoldingPreview.*`, `constantFoldingMaterialization.*`, `safeLocalCsePreview.*`, `safeLocalCseMaterialization.*`, `madFmaMaterialization.*`, `clampMaterialization.*`, `stepMaterialization.*`, `mixMaterialization.*`, `loopVectorizationMaterialization.*`, `typedDeadCodePreview.*`, and `typedDeadCodeMaterialization.*` summary fields inside `runtime-ir-optimizer-evidence.properties`. Backend-neutral source materialization evidence records pass/candidate/source-ready counts, total reconstructed source length, status, and the first blocker so CI can see source-ready review candidates without treating them as selected IR. Materialized constant-folding evidence records pass/transformed-node/literal-rewrite/identity-rewrite/fixed-point-pass counts, runtime-equivalence payload presence/pass counts, status, and the first blocker so CI can see real transformed review candidates without treating them as selected IR. Materialized safe-local-CSE evidence records pass/local-binding/transformed-node/body-text-replacement counts, runtime-equivalence payload presence/pass counts, status, and the first blocker so CI can see repeated pure expressions rewritten to existing local references without treating them as selected IR. Materialized mad/FMA evidence records pass/candidate/transformed-node/body-text-replacement/fixed-point-pass counts, skipped fast-math-policy / body-text-pattern blockers, fast-math policy state, runtime-equivalence payload presence/pass counts, status, and the first blocker so CI can see fast-math peephole candidates without treating them as selected IR. Materialized clamp/step/mix evidence records pass/candidate/transformed-node/body-text-replacement counts, direct/inverted step counts, canonical/expanded/MAD-expanded mix counts, fast-math/reassociation requirements, runtime-equivalence payload presence/pass counts, status, and the first blocker so CI can see intrinsic materialization candidates without treating them as selected IR. Materialized loop-vectorization evidence records pass/candidate/transformed-loop/changed-method-body/body-text-replacement counts, typed-body materialization/invalidation counts, skipped loop-shape / unsupported-width / unsafe-load blockers, ordered-reduction proof, runtime-equivalence payload presence/pass counts, status, and the first blocker so CI can see fixed-width contiguous `vload4` review candidates without treating them as selected IR. Materialized typed dead-code evidence records pass/node/unreachable/removed-node counts, side-effect-freedom proof, runtime-equivalence payload presence/pass counts, status, and the first blocker so CI can see pure unreachable-node cleanup candidates without treating them as selected IR. The same payloads are also routed into `runtime-optimizer-family-equivalence-payload.properties` and durable per-pass component files. The artifact also records `previewReadiness.*` fields that summarize preview-family status across constant folding, safe-local CSE, and typed dead-code cleanup as `not-recorded`, `no-candidates`, `candidates-recorded`, `blocked-by-proof`, or `ready-for-runtime-equivalence-review`. `runtimeEquivalenceReview.*` then exposes a fail-closed review gate over preview readiness, materialized constant-folding candidates, materialized safe-local-CSE candidates, materialized mad/FMA candidates, materialized clamp/step/mix candidates, materialized loop-vectorization candidates, and materialized typed dead-code candidates: it can mark a candidate set as `review-ready`, but production mutation and selected-IR replacement remain disabled/manual-review-only. `reviewPackage.*` sits above that gate as the manual-review bundle boundary: backend-neutral source materialization and materialized candidates can produce pending approval templates, but the package remains pending on approval/manual review and repeats the disabled production mutation / selected-IR replacement guardrails. The generated `Runtime IR Optimizer Evidence` Markdown section shows backend-neutral source materialization status/source-ready counts, constant-folding preview pass/candidate/blocker counts, constant-folding materialization status/materialized-node/literal-rewrite/identity-rewrite/fixed-point-pass/blocker counts, safe-local-CSE preview pass/candidate-expression/duplicate-expression/blocker counts, safe-local-CSE materialization status/materialized-node/blocker counts, mad/FMA materialization status/materialized-node/text-replacement/fast-math/blocker counts, clamp/step/mix materialization status/materialized-node/text-replacement/payload/blocker counts, loop-vectorization materialization status/transformed-loop/text-replacement/typed-body-materialization/typed-body-invalidation/payload/blocker counts, typed dead-code preview pass/unreachable-node/blocker counts, typed dead-code materialization removed-node/payload/blocker counts, aggregate preview-readiness status, runtime-equivalence review eligibility, and review-package status. These fields are report-only and do not participate in source switching, selected-IR replacement, or production mutation gates.

Loop-vectorization evidence also includes `typedBody.rebuild.*` counters and status fields. These distinguish a validated reconstructed typed body from a review artifact where source text was vectorized but the typed body was intentionally invalidated because parsing, typed-body construction, or shared graph reachability validation failed. A rejected or invalidated typed-body rebuild blocks `loopVectorizationMaterialization.status=review-ready` even when the source rewrite and runtime-equivalence payload evidence are otherwise present.

## Immutable Proposal Flow

The optimizer contract is intentionally split into proposal and selection:

1. Build a `GpuIrOptimizationProposalRequest` from the original `IrGpu` artifact and `GpuIrOptimizationPolicy` context.
2. Run a `GpuIrOptimizationProposalProvider` that returns `NO_CHANGE`, `REJECTED`, or `PROPOSED`.
3. Run the validation sandwich through `GpuIrOptimizationSandwichRunner`.
4. Select the optimized artifact only when the original artifact validates, the optimized artifact validates, and mutation is explicitly allowed.

If mutation is disabled, a valid optimized artifact is reported as `PROPOSAL_ONLY` and the original IR remains selected. If post-validation fails, the runner reports `OPTIMIZED_INVALID_ROLLED_BACK` and keeps the original IR.

`GpuIrOptimizedArtifactCandidateBuilder` now materializes a separate optimized-artifact candidate envelope after optimized-artifact validation. The envelope records original/optimized IR identities, the deterministic candidate key, validation verdict, proof presence, rollback requirement, mutation policy, and selection blockers. It deliberately keeps `selectionReady=false`, `selectionApplied=false`, and `selectedIrReplacement=false`; final runtime IR selection remains a separate gate even when a candidate artifact exists.

### Policy Controls

`GpuIrOptimizationPolicy` is the backend-neutral control surface for optimizer providers and runtime adapters. It currently covers:

- `optimizerProfile` - high-level profile such as `off`, `diagnostic`, or future production profiles.
- `optimizationLevel` - shared optimization intensity hint independent of OpenCL/CUDA/Vulkan/Metal flags.
- `proposalOnly` - forces evidence collection without optimized IR selection.
- `mutationAllowed` - explicit opt-in required before the validation sandwich can select an optimized artifact.
- `fastMathAllowed` - explicit permission for future math-reassociation or precision-sensitive rewrites.
- `vendorAdaptationAllowed` - explicit permission for vendor/device-specific proposals to participate.
- `registerPressureSplittingAllowed` - explicit permission for future register-pressure/resource-shaping rewrites.
- `rollbackRequired` - records whether a proposal must provide rollback/fallback evidence.
- `proofRequired` - records whether proof/runtime-equivalence evidence is required for the proposal class.

`GpuIrOptimizationProposalRequest` preserves the older constructor shape for compatibility, but it now exposes a normalized `policy()` view and serializes `policy.*` fields into context metadata. Provider authors should read the policy instead of inventing backend-specific context keys. Defaults are fail-closed: proposal-only, mutation disabled, fast math disabled, vendor adaptation disabled, register-pressure splitting disabled, rollback required, and proof required.

### Runtime Bridge

`GpuIrOptimizationProposalRegistry` owns backend-neutral provider discovery. It loads `GpuIrOptimizationProposalProvider` implementations through ServiceLoader, orders providers deterministically, and rejects duplicate extension ids before runtime adaptation.

`GpuIrProposalRuntimeBridgePass` is the runtime adapter between the existing runtime optimizer pipeline and the immutable proposal-provider contract. It consumes providers from `GpuIrOptimizationProposalRegistry`, runs each provider through the validation sandwich, and reports the result as a runtime optimizer pass report.

The default bridge is intentionally proposal-only. It can surface canonicalization proposals, materialized review candidates, and proof metadata in runtime optimizer reports, but it does not select optimized IR unless explicit mutation permission is supplied by test wiring or the runtime compile option `runtime.irOptimizerSelection=experimental-apply`. Proposal-only materialized candidates are composed through a separate review-working artifact, so later providers can see earlier review transforms such as constant folding before safe-local-CSE and `mad/fma` materialization run. The final runtime `artifact` remains the original selected IR by default, while `candidateArtifact`, `optimized.irgpu.properties`, and `optimized.backend.opencl-c` carry the accumulated review candidate. This lets `ir-optimizer` participate in runtime evidence collection without enabling production mutation.

Runtime artifact dumping records bridge/provider evidence in `runtime-ir-optimizer-evidence.properties`. The artifact is filtered by the stable `javatogpu.ir-optimizer` provider prefix and includes pass counts, outcomes, proposal-only/selected counts, original/transformed IR identities, proof artifact fields, and diagnostics. This is an evidence surface only; it does not authorize optimized IR selection or production mutation by itself.

When a provider proposes an optimized artifact, bridge proof fields include `optimizedArtifactCandidate.*` metadata from the fail-closed candidate envelope. These fields make candidate identity, validation result, rollback requirement, mutation policy, and selection blockers visible without marking runtime selection as applied.

The runtime artifact also lifts those proof fields into top-level `optimizedArtifactCandidate.*` aggregate evidence: candidate status/counts, ready/blocked counts, first blockers, selection-ready/applied counts, selected-IR replacement counts, mutation-policy count, and explicit disabled selection/replacement guardrails. The generated OpenCL Markdown report shows candidate status and selection blockers per kernel. `validateOpenClRuntimeIrOptimizerEvidence` fails if candidate selection or selected-IR replacement becomes enabled in normal CI mode; local experiments that deliberately compile the optimized artifact must pass `--allow-experimental-apply` and carry `experimentalApply.*` evidence.

For local runtime experiments, use `GpuRuntimeCompileOptions.openClIrOptimizerExperimentalApply(...)`. This sets `runtime.irOptimizerSelection=experimental-apply`, allows the bridge to select a validated optimized artifact, and makes OpenCL compile the optimized backend source when selection survives runtime-equivalence and production gates. It is not a production switch: production-like profiles still go through the existing production IR acceptance gate, and the default `openCl(...)` / artifact-journal path remains review-only.

Runtime peephole/InstCombine proof is also kept read-only. `GpuRuntimeIrPeepholeReplacementPlan` records future structural replacement intent, including root node, covered node ids, input node ids, replacement kind, completeness, and first blocker. `GpuRuntimeIrTypedNodeGraph` is the shared read-only traversal view for typed peephole rules, and `GpuRuntimeIrPeepholeReplacementPlanValidation` checks each emitted plan against that graph before it is surfaced. `GpuRuntimeIrPeepholeTypedRewriteVisitor` is the shared traversal preflight above validated plans: it records deterministic visit order, visitor-ready/blocked counts, first visitor blocker, `visitorImplemented=true`, and disabled replacement-builder / transformed-IR / mutation / selected-IR flags. `GpuRuntimeIrPeepholeReplacementBlueprint` sits above visitor-ready plans as a read-only target-node contract: it records intended intrinsic-call node kind, target operation, argument node ids/roles, blueprint-ready/blocked counts, first blueprint blocker, and disabled replacement-builder / transformed-IR / mutation / selected-IR flags. `GpuRuntimeIrPeepholeRewriteTransactionPreflight` then describes the future graph transaction shape: replaced root ids, removed covered ids, retained input ids, planned added-node count, `plannedAddedNodeIds=not-allocated`, and disabled node-id allocator / graph-rewrite / transformed-IR / mutation / selected-IR flags. `GpuRuntimeIrPeepholeNodeIdAllocationPreflight` sits above transaction-ready plans as a diagnostic-only allocation preview: it records current graph max node id, deterministic candidate node ids, allocation-ready/blocked counts, first blocker, and disabled id reservation / allocator application / graph-rewrite / transformed-IR / mutation / selected-IR guardrails. `GpuRuntimeIrPeepholeReplacementNodePreflight` then pairs that candidate id with the intrinsic-call blueprint as a diagnostic-only replacement-node construction preview, keeping `replacementNodeBuilt=false`, `replacementBuilderImplemented=false`, and graph-rewrite / transformed-IR / mutation / selected-IR disabled. `GpuRuntimeIrPeepholeGraphPatchPreflight` records the next read-only patch preview: replacement node id, replaced/removed/retained/inserted node sets, graph-patch ready/blocked counts, first blocker, and disabled graph-patch application / graph-rewrite / transformed-IR / mutation / selected-IR guardrails. `GpuRuntimeIrPeepholeTransformedGraphPreflight` adds the materialization boundary above that patch: original IR identity, deterministic materialization key, `transformedGraphIdentity=not-built`, materialization-ready/blocked counts, first blocker, and disabled transformed-graph build / transformed-IR / graph-patch application / graph-rewrite / mutation / selected-IR guardrails. `GpuRuntimeIrPeepholeRewriteSketch` remains the mutation-free rewrite skeleton: a complete, valid replacement plan can become `sketch-ready`, but the sketch still records `rewriteBuilderImplemented=false`, `mutationAllowed=false`, and `selectedIrReplacement=false`; runtime equivalence and approval remain required. `GpuRuntimeIrPeepholeRewriteSelectionReadiness` now sits above ready/blocked sketches as a read-only selection preflight: it reports sketch counts, conflict counts, status, first blocker, missing rewrite-builder/conflict-resolution/proof/approval gates, and disabled mutation/selection flags without choosing any optimized IR. `GpuRuntimeIrPeepholeRewriteProofReadiness` then records the next fail-closed proof boundary: proof required/accepted state, runtime-equivalence payload presence/completeness, rollback evidence, approval acceptance, original IR identity, absent transformed IR identity, and disabled mutation / selected-IR replacement. `GpuRuntimeIrPeepholeRewriteReviewPackage` sits above proof readiness as the manual-review package boundary: it records required/complete state, conflict count, proof acceptance, runtime-equivalence payload, rollback, approval, manual-review-only state, and disabled mutation / selection / selected-IR replacement. The pass emits aggregate `rewriteVisitor.*`, `replacementBlueprint.*`, `rewriteTransaction.*`, `nodeIdAllocation.*`, `replacementNode.*`, `graphPatch.*`, `transformedGraph.*`, `rewriteSelection.*`, `rewriteProof.*`, and `rewriteReviewPackage.*` plus matching per-rule fields, so CI can identify which matcher family is blocked by graph validation, traversal, blueprint construction, transaction shaping, id-allocation preview, replacement-node preview, graph-patch preview, transformed-graph materialization, conflict resolution, missing runtime-equivalence payloads, missing rollback evidence, missing approval, or incomplete review packaging. Validation fields fail closed on missing roots, uncovered roots, or missing covered/input nodes; visitor fields prove only original-node traversal; blueprint fields describe only a future intrinsic-call shape; transaction fields describe only planned replace/remove/retain/add effects; allocation fields preview candidate ids only and keep `nodeIdsReserved=false` and `nodeIdAllocatorApplied=false`; replacement-node fields preview the synthetic node shape only and keep `replacementNodeBuilt=false`; graph-patch fields preview the future diff only and keep `graphPatchApplied=false`; transformed-graph fields preview materialization only and keep `transformedGraphBuilt=false`. They still do not reserve node ids, build replacement nodes, apply graph patches, build transformed graphs, rewrite graphs, or authorize mutation. The current matcher set covers `mad/fma` for `a*b+c` / `c+a*b`, `clamp` for `min(max(x, lo), hi)`, `step` for simple `x < edge ? 0 : 1` / `x >= edge ? 1 : 0` ternaries, `dot` for additive multiply trees such as `(a0*b0)+(a1*b1)`, and `mix` for `a + t * (b - a)` / `t * (b - a) + a`. `runtime-optimizer-drift.properties` aggregates complete/partial replacement-plan counts, structural plan-validation total/valid/invalid counts, rewrite-visitor ready/blocked counts, replacement-blueprint ready/blocked counts, rewrite-transaction ready/blocked counts, node-id allocation ready/blocked counts, replacement-node preview ready/blocked counts, graph-patch preview ready/blocked counts, transformed-graph materialization ready/blocked counts, rewrite-sketch ready/blocked counts, overlap/conflict counters, aggregate and per-rule `rewriteSelection.*` status/first-blocker guardrails, aggregate and per-rule `rewriteProof.*` proof/runtime-equivalence/rollback blockers, aggregate and per-rule `rewriteReviewPackage.*` package-completeness blockers, and compact `optimizerRule.*` summaries for CI drift detection. Workload gates carry those visitor/blueprint/transaction/allocation/replacement-node/graph-patch/transformed-graph fields forward with the rest of the drift payload, while validation reports/history, I3 summaries, and production-promotion explainability treat the optimizer state as evidence-only telemetry. The pass still reports `rewrite-engine-not-implemented` and never mutates the selected IR.

`GpuRuntimeIrPeepholeIrArtifactEnvelopePreflight` now sits above transformed-graph materialization as the future optimized-artifact boundary. It records the original IR identity, absent transformed graph and optimized artifact identities, deterministic envelope key, proof anchor, rollback anchor, ready/blocked counts, first blocker, and disabled artifact build / optimized-artifact build / transformed-IR / graph-patch / graph-rewrite / mutation / selected-IR flags. The pass emits aggregate and `rule.N.irArtifactEnvelope.*` fields, `runtime-optimizer-drift.properties` aggregates them, and workload gates carry them forward as evidence-only telemetry; no optimized artifact is constructed or selected.

`GpuRuntimeIrPeepholeArtifactProofBindingPreflight` records the next fail-closed proof-binding boundary between that artifact envelope and proof/review evidence. It reports binding counts, proof and review blockers, runtime-equivalence payload completeness, rollback evidence, approval state, and disabled proof/rollback/approval binding flags. Binding remains blocked until proof is accepted, runtime-equivalence payloads are complete, rollback evidence is clean, approval is accepted, and the manual-review package is complete; even then this preflight does not build optimized IR or select it.

`GpuRuntimeIrPeepholeOptimizedArtifactSelectionPreflight` records the final read-only selection boundary above proof binding. It reports whether proof binding is ready, whether production gate and mutation policy would still block selection, and keeps optimized-artifact selection, selection application, mutation, and selected-IR replacement disabled. This makes the future handoff to runtime IR selection auditable without changing the selected artifact.

The same evidence artifact now records `approvalTemplate.*` fields for each optimizer pass. Real proposal rewrites with distinct original/optimized identities and accepted proof metadata are marked `pending`; preview and no-change proposals are marked `not-applicable` with a first blocker such as `proposal-decision-not-proposed`. Runtime evidence also lifts payload-bound approval readiness into `approvalTemplate.runtimeEquivalencePayloadRequired.count`, `approvalTemplate.runtimeEquivalencePayloadPresent.count`, `approvalTemplate.runtimeEquivalencePayloadPassed.count`, and `approvalTemplate.runtimeEquivalencePayloadComplete.count`, so review packages can show whether pending approvals are bound to complete runtime-equivalence payloads without completing the package or selecting optimized IR. When the bridge has full proposal context, proof fields also include `approvalTemplate.resourcePath`; the dumper exposes this under `pass.N.approvalTemplate.field.resourcePath` as the review package location for the manifest candidate. `GpuIrOptimizationApprovalManifestLoader` then probes that deterministic classpath resource, validates any loaded manifest against the proposal/request binding, and emits `approvalManifest.*` proof fields. `reviewPackage.approvalManifest.*` records the package-level manifest boundary: required state, present/accepted counts, resource-path summary, first blocker, and disabled production mutation / selected-IR replacement. Missing, stale, or payload-incomplete manifests remain fail-closed; accepted manifests are auditable review evidence only and do not select optimized IR.

### Vendor Optimizer SPI

`GpuIrVendorOptimizationProposalProvider` is the optional vendor/device-specific contract. It receives a `GpuIrVendorOptimizationProposalRequest` with backend target, vendor, device id/label, driver/API text, device class, optimizer profile, mutation flag, and all original context fields. The provider still returns the same immutable `GpuIrOptimizationProposal`, so vendor proposals go through the same validation sandwich and rollback semantics as backend-neutral proposals.

`GpuIrVendorOptimizationProposalRegistry` loads vendor providers deterministically, rejects duplicate extension ids, and exposes an adapter back to `GpuIrOptimizationProposalProvider`. The default runtime bridge does not auto-load vendor providers; projects or future vendor modules must opt into that registry explicitly. This keeps `ir-optimizer` backend-neutral while leaving room for a separate `ir-vendor-optimizer` artifact.

### Packaging External Providers

Backend-neutral optimizer providers should depend on `javatogpu-ir-optimizer`, implement `GpuIrOptimizationProposalProvider`, and register the implementation in:

```text
META-INF/services/net.sixik.ga_utils.javatogpu.iroptimizer.GpuIrOptimizationProposalProvider
```

Vendor/device-specific optimizer providers should depend on `javatogpu-ir-vendor-optimizer` or `javatogpu-ir-optimizer`, implement `GpuIrVendorOptimizationProposalProvider`, and register the implementation in:

```text
META-INF/services/net.sixik.ga_utils.javatogpu.iroptimizer.GpuIrVendorOptimizationProposalProvider
```

Do not register a vendor provider in the backend-neutral provider descriptor unless it is intentionally safe for every backend/device context. The default runtime bridge loads only backend-neutral proposal providers. Vendor providers must be loaded explicitly through `GpuIrVendorOptimizationProposalRegistry`, adapted into the common proposal contract, and then passed through the same validation sandwich.

Provider authors must expose stable `extensionId`, `extensionVersion`, and `extensionOrder` values. Duplicate ids are rejected by the registry. A provider that cannot prove a safe rewrite should return `NO_CHANGE` or `REJECTED`; it should not return a proposed artifact without proof fields, diagnostics, and rollback/equivalence evidence.

### Approval Manifest

`GpuIrOptimizationApprovalManifest` writes a review-only approval template for one immutable optimizer proposal. The template is bound to the optimizer id/version, original and optimized `IrGpu` identities, backend target, optimization profile, optional device vendor/label context, proof source/verdict, runtime-equivalence payload binding when required, and rollback requirement. It is intended for packaging under:

```text
META-INF/javatogpu/ir-optimization-approvals/
```

Validation requires `status=approved`, explicit approval id/author/timestamp, matching proposal identities, matching backend/device context, matching proof metadata, matching runtime-equivalence payload resource/comparison/case metadata when the proposal requires payload evidence, `binding.rollback.required=true`, and `authorization.productionMutation=disabled`. Required payloads must be present, passed, component-complete, and have at least one recorded case. A valid manifest is still `manual-review-only`: it proves that the proposal was reviewed, but it does not select optimized IR or grant production mutation by itself.

`GpuIrOptimizationApprovalTemplateFormatter` is the bridge between proposal evidence and approval artifacts. It returns a pending template only for a real `PROPOSED` rewrite with distinct original/optimized identities and accepted proof metadata, emits a deterministic `manifest.resourcePath` under `META-INF/javatogpu/ir-optimization-approvals/`, and exposes compact `runtimeEquivalencePayload.*` template fields so review packages can show whether approval is bound to complete payload evidence. Preview or `NO_CHANGE` proposals return `not-applicable` with a first blocker such as `proposal-decision-not-proposed`, so report tooling can expose why no approval template should be packaged yet.

</details>

## Read Next

- [IR Validation](IR-Validation.md)
- [Runtime Guide](Runtime-Guide.md)
- [Public API and Extension Contract](Public-API-And-Extension-Contract.md)

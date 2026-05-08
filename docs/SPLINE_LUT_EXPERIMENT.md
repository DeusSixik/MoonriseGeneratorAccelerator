# Spline LUT Experiment

This document describes the current experimental Look-Up Table (LUT) path for
`DensityFunctions.Spline` code generation in the Density Function Compiler.

## Status

The LUT path is currently **experimental** and **opt-in**.

It is **not enabled by default** because testing showed that:

- large interior splines (`>= 9` points) can benefit a lot from LUT-guided
  segment selection;
- medium splines (`5..8` points) can regress badly when forced through the same
  path;
- the effect is shape-dependent and can change with JIT behavior.

Because of that, the recommended configuration is:

- keep normal spline search mode on `auto`;
- keep the linear/binary threshold at its current default;
- enable LUT only when explicitly testing or profiling;
- use LUT only for splines with `>= 9` points.

## What the LUT path does

The current LUT implementation is still **exact**.

It does **not** approximate the spline value itself.

Instead, it:

1. builds a table that predicts the most likely spline segment for an interior
   coordinate;
2. performs a tiny runtime fix-up loop to walk to the true segment if the
   prediction lands near a boundary;
3. evaluates the existing cubic interpolation for that exact segment.

This means the generated value stays equivalent to the existing binary-search
  path, while potentially reducing the cost of segment selection for large
  splines.

## Recommended JVM flags

Enable runtime diagnostics together with LUT when testing:

```text
-Ddfc.codegen.splineRuntimeStats=true
-Ddfc.codegen.splineSegmentLut=true
```

Recommended optional tuning flags:

```text
-Ddfc.codegen.splineSegmentLutMinPoints=9
-Ddfc.codegen.splineSegmentLutBuckets=128
```

Current recommendation:

- `splineSegmentLut=true`
- `splineSegmentLutMinPoints=9`
- `splineSegmentLutBuckets=128`

## Runtime diagnostics

Use the in-game commands:

```text
/dfc splinestats reset
/dfc splinestats
/dfc splinestats top
```

Important fields:

- `lutCalls` — how often the LUT path was used;
- `lutAvgNs` — average cost of LUT-routed spline calls;
- `top` output — the hottest compiled spline roots/classes;
- `5..8` and `>=9` buckets — where spline time is really spent;
- `leftExt` / `rightExt` — how much work goes into extrapolation instead of
  interior interpolation.

## Findings from current testing

Current local testing suggests:

- `auto` binary search with the default threshold of `4` is a good baseline;
- LUT for `>= 9` points can significantly improve the hottest interior spline
  roots;
- expanding LUT to `5..8` points caused a clear regression and should be
  avoided for now;
- extrapolation-heavy roots should be treated separately from pure interior
  spline hot paths.

## Recommendation for now

Treat this path as a **targeted profiling/optimization feature**, not as a
global default.

If you want the safest useful setup today, use:

```text
-Ddfc.codegen.splineRuntimeStats=true
-Ddfc.codegen.splineSegmentLut=true
-Ddfc.codegen.splineSegmentLutMinPoints=9
```

If future testing across more seeds/worlds stays stable, this can later be
revisited for a broader automatic mode.

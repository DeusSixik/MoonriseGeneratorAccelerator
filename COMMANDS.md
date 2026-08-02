
# Developers

## Tests

- Vanilla `./gradlew runBenchmarks`
- Quick Surface Rule Compiler check `./gradlew :common:surfaceQuickBenchmark`
- Quick SurfaceProgram apply check `./gradlew :common:surfaceApplyQuickBenchmark`
- Quick SurfaceProgram apply without column/interval `./gradlew :common:surfaceApplyQuickBenchmark -PsurfaceColumnInterval=false`
- Quick Surface benchmarks without DAG optimizer `./gradlew :common:surfaceQuickBenchmark -PsurfaceDag=false`

## Benchmark Diagnostics

- Server benchmark tasks write normalized diagnostics JSON to `benchmark-dumps` by default; disable with `-PbenchmarkDiagnostics=false`.
- Override the output folder with `-PbenchmarkDumpDir=benchmark-dumps/my-run`; avoid spaces because loader JVM args are whitespace-split.
- Key fields: `extra.benchmark.chunksPerSecond`, `extra.benchmark.msPerChunk`, `extra.benchmark.runtime.allocation.allocatedBytesPerChunk`, `extra.benchmark.runtime.gc.collectionTimeMsPerChunk`, `extra.benchmark.runtime.memory.metaspaceUsedBytesDeltaPerChunk`, and `artifacts.parity`.
- Use `-PbenchmarkAllocationCounters=false` for the lowest-overhead run; JFR allocation artifacts still require `-Dga.diagnostics.jfr=true` / `-Dga.diagnostics.jfr.allocations=true`.

## Decoration Pipeline Benchmarks

- Quick fair comparison, metrics off `./gradlew runDecorationPipelineQuickBenchmarks -PbenchRuns=1`
- Quick profiling comparison, metrics on `./gradlew runDecorationPipelineQuickBenchmarks -PbenchRuns=1 -PquickPipelineMetrics=true`
- Quick fair comparison on the fixed benchmark seed `./gradlew runDecorationPipelineQuickBenchmarks -PbenchRuns=1 -PquickWorldSeed=918273645546372819`
- Short smoke `./gradlew runDecorationPipelineQuickBenchmarks -PbenchRuns=1 -PquickMaxBatches=4 -PquickStopTick=120 -PquickHaltTick=180`
- Pipeline-only smoke `./gradlew runDecorationPipelineQuickBenchmarks -PquickMode=pipeline -PquickMaxBatches=4 -PquickStopTick=120 -PquickHaltTick=180`
- Pipeline-only profiling smoke `./gradlew runDecorationPipelineQuickBenchmarks -PquickMode=pipeline -PquickPipelineMetrics=true -PquickMaxBatches=4 -PquickStopTick=120 -PquickHaltTick=180`
- Baseline-only smoke `./gradlew runDecorationPipelineQuickBenchmarks -PquickMode=baseline -PquickMaxBatches=4 -PquickStopTick=120 -PquickHaltTick=180`
- Faster watchdog smoke `./gradlew runDecorationPipelineQuickBenchmarks -PquickMode=pipeline -PquickMaxBatches=2 -PquickStopTick=80 -PquickHaltTick=120 -PquickStartupWatchdogSeconds=60 -PquickTickWatchdogSeconds=20`
- Longer comparison `./gradlew runDecorationPipelineBenchmarks -PbenchRuns=1`
- Longer comparison on the fixed benchmark seed `./gradlew runDecorationPipelineBenchmarks -PbenchRuns=1 -PbenchWorldSeed=918273645546372819`

## Fabric

### Client

- Vanilla `./gradlew :fabric:runClient`
- Moonrise `./gradlew :fabric:runClient -PwithMoonrise`

### Server
- Vanilla `./gradlew :fabric:runServer`
- Moonrise `./gradlew :fabric:runServer -PwithMoonrise`

[//]: # (- C2ME `./gradlew :fabric:runClient -PwithC2ME`)

## NeoForge

### Client
- Vanilla `./gradlew :neoforge:runClient`
- Moonrise `./gradlew :neoforge:runClient -PwithMoonrise`
- ModernFix `./gradlew :neoforge:runClient -PwithModernFix`

### Server
- Vanilla `./gradlew :neoforge:runServer`
- Moonrise `./gradlew :neoforge:runServer -PwithMoonrise`
- ModernFix `./gradlew :neoforge:runServer -PwithModernFix`

[//]: # (- C2ME `./gradlew :neoforge:runClient -PwithC2ME`)

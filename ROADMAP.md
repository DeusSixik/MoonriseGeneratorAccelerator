# Generator Accelerator: Development Roadmap

This document outlines the planned technical trajectory for Generator Accelerator (GA). Our roadmap is strictly driven by performance profiling and architectural necessity, not by feature creep.

## 🧭 Development Philosophy
1. **Targeted Versioning:** We do not chase every minor Minecraft update. Development is focused exclusively on established "LTS" modding versions (e.g., 1.20.1, 1.21.1) where the modding ecosystem actually thrives.
2. **Root Cause Over Band-Aids:** We rewrite core generation pipelines from the ground up using Data-Oriented Design (DOD) rather than patching the symptoms of bad Object-Oriented architecture.
3. **Data-Driven Decisions:** We rely on deterministic benchmarking and strict profiling. Without a non-combined Spark profile proving a bottleneck, we do not rewrite.
4. **Third-Party Interception (Not Maintenance):** If a popular third-party mod acts as a bottleneck, we implement fast-path overrides and optimized adapters (interception layer) rather than fully rewriting and maintaining their codebase.

## 🟢 Current Focus (Active Development)
*Tasks currently being worked on, profiled, and implemented in the main branch.*

- [ ] **DensityFunctions AST Compiler:** Implement a compiler to optimize the Abstract Syntax Tree (AST) of `DensityFunctions`. This will perform constant folding and branch pruning at initialization, drastically reducing runtime overhead during chunk generation.
- [ ] **Native Noise Module (C3) Refactor:** Rework the C3 Native Noise DLL to resolve RNG (Random Number Generator) state desync issues between the JVM and native execution, ensuring strict 1:1 vanilla parity.
- [ ] **Generation Threading Model Overhaul:**
    - Reduce lock contention across the chunk generation pipeline.
    - Introduce thread-local data layouts to avoid allocation storms.
    - Eliminate *false sharing* by implementing cache-line padding for hot generation structures.
- [ ] **NBT Serialization Optimization:** Overhaul the NBT data pipeline to minimize memory allocation and I/O overhead during chunk generation and saving phases.
- [ ] **C2ME Compatibility:** Ensure seamless integration and prevent mixin conflicts with the Concurrent Chunk Management Engine (C2ME).
- [ ] **1.20.1 Backporting:** Port the latest features and optimizations back to the 1.20.1 LTS environment.

## 🟡 Short-Term Targets (Next in Line)
*Identified bottlenecks that are next in the queue for a complete DOD rewrite.*

- [ ] **Memory Layout Optimization (SoA Migration):**
    - Convert Array of Structures (AoS) to Structure of Arrays (SoA) for hot data structures (e.g., Biome buffers, Heightmaps).
    - Align data for L1/L2 cache efficiency to reduce pointer chasing.
- [ ] **Allocation Audit & Enforcements:** Track allocations in hot paths and enforce strict zero-allocation policies using primitives and object pooling.
- [ ] **Third-Party Acceleration Layer:** Profile heavy third-party biome/structure mods and intercept their slow generation paths with optimized GA adapters.
- [ ] **Spark Profile Analysis:** Collect and analyze `/spark profiler start --thread * --not-combined` reports from the community to identify emerging bottlenecks.

## 🔴 Long-Term Architecture (Backlog)
*Massive systemic changes that require deep refactoring of Minecraft's engine and rigorous benchmarking.*

- [ ] **JNI Cost Model Evaluation & Expansion:**
    - Benchmark JNI call overhead vs. JVM JIT auto-vectorization.
    - Identify *only* the most profitable, predictable workloads (tight loops, SIMD-heavy math) before moving additional generation math into the native C3 module.
- [ ] **SIMD Verification:** Evaluate the adoption of the Panama Vector API (Incubator) as a pure-Java alternative to JNI for mathematical vectorization.
- [ ] **Cross-Platform Native Binaries:** Compile and distribute the C3 JNI module for macOS (ARM64).

## 🛑 Hall of Fame (Completed Milestones)
*Major architectural wins we have already shipped.*

- [x] **DOD Feature Sorter:** Eradicated `Stream API` and `TreeMap` from feature placement.
- [x] **Flat Block Structure:** Implemented `int[4096]` flat arrays to bypass `PalettedContainer` packing overhead.
- [x] **FlatClimateIndex:** High-performance spatial index for Minecraft climate parameter lookup.

### How to Suggest an Addition to the Roadmap
If you believe a specific vanilla system or a third-party mod needs a rewrite, open an Issue. **You MUST include a non-combined Spark profile** (`/spark profiler start --thread * --not-combined`) proving that the system is a bottleneck. Without data, we do not rewrite.

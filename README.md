# Generator Accelerator (1.21.1)

Generator Accelerator (GA) is a low-level optimization mod for Minecraft world generation, aimed at radically improving performance by restructuring the game's internal systems. The 1.21.1 version ports the full spectrum of Data-Oriented Design (DOD) optimizations from previous iterations, while introducing experimental Native execution modules and seamless integration with the modern 1.21+ modding ecosystem.

The project transitions standard object-oriented pipelines to DOD principles, introduces vectorization, eliminates the overhead of the Java Stream API in performance-critical sections, and optionally offloads heavy math to native binaries.

## Project Vision & Goals
The ultimate objective of Generator Accelerator is not just to patch poorly performing code, but to fundamentally rethink how Minecraft generates worlds. Our core tenets are:
1. **Modern Engine Standards**: To transition Minecraft's world generation to the architectural standards and paradigms of modern, high-performance game engines.
2. **Root Cause Eradication**: We are committed to a full, comprehensive rewrite of the entire Minecraft generation codebase. We refuse to apply band-aids to symptoms; our goal is to solve the root causes of performance bottlenecks.
3. **Uncompromising Ecosystem Optimization**: Performance takes precedence. If we encounter a third-party mod that operates inefficiently and bottlenecks the generation pipeline, we will rewrite its logic or integration to meet our standards.

## Technical Modules

### 1. Data and Memory Architecture
* **Flat Block Structure**: During chunk generation, data is stored in a flat `int[4096]` array. Unlike the vanilla `PalettedContainer`, this eliminates the delay and overhead of packing data with every single block change. The data is repacked into a standard container only after the generation phase is complete.
* **Paletted Container Optimization**: Search and retrieval algorithms have been heavily optimized. The complete removal of the Stream API and the introduction of custom raw data logic ensure minimal overhead when manipulating block palettes.
* **DOD Surface**: Landscape generation has been entirely transitioned to a Data-Oriented architecture, which significantly speeds up surface processing in worlds with heavy custom generation rules.

### 2. Biome & Climate Optimization
* **FlatClimateIndex**: A custom, high-performance spatial index for biome lookup.
  * **Flattened SoA (Structure of Arrays)**: Climate parameters are stored in contiguous arrays to ensure maximum cache locality.
  * **Warm-start heuristic**: Caches the index of the last successful node for instant queries in spatially adjacent points.
  * **Branchless Math**: Distance calculations utilize bitwise operations to minimize CPU branching.
* **LevelChunkSection**: Biome population is optimized through lazy array allocation and dedicated fast paths for uniform sections.

### 3. Features Optimization (Decorations)
This module acts as a complete replacement for vanilla placement methods:
* **Feature Sorter (DOD)**: The sorting algorithm was rewritten from a functional to an iterative style. Replacing `Stream API`, `TreeSet`, and `TreeMap` with `ObjectArrayList` and `TimSort` reduced assembly complexity to O(N) and minimized cache misses (highly L1 cache-friendly).
* **Placement & Context**: Eliminated `Stream.flatMap` overhead in `PlacedFeature`. `LongArrayList` is now used instead of streams for position lookups.
* **Ore & Tree Features**: Deep refactoring of ore and tree generation logic. The code has been stripped of inefficient abstractions, replacing them with direct primitive computations.

### 4. Noise & Density Functions
* **[Experimental] Native C3 Noise Module (JNI)**: An optional, ultra-fast noise generation module written entirely in **C3** and hooked directly into the JVM via JNI.
  * *Support:* Currently supports Linux and Windows (macOS support is pending).
  * *Note:* This module is **disabled by default** as the floating-point math differences in the native implementation may cause noticeable discrepancies in world generation compared to vanilla.
* **VectorNoise**: A vectorized Simplex noise generator optimized specifically for vertical columns.
  * Utilizes **Loop Unrolling (x4)** to assist the JIT compiler in vectorization (SIMD).
  * **Zero Allocation**: Absolutely no object creation within the hot generation loop.
* **Density Compiler**: Introduces a density function tree compiler with IR-level folding, CSE, marker-aware cache preservation, and noise-specialized code generation.

### 5. Geometry and Structures
* **Heightmap**: Accelerated heightmap calculation (Hole Punching). Skips empty sections during downward scanning using bitmasks. Zero memory allocation in the main loop.
* **Structures & Blender**: Optimized chunk blending logic and structure assembly algorithms.
* **Aquifer & Beardifier**: Implements C2ME-based optimized algorithms with additional custom tweaks to further reduce computational load.

## Technical Information & Ecosystem

The project is open-source and distributed under the **GPLv3** license.

### Documentation

- `docs/DENSITY_FUNCTION_COMPILER.md` - deep technical write-up for the Density Function Compiler.
- `docs/NATIVE_MODULES.md` - how bundled native modules are discovered, configured, built, and packaged.

### Version Support Policy
Please note that we do not plan to blindly chase every new Minecraft update. Given the invasive, low-level nature of our engine rewrites, porting is highly resource-intensive. Therefore, our development will strictly focus on versions that become established hubs for the modding community (e.g., major modding "LTS" versions). We update where the ecosystem thrives, not just where the version number goes.

### Moonrise Integration
Unlike previous versions, the 1.21.1 release boasts **full compatibility with the Moonrise chunk system**. Generator Accelerator works seamlessly alongside modern chunk handling APIs, ensuring maximum performance without breaking the 1.21+ ecosystem.

### Credits and Origins
* **C2ME**: Logic for noise and aquifer optimizations.
* **CanvasMC**: Served as an aggregator of advanced solutions and an inspiration for Density Functions optimization.
* **Noisium**: Original baseline comparison for biome section optimizations.

### Requirements
* Minecraft 1.21.1
* Fabric Loader OR NeoForge
* *(Optional)* Supported OS (Windows/Linux) for Native C3 JNI Execution


## 🛠 Contributing & Profiling (How to Help)
We highly welcome community assistance in hunting down bottlenecks! However, to effectively optimize at this low level, we need granular data.

If you want to help development by providing performance reports, **you MUST generate your Spark profile using the following command:**
```mcfunction
/spark profiler start --thread * --not-combined
```

Why? Standard profiles combine threads, which obscures the specific worker threads executing world generation. We need non-combined, thread-specific data to see exactly where the CPU is stalling.

> [!WARNING]
> This mod is in the ALPHA stage. It makes invasive, low-level changes to the generation engine. Always make backups of your worlds before installing or enabling experimental native modules.

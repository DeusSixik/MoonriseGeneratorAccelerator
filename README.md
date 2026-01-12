# Moonrise Generator Accelerator

A performance mod that brings parts of **BTS Concurrent** chunk-generation work into the **Moonrise** ecosystem.

Moonrise already solves several hard chunk-system problems (the kind we would have been tackling for months). This project builds on that foundation and focuses specifically on **pushing world generation throughput further** for cases where “Moonrise is fast enough” stops being true — large modpacks, heavy worldgen stacks, and servers where generation speed is critical.

## Why use this mod?

Moonrise rewrites the ChunkSystem and for many vanilla-like setups its speed is already more than enough.

However, on:
- **large modpacks** with many worldgen features,
- **servers** that generate a lot of new terrain,
- **aggressive pregen** / exploration-heavy gameplay,
- **CPU-bound generation** scenarios,

…extra parallelism and generator-side optimizations can still provide a noticeable improvement.  
That’s where Moonrise Generator Accelerator comes in.


## Features (high level)

- Focus on **chunk generation acceleration** on top of Moonrise’s ChunkSystem work
- Integrates concurrency/throughput ideas originally developed in **BTS Concurrent**
- Designed for **heavier modded environments** and high-gen workloads

> [!NOTE]
> - Exact gains depend heavily on modpack composition, CPU, and server settings.
> - This README intentionally stays high-level; implementation details may evolve quickly.


## Requirements / Compatibility

- Requires **Moonrise**.
- Minecraft + loader compatibility depends on the release you download (see the version/file name and changelog).

## Supported Moonrise versions

Moonrise Compats follows the **same base version as Moonrise**.

Example:

- `Moonrise-NeoForge 0.1.0-beta.15+2eae1b1`
- `MoonriseGeneratorAccelerator 0.1.0-beta.15+2eae1b1.1`

The trailing suffix:

- `.1 ... .x`

means **Moonrise Compats patch revisions** (compatibility fixes / updates) for that exact Moonrise build.

So:

- `...+2eae1b1.1` = first Compats patch for Moonrise build `+2eae1b1`
- `...+2eae1b1.2` = second patch, etc.

## License

This project is licensed under **GNU GPL v3**.

We use portions of code from the server core **CanvasMC**, which itself incorporates code originating from **C2ME**.  
Because CanvasMC is distributed under **GNU GPL v3**, this project is also distributed under **GPLv3**.

## Credits

- **Moonrise** — for the chunk system foundation this project builds upon
- **CanvasMC** — source of reused GPLv3 code portions
- **C2ME** — original source of some of the concepts/implementations used upstream

## Reporting issues

If something breaks, please include:

- Minecraft version
- Loader (NeoForge/Fabric/etc.)
- Exact versions of:
    - Moonrise
    - Moonrise Compats
    - The mod(s) that conflict
- `latest.log` (and crash report if present)
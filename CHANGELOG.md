# Changelog

## [1.21.1-1.6.0]

### Added

- Added the GA scheduler: separated lanes for noise, compile/warmup, workspace,
  transaction, serial, and commit/finalize work, with configurable worker counts,
  queue pressure controls, CPU target, heap pressure target, and commit/mailbox
  backlog throttles.
- Added an async chunk-status pipeline and custom chunk graph scheduler for
  worldgen stages such as biomes, noise, structure starts/references, surface,
  carvers, features, and spawn generation.
- Added GA Chunk Workspace infrastructure: chunk-local block IO, workspace
  pooling/runtime, terrain workspace passes, final repack, section dirty tracking,
  workspace write bridge, and workspace metrics.
- Added deterministic worldgen commit infrastructure: commit batches, ownership,
  ordering keys, conflict groups, collision policies, side-effect values,
  finalization plans, and a cross-chunk mailbox runtime.
- Added transaction and sandbox infrastructure for isolating explicit worldgen
  units before their writes are committed back to vanilla chunk state.
- Added worldgen profiling and safety classification systems, including registry
  scanning, effect flags, unit profiles, rollout metadata, optimizer decisions,
  parity samples, and diagnostics feedback.
- Added decoration pipeline upgrades: placement programs, section descriptors,
  read snapshots, workspace bridge support, conflict scheduler metrics, and
  richer pipeline metrics.
- Added more diagnostics output for worldgen, scheduler, workspace, commit,
  decoration, surface compiler, Feature VM, and density compiler paths.
- Added compatibility and safety work for Alex's Caves, Repurposed Structures,
  C2ME, ModernFix, Biolith, Confluence, TerraBlender, Biomes We've Gone,
  Biomes O' Plenty, Dynamic Trees, Galosphere, Terrain Slabs, and related
  worldgen integrations.
- Added broad unit-test coverage for scheduler, chunk pipeline, workspace,
  commit, transaction, mailbox, diagnostics, profiling, optimizer, and decoration
  pipeline components.

### Changed

- Changed mod version to `1.21.1-1.6.0`.
- Changed mod initialization to initialize `GAScheduler` and expose a configurable
  custom pool for worldgen/noise execution.
- Changed feature generation toward the newer Decoration Pipeline; the older
  Feature VM path remains available for compatibility, diagnostics, and
  benchmarking, but is deprecated internally.
- Changed chunk generation hot paths to use guarded scheduling, workspace-aware
  execution, and deterministic commit/fallback infrastructure instead of relying
  only on isolated per-method optimizations.
- Changed surface generation to use a stronger compiled/vectorized path where it
  is safe, while preserving vanilla fallback for unknown or incompatible surface
  rules.
- Changed density compiler and noise paths to prefer Java/JIT-safe behavior when
  native support is unavailable.
- Changed C2ME compatibility to cancel conflicting C2ME mixins/modules where GA
  already owns the same hot path.
- Changed ModernFix surface compatibility so GA defers its buildSurface rewrite
  and related Biolith/BWG/NatureSpirit MixinSquared handlers when ModernFix owns
  that surface path.
- Changed configuration with many new runtime toggles for chunk pipeline,
  custom graph scheduling, workspace runtime, final repack, dense section copy,
  mailbox limits, transaction sandboxing, and experimental decoration schedulers.

### Fixed

- Fixed multiple race-condition risks in worldgen planning and cache paths,
  including carver plan caching and density-function plan/cache handling.
- Fixed structure-threading safety issues by synchronizing/protecting structure
  data access and adding safer structure template/palette mixins.
- Fixed a frozen-ocean surface regression in the optimized surface path.
- Fixed C2ME `opts-worldgen-general` random instance mixin crashes by cancelling
  C2ME atomic/simple random redirects that conflict with GA random hot paths.
- Fixed ModernFix + Biolith startup crashes by disabling GA's Biolith surface
  MixinSquared handler when the underlying GA surface handler is intentionally
  disabled for ModernFix compatibility.
- Fixed Confluence `StructureTemplateMixin` startup crashes by preserving the
  vanilla `StructureTemplate.addToLists` hook inside GA's safer template capture
  path and carrying Confluence brush/droplet metadata through GA template load
  and save.
- Fixed several mod-compat regressions around Alex's Caves surface/features,
  Repurposed Structures vines, C2ME blender/allocs/DFC/noise mixins, and
  modded decoration/surface hooks.
- Fixed final workspace repack validation paths that could otherwise leave
  air-only-looking sections after workspace-only writes.
- Fixed and hardened benchmark/diagnostics behavior after removing the old native
  loader path.

### Removed

- Removed the old standalone `NoiseTesting` test entry point.

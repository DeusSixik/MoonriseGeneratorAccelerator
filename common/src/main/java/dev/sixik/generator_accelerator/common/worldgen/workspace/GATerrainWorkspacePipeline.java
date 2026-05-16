package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Staged terrain runtime over workspace-owned buffers.
 *
 * <p>It does not call Minecraft terrain internals. Future mixins can feed real
 * samplers here while the pass order and completion contract stay centralized.</p>
 */
public final class GATerrainWorkspacePipeline {
    private GATerrainWorkspacePipeline() {
    }

    public static Result run(GAChunkWorkspace workspace, Plan plan) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(plan, "plan");
        plan.validate();

        long importedBlocks = 0L;
        if (plan.blockImporter != null) {
            importedBlocks = workspace.importBlockIds(plan.blockImporter);
        }

        long biomeColumns = 0L;
        if (plan.biomeSampler != null) {
            biomeColumns = GATerrainWorkspacePasses.fillBiomes(workspace, plan.biomeSampler);
        }

        GATerrainWorkspacePasses.TerrainPreparationResult terrainPreparation =
                GATerrainWorkspacePasses.fillDensityAquiferAndMaterialize(
                        workspace,
                        plan.densitySampler,
                        plan.aquiferSampler,
                        plan.terrainBlockResolver
                );
        long densitySamples = terrainPreparation.densitySamples();
        long aquiferDecisions = terrainPreparation.aquiferDecisions();
        long terrainBlocksWritten = terrainPreparation.terrainBlocksWritten();
        long surfaceColumns = GATerrainWorkspacePasses.scanPreliminarySurfaceHeights(workspace, plan.surfacePredicate);
        GATerrainWorkspacePasses.CarverResult carverResult =
                GATerrainWorkspacePasses.fillAndApplyCarverMask(workspace, plan.carverPredicate, plan.airBlockId);
        long carvedMaskBlocks = carverResult.carvedMaskBlocks();
        long carvedChangedBlocks = carverResult.carvedChangedBlocks();
        workspace.markTerrainFinalized();

        return new Result(
                importedBlocks,
                biomeColumns,
                densitySamples,
                aquiferDecisions,
                terrainBlocksWritten,
                surfaceColumns,
                carvedMaskBlocks,
                carvedChangedBlocks,
                workspace.densityReady(),
                workspace.aquiferReady(),
                workspace.surfaceReady(),
                workspace.carverReady(),
                workspace.terrainFinalized()
        );
    }

    public static Result runBlocking(GAChunkWorkspace workspace, Plan plan) {
        AtomicReference<Result> result = new AtomicReference<>();
        try {
            GAScheduler.invokeBlocking(GAScheduler.Lane.WORKSPACE, () -> result.set(run(workspace, plan)));
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("terrain workspace pipeline interrupted", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("terrain workspace pipeline failed", cause);
        }
    }

    public static Plan plan(
            GATerrainWorkspacePasses.DensitySampler densitySampler,
            GATerrainWorkspacePasses.AquiferSampler aquiferSampler,
            GATerrainWorkspacePasses.SurfaceBlockPredicate surfacePredicate,
            GATerrainWorkspacePasses.CarverPredicate carverPredicate,
            int airBlockId
    ) {
        return new Plan(densitySampler, aquiferSampler, surfacePredicate, carverPredicate, airBlockId);
    }

    public static final class Plan {
        private final GATerrainWorkspacePasses.DensitySampler densitySampler;
        private final GATerrainWorkspacePasses.AquiferSampler aquiferSampler;
        private final GATerrainWorkspacePasses.SurfaceBlockPredicate surfacePredicate;
        private final GATerrainWorkspacePasses.CarverPredicate carverPredicate;
        private final int airBlockId;
        private GAChunkWorkspace.BlockIdReader blockImporter;
        private GATerrainWorkspacePasses.BiomeSampler biomeSampler;
        private GATerrainWorkspacePasses.TerrainBlockResolver terrainBlockResolver;

        private Plan(
                GATerrainWorkspacePasses.DensitySampler densitySampler,
                GATerrainWorkspacePasses.AquiferSampler aquiferSampler,
                GATerrainWorkspacePasses.SurfaceBlockPredicate surfacePredicate,
                GATerrainWorkspacePasses.CarverPredicate carverPredicate,
                int airBlockId
        ) {
            this.densitySampler = densitySampler;
            this.aquiferSampler = aquiferSampler;
            this.surfacePredicate = surfacePredicate;
            this.carverPredicate = carverPredicate;
            this.airBlockId = airBlockId;
        }

        public Plan importBlocks(GAChunkWorkspace.BlockIdReader blockImporter) {
            this.blockImporter = Objects.requireNonNull(blockImporter, "blockImporter");
            return this;
        }

        public Plan biomes(GATerrainWorkspacePasses.BiomeSampler biomeSampler) {
            this.biomeSampler = Objects.requireNonNull(biomeSampler, "biomeSampler");
            return this;
        }

        public Plan terrainBlocks(GATerrainWorkspacePasses.TerrainBlockResolver terrainBlockResolver) {
            this.terrainBlockResolver = Objects.requireNonNull(terrainBlockResolver, "terrainBlockResolver");
            return this;
        }

        private void validate() {
            Objects.requireNonNull(densitySampler, "densitySampler");
            Objects.requireNonNull(aquiferSampler, "aquiferSampler");
            Objects.requireNonNull(surfacePredicate, "surfacePredicate");
            Objects.requireNonNull(carverPredicate, "carverPredicate");
        }
    }

    public record Result(
            long importedBlocks,
            long biomeColumns,
            long densitySamples,
            long aquiferDecisions,
            long terrainBlocksWritten,
            long surfaceColumns,
            long carvedMaskBlocks,
            long carvedChangedBlocks,
            boolean densityReady,
            boolean aquiferReady,
            boolean surfaceReady,
            boolean carverReady,
            boolean terrainFinalized
    ) {
    }
}

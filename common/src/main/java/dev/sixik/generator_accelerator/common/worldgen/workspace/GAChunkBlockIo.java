package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;
import java.util.Objects;

/** Safe ChunkAccess adapter for detached workspace block-id import/repack. */
public final class GAChunkBlockIo {
    private static final GAConfig CONFIG = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
    private static final boolean VALIDATE_FINAL_REPACK = booleanProperty(
            "ga.chunkWorkspace.finalRepack.validate",
            CONFIG.enableWorkspaceFinalRepackValidation
    );
    private static final boolean DENSE_FINAL_SECTION_COPY = booleanProperty(
            "ga.chunkWorkspace.finalRepack.denseSectionCopy.enabled",
            CONFIG.enableWorkspaceDenseFinalSectionCopy
    );
    private static final int DENSE_FINAL_SECTION_COPY_THRESHOLD = intProperty(
            "ga.chunkWorkspace.finalRepack.denseSectionCopy.threshold",
            CONFIG.workspaceDenseFinalSectionCopyThreshold
    );
    private static final boolean TERRAIN_LAZY_AIR_IMPORT = booleanProperty(
            "ga.chunkWorkspace.terrain.lazyAirImport.enabled",
            CONFIG.enableWorkspaceTerrainLazyAirImport
    );

    private GAChunkBlockIo() {
    }

    public static long importToWorkspace(ChunkAccess chunk, GAChunkWorkspace workspace) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(workspace, "workspace");
        if (!workspace.imported()) {
            workspace.begin(chunk, true);
        } else {
            validateSameChunk(chunk, workspace);
        }

        long start = System.nanoTime();
        workspace.ensureBlockBufferCapacity(workspace.blockCount());
        workspace.disableLazyAirBlockBuffer();
        int[] blocks = workspace.blockIds();
        LevelChunkSection[] sections = chunk.getSections();
        int importedBlocks;
        if (isSectionAligned(workspace)) {
            importedBlocks = importAlignedSections(workspace, sections, blocks);
        } else {
            importedBlocks = importGeneric(workspace, sections, blocks);
        }
        workspace.clearCommittedBlockDirties();
        workspace.metrics().addImportNanos(System.nanoTime() - start);
        workspace.metrics();
        return importedBlocks;
    }

    public static boolean canInitializeAirWorkspace(ChunkAccess chunk) {
        Objects.requireNonNull(chunk, "chunk");
        LevelChunkSection[] sections = chunk.getSections();
        int sectionCount = chunk.getSectionsCount();
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            LevelChunkSection section = sectionAt(sections, sectionIndex);
            if (section != null && !section.hasOnlyAir()) {
                return false;
            }
        }
        return true;
    }

    public static long initializeAirWorkspace(ChunkAccess chunk, GAChunkWorkspace workspace) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(workspace, "workspace");
        if (!workspace.imported()) {
            workspace.begin(chunk, !TERRAIN_LAZY_AIR_IMPORT);
        } else {
            validateSameChunk(chunk, workspace);
        }

        long start = System.nanoTime();
        if (TERRAIN_LAZY_AIR_IMPORT) {
            workspace.initializeLazyAirBlockBuffer(airId());
            GAChunkWorkspaceMetrics.incrementTerrainLazyAirImports();
        } else {
            workspace.ensureBlockBufferCapacity(workspace.blockCount());
            workspace.disableLazyAirBlockBuffer();
            Arrays.fill(workspace.blockIds(), 0, workspace.blockCount(), airId());
        }
        workspace.clearCommittedBlockDirties();
        workspace.metrics().addImportNanos(System.nanoTime() - start);
        workspace.metrics();
        GAChunkWorkspaceMetrics.incrementTerrainAirImports();
        return workspace.blockCount();
    }

    private static int importAlignedSections(GAChunkWorkspace workspace, LevelChunkSection[] sections, int[] blocks) {
        int airId = airId();
        int importedBlocks = 0;
        for (int sectionIndex = 0; sectionIndex < workspace.sectionCount(); sectionIndex++) {
            int baseIndex = sectionIndex * GAChunkWorkspace.BLOCKS_PER_SECTION;
            LevelChunkSection section = sectionAt(sections, sectionIndex);
            if (section == null || section.hasOnlyAir()) {
                Arrays.fill(blocks, baseIndex, baseIndex + GAChunkWorkspace.BLOCKS_PER_SECTION, airId);
                importedBlocks += GAChunkWorkspace.BLOCKS_PER_SECTION;
                continue;
            }
            int[] raw = LevelChunkSection$FlatBlockArray.rawData(section);
            if (raw != null) {
                System.arraycopy(raw, 0, blocks, baseIndex, GAChunkWorkspace.BLOCKS_PER_SECTION);
                importedBlocks += GAChunkWorkspace.BLOCKS_PER_SECTION;
                continue;
            }
            for (int localY = 0; localY < GAChunkWorkspace.CHUNK_WIDTH; localY++) {
                int rowBase = baseIndex | (localY << 8);
                for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
                    int rowIndex = rowBase | (localZ << 4);
                    for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                        blocks[rowIndex | localX] = stateId(section.getBlockState(localX, localY, localZ));
                        importedBlocks++;
                    }
                }
            }
        }
        return importedBlocks;
    }

    private static int importGeneric(GAChunkWorkspace workspace, LevelChunkSection[] sections, int[] blocks) {
        int airId = airId();
        int importedBlocks = 0;
        int index = 0;
        int maxY = workspace.minBuildHeight() + workspace.buildHeight();
        for (int y = workspace.minBuildHeight(); y < maxY; y++) {
            int sectionIndex = sectionIndexForY(workspace, y);
            LevelChunkSection section = sectionAt(sections, sectionIndex);
            boolean airSection = section == null || section.hasOnlyAir();
            int[] raw = airSection ? null : LevelChunkSection$FlatBlockArray.rawData(section);
            int localY = y & 15;
            for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
                for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                    blocks[index++] = airSection
                            ? airId
                            : raw == null
                                    ? stateId(section.getBlockState(localX, localY, localZ))
                                    : raw[(localY << 8) | (localZ << 4) | localX];
                    importedBlocks++;
                }
            }
        }
        return importedBlocks;
    }

    public static long repackDirtySections(ChunkAccess chunk, GAChunkWorkspace workspace) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(workspace, "workspace");
        validateSameChunk(chunk, workspace);

        long writtenBlocks = 0L;
        int[] dirtySections = workspace.dirtySectionIndices();
        for (int dirtySection : dirtySections) {
            writtenBlocks += repackDirtySection(chunk, workspace, dirtySection);
        }
        workspace.clearCommittedBlockDirties();
        return writtenBlocks;
    }

    public static long repackDirtySection(ChunkAccess chunk, GAChunkWorkspace workspace, int sectionIndex) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(workspace, "workspace");
        validateSameChunk(chunk, workspace);
        LevelChunkSection[] sections = chunk.getSections();
        LevelChunkSection section = sectionAt(sections, sectionIndex);
        if (section == null) {
            throw new IllegalStateException("missing section " + sectionIndex);
        }

        LevelChunkSection$FlatBlockArray flat = section instanceof LevelChunkSection$FlatBlockArray array ? array : null;
        int[] raw = flat == null ? null : flat.bts$getRawBlockData();
        int dirtyBlocks = workspace.dirtyBlockCountInSection(sectionIndex);
        if (workspace.hasTerrainSectionOnlyDirtiesInSection(sectionIndex)
                || (raw != null && shouldDenseCopySection(dirtyBlocks))) {
            return repackDenseSectionCopy(chunk, workspace, section, sectionIndex);
        }

        long written;
        if (raw != null) {
            written = workspace.repackDirtyBlockRunsInSection(sectionIndex, (sectionLocalIndex, workspaceIndex, length) -> {
                int[] blockIds = workspace.blockIds();
                for (int offset = 0; offset < length; offset++) {
                    int localIndex = sectionLocalIndex + offset;
                    int blockId = blockIds[workspaceIndex + offset];
                    if (!flat.bts$setRawBlockStateForGeneration(localIndex, blockId)) {
                        section.setBlockState(
                                localIndex & 15,
                                (localIndex >>> 8) & 15,
                                (localIndex >>> 4) & 15,
                                FastBlockStateCache.getBlockState(blockId),
                                false
                        );
                    }
                }
            });
            validateOrRepairSection(chunk, workspace, section, sectionIndex, written);
            return written;
        }
        written = workspace.repackDirtyBlockRunsInSection(sectionIndex, (sectionLocalIndex, workspaceIndex, length) -> {
            int[] blockIds = workspace.blockIds();
            for (int offset = 0; offset < length; offset++) {
                int localIndex = sectionLocalIndex + offset;
                section.setBlockState(
                        localIndex & 15,
                        (localIndex >>> 8) & 15,
                        (localIndex >>> 4) & 15,
                        FastBlockStateCache.getBlockState(blockIds[workspaceIndex + offset]),
                        false
                );
            }
        });
        validateOrRepairSection(chunk, workspace, section, sectionIndex, written);
        return written;
    }

    public static long repackLocalTerrainDirtySection(ChunkAccess chunk, GAChunkWorkspace workspace, int sectionIndex) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(workspace, "workspace");
        validateSameChunk(chunk, workspace);
        LevelChunkSection section = sectionAt(chunk.getSections(), sectionIndex);
        if (section == null) {
            throw new IllegalStateException("missing section " + sectionIndex);
        }
        return repackDenseSectionCopy(chunk, workspace, section, sectionIndex, true);
    }

    public static long repackLocalTerrainDirtySections(
            ChunkAccess chunk,
            GAChunkWorkspace workspace,
            int[] sectionIndices
    ) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(sectionIndices, "sectionIndices");
        validateSameChunk(chunk, workspace);
        if (sectionIndices.length == 0) {
            return 0L;
        }

        long start = System.nanoTime();
        long writtenBlocks = 0L;
        long terrainCopies = 0L;
        LevelChunkSection[] sections = chunk.getSections();
        try {
            for (int sectionIndex : sectionIndices) {
                LevelChunkSection section = sectionAt(sections, sectionIndex);
                if (section == null) {
                    throw new IllegalStateException("missing section " + sectionIndex);
                }
                boolean terrainSectionOnly = workspace.hasTerrainSectionOnlyDirtiesInSection(sectionIndex);
                long written = forceFullSectionCopy(workspace, section, sectionIndex, true);
                if (terrainSectionOnly) {
                    workspace.clearCommittedTerrainSectionOnlyDirtiesInSection(sectionIndex);
                } else {
                    workspace.clearCommittedBlockDirtiesInSection(sectionIndex);
                }
                validateOrRepairSection(chunk, workspace, section, sectionIndex, written, true);
                writtenBlocks += written;
                if (terrainSectionOnly) {
                    terrainCopies++;
                }
            }
        } finally {
            workspace.metrics().addRepackNanos(System.nanoTime() - start);
        }
        GAChunkWorkspaceMetrics.addFinalRepackDenseSectionCopies(sectionIndices.length);
        GAChunkWorkspaceMetrics.addFinalRepackTerrainSectionCopies(terrainCopies);
        return writtenBlocks;
    }

    private static long repackDenseSectionCopy(
            ChunkAccess chunk,
            GAChunkWorkspace workspace,
            LevelChunkSection section,
            int sectionIndex
    ) {
        return repackDenseSectionCopy(chunk, workspace, section, sectionIndex, false);
    }

    private static long repackDenseSectionCopy(
            ChunkAccess chunk,
            GAChunkWorkspace workspace,
            LevelChunkSection section,
            int sectionIndex,
            boolean trustedTerrainCounts
    ) {
        long start = System.nanoTime();
        boolean terrainSectionOnly = workspace.hasTerrainSectionOnlyDirtiesInSection(sectionIndex);
        long written = forceFullSectionCopy(workspace, section, sectionIndex, trustedTerrainCounts);
        if (terrainSectionOnly) {
            workspace.clearCommittedTerrainSectionOnlyDirtiesInSection(sectionIndex);
        } else {
            workspace.clearCommittedBlockDirtiesInSection(sectionIndex);
        }
        workspace.metrics().addRepackNanos(System.nanoTime() - start);
        GAChunkWorkspaceMetrics.incrementFinalRepackDenseSectionCopies();
        if (terrainSectionOnly) {
            GAChunkWorkspaceMetrics.incrementFinalRepackTerrainSectionCopies();
        }
        validateOrRepairSection(chunk, workspace, section, sectionIndex, written, trustedTerrainCounts);
        return written;
    }

    public static long emergencyRepackDirtySections(ChunkAccess chunk, GAChunkWorkspace workspace) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(workspace, "workspace");
        validateSameChunk(chunk, workspace);

        long start = System.nanoTime();
        long writtenBlocks = 0L;
        int[] dirtySections = workspace.dirtySectionIndices();
        int[] targetSections = dirtySections.length == 0 && workspace.hasWorkspaceOnlyWrites()
                ? allSectionIndices(workspace)
                : dirtySections;
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex : targetSections) {
            LevelChunkSection section = sectionAt(sections, sectionIndex);
            if (section == null) {
                throw new IllegalStateException("missing section " + sectionIndex);
            }
            writtenBlocks += forceFullSectionCopy(workspace, section, sectionIndex);
            if (expectedNonAirBlocks(workspace, sectionIndex) > 0 && section.hasOnlyAir()) {
                throw new IllegalStateException("emergency workspace repair left non-air section marked air-only: "
                        + chunk.getPos() + " sectionIndex=" + sectionIndex);
            }
        }
        workspace.clearCommittedBlockDirties();
        workspace.metrics().addRepackNanos(System.nanoTime() - start);
        if (writtenBlocks > 0L) {
            GAChunkWorkspaceMetrics.incrementEmergencyRepacks();
        }
        return writtenBlocks;
    }

    private static int stateId(BlockState state) {
        return GA$BlockStateExtension.get(state).bts$getFastId();
    }

    private static int airId() {
        return Block.getId(Blocks.AIR.defaultBlockState());
    }

    private static void validateOrRepairSection(
            ChunkAccess chunk,
            GAChunkWorkspace workspace,
            LevelChunkSection section,
            int sectionIndex,
            long written
    ) {
        validateOrRepairSection(chunk, workspace, section, sectionIndex, written, false);
    }

    private static void validateOrRepairSection(
            ChunkAccess chunk,
            GAChunkWorkspace workspace,
            LevelChunkSection section,
            int sectionIndex,
            long written,
            boolean trustedTerrainCounts
    ) {
        if (!VALIDATE_FINAL_REPACK || written <= 0L) {
            return;
        }

        int expectedNonAir = trustedTerrainCounts && workspace.hasKnownTerrainSectionCounts(sectionIndex)
                ? workspace.terrainNonEmptyBlockCountInSection(sectionIndex)
                : expectedNonAirBlocks(workspace, sectionIndex);
        if (expectedNonAir <= 0 || !section.hasOnlyAir()) {
            return;
        }

        // A workspace-only section with non-air ids must never publish as air-only.
        forceFullSectionCopy(workspace, section, sectionIndex);
        if (section.hasOnlyAir()) {
            throw new IllegalStateException("workspace final repack left non-air section marked air-only for "
                    + chunk.getPos() + " sectionIndex=" + sectionIndex);
        }
        GAChunkWorkspaceMetrics.incrementFinalRepackRepairs();
    }

    private static int expectedNonAirBlocks(GAChunkWorkspace workspace, int sectionIndex) {
        int[] blockIds = workspace.blockIds();
        if (!workspace.blockBufferEnabled() || blockIds == null) {
            return 0;
        }
        if (workspace.lazyAirBlockBuffer() && !workspace.isLazyAirSectionInitialized(sectionIndex)) {
            return 0;
        }

        int sectionY = workspace.minSectionY() + sectionIndex;
        int sectionMinY = sectionY * GAChunkWorkspace.CHUNK_WIDTH;
        int minY = Math.max(workspace.minBuildHeight(), sectionMinY);
        int maxY = Math.min(workspace.minBuildHeight() + workspace.buildHeight(),
                sectionMinY + GAChunkWorkspace.CHUNK_WIDTH);
        int count = 0;
        for (int y = minY; y < maxY; y++) {
            int rowBase = (y - workspace.minBuildHeight()) << 8;
            for (int column = 0; column < GAChunkWorkspace.COLUMN_COUNT; column++) {
                if (!FastBlockStateCache.isEmpty(blockIds[rowBase | column])) {
                    count++;
                }
            }
        }
        return count;
    }

    private static long forceFullSectionCopy(
            GAChunkWorkspace workspace,
            LevelChunkSection section,
            int sectionIndex
    ) {
        return forceFullSectionCopy(workspace, section, sectionIndex, false);
    }

    private static long forceFullSectionCopy(
            GAChunkWorkspace workspace,
            LevelChunkSection section,
            int sectionIndex,
            boolean trustedTerrainCounts
    ) {
        LevelChunkSection$FlatBlockArray flat = section instanceof LevelChunkSection$FlatBlockArray array ? array : null;
        int[] blockIds = workspace.blockIds();
        boolean lazyUninitialized = workspace.lazyAirBlockBuffer() && !workspace.isLazyAirSectionInitialized(sectionIndex);
        if (!lazyUninitialized && flat != null && workspace.blockBufferEnabled() && blockIds != null && isSectionAligned(workspace)) {
            int sourceOffset = sectionIndex * GAChunkWorkspace.BLOCKS_PER_SECTION;
            if (trustedTerrainCounts && workspace.hasKnownTerrainSectionCounts(sectionIndex)
                    && flat.bts$copyRawBlockDataForGeneration(
                            blockIds,
                            sourceOffset,
                            workspace.terrainNonEmptyBlockCountInSection(sectionIndex),
                            workspace.terrainTickingBlockCountInSection(sectionIndex),
                            workspace.terrainTickingFluidCountInSection(sectionIndex),
                            workspace.terrainLightEmissionCountInSection(sectionIndex)
                    )) {
                return GAChunkWorkspace.BLOCKS_PER_SECTION;
            }
            if (flat.bts$copyRawBlockDataForGeneration(blockIds, sourceOffset)) {
                return GAChunkWorkspace.BLOCKS_PER_SECTION;
            }
        }

        int[] source = copyWorkspaceSection(workspace, sectionIndex);
        if (flat != null) {
            if (flat.bts$copyRawBlockDataForGeneration(source)) {
                return GAChunkWorkspace.BLOCKS_PER_SECTION;
            }
            if (flat.bts$getRawBlockData() != null) {
                throw new IllegalStateException("flat raw section rejected full workspace repair");
            }
        }

        for (int localY = 0; localY < GAChunkWorkspace.CHUNK_WIDTH; localY++) {
            for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
                int rowIndex = (localY << 8) | (localZ << 4);
                for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                    section.setBlockState(
                            localX,
                            localY,
                            localZ,
                            FastBlockStateCache.getBlockState(source[rowIndex | localX]),
                            false
                    );
                }
            }
        }
        return GAChunkWorkspace.BLOCKS_PER_SECTION;
    }

    private static int[] copyWorkspaceSection(GAChunkWorkspace workspace, int sectionIndex) {
        int[] source = new int[GAChunkWorkspace.BLOCKS_PER_SECTION];
        Arrays.fill(source, airId());

        int[] blockIds = workspace.blockIds();
        if (!workspace.blockBufferEnabled() || blockIds == null) {
            return source;
        }
        if (workspace.lazyAirBlockBuffer() && !workspace.isLazyAirSectionInitialized(sectionIndex)) {
            return source;
        }

        int sectionY = workspace.minSectionY() + sectionIndex;
        int sectionMinY = sectionY * GAChunkWorkspace.CHUNK_WIDTH;
        int minY = Math.max(workspace.minBuildHeight(), sectionMinY);
        int maxY = Math.min(workspace.minBuildHeight() + workspace.buildHeight(),
                sectionMinY + GAChunkWorkspace.CHUNK_WIDTH);
        for (int y = minY; y < maxY; y++) {
            int workspaceBase = (y - workspace.minBuildHeight()) << 8;
            int sectionBase = (y & 15) << 8;
            System.arraycopy(blockIds, workspaceBase, source, sectionBase, GAChunkWorkspace.COLUMN_COUNT);
        }
        return source;
    }

    private static int[] allSectionIndices(GAChunkWorkspace workspace) {
        int[] sections = new int[workspace.sectionCount()];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = i;
        }
        return sections;
    }

    private static LevelChunkSection sectionAt(LevelChunkSection[] sections, int sectionIndex) {
        return sectionIndex < 0 || sectionIndex >= sections.length ? null : sections[sectionIndex];
    }

    private static int sectionIndexForY(GAChunkWorkspace workspace, int y) {
        return Math.floorDiv(y, GAChunkWorkspace.CHUNK_WIDTH) - workspace.minSectionY();
    }

    private static boolean isSectionAligned(GAChunkWorkspace workspace) {
        return workspace.minBuildHeight() == workspace.minSectionY() * GAChunkWorkspace.CHUNK_WIDTH
                && workspace.buildHeight() == workspace.sectionCount() * GAChunkWorkspace.CHUNK_WIDTH;
    }

    private static boolean booleanProperty(String property, boolean fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int intProperty(String property, int fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean shouldDenseCopySection(int dirtyBlocks) {
        return DENSE_FINAL_SECTION_COPY
                && DENSE_FINAL_SECTION_COPY_THRESHOLD > 0
                && dirtyBlocks >= Math.min(GAChunkWorkspace.BLOCKS_PER_SECTION, DENSE_FINAL_SECTION_COPY_THRESHOLD);
    }

    private static void validateSameChunk(ChunkAccess chunk, GAChunkWorkspace workspace) {
        if (!workspace.imported()) {
            throw new IllegalStateException("workspace metadata is not imported");
        }
        if (chunk.getPos().x != workspace.chunkX() || chunk.getPos().z != workspace.chunkZ()) {
            throw new IllegalArgumentException("chunk does not match workspace metadata");
        }
        if (chunk.getMinBuildHeight() != workspace.minBuildHeight() || chunk.getHeight() != workspace.buildHeight()) {
            throw new IllegalArgumentException("chunk height does not match workspace metadata");
        }
    }
}

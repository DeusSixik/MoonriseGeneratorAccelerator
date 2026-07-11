package dev.sixik.generator_accelerator.common.surface_compiler.backend.vector;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.surface.GASurfaceChunkBiomeLookup;
import dev.sixik.generator_accelerator.common.surface.mixin.GABiomeManagerAccess;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorContextRequirements;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRuleCompiler;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode.GeneratedKernel;
import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionContext;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceSystem;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Objects;

/**
 * Fast section-oriented kernel for vanilla-compatible surface trees.
 *
 * <p>The generic mask interpreter calls Mojang SurfaceRule.tryApply for every
 * candidate block and then mirrors the result through section CoW. This kernel
 * keeps the same pre-mutation fallback rule, but executes the already-existing
 * vector rule implementation over copied raw section arrays and commits only
 * after the full rule tree completed successfully.</p>
 */
public final class VectorSurfaceKernel implements GeneratedKernel {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final Field DEFAULT_BLOCK = defaultBlockField();
    private static final Cache<SpecialBiomeGuardKey, Boolean> SPECIAL_BIOME_GUARD_CACHE = Caffeine.newBuilder()
            .maximumSize(16_384L)
            .build();

    private final VectorRule rootRule;
    private final int requiredContext;

    public VectorSurfaceKernel(VectorRule rootRule) {
        this.rootRule = rootRule;
        this.requiredContext = rootRule.requiredContext();
    }

    public static VectorSurfaceKernel compile(net.minecraft.world.level.levelgen.SurfaceRules.RuleSource ruleSource) {
        try {
            return new VectorSurfaceKernel(VectorRuleCompiler.compileRule(ruleSource));
        } catch (RuntimeException | LinkageError unsupported) {
            return null;
        }
    }

    @Override
    public boolean execute(SurfaceExecutionContext context) {
        if (context == null || this.rootRule == null) {
            return false;
        }
        Scratch scratch = SCRATCH.get();
        try {
            return execute0(context, scratch);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        } finally {
            scratch.clearRefs();
        }
    }

    private boolean execute0(SurfaceExecutionContext execution, Scratch scratch) {
        ChunkAccess chunk = execution.chunk();
        SurfaceSystem surfaceSystem = execution.surfaceSystem();
        if (chunk == null || surfaceSystem == null || execution.biomeManager() == null || execution.noiseChunk() == null) {
            return false;
        }

        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return false;
        }
        for (LevelChunkSection section : sections) {
            if (section != null && !(section instanceof LevelChunkSection$FlatBlockArray)) {
                return false;
            }
        }

        BlockState defaultBlock = defaultBlock(surfaceSystem);
        int defaultBlockId = GA$BlockStateExtension.get(defaultBlock).bts$getFastId();
        ChunkPos chunkPos = chunk.getPos();
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();

        Holder<Biome>[] surfaceBiomes = scratch.surfaceBiomes;
        VectorChunkContext vectorContext = scratch.vectorContext;
        if (vectorContext == null) {
            vectorContext = new VectorChunkContext(surfaceBiomes, defaultBlockId, execution.worldContext(), execution.randomState(), surfaceSystem);
            scratch.vectorContext = vectorContext;
        } else {
            vectorContext.reset(surfaceBiomes, defaultBlockId, execution.worldContext(), execution.randomState(), surfaceSystem);
        }

        GASurfaceChunkBiomeLookup biomeLookup = scratch.biomeLookup;
        try {
            scratch.pendingCount = 0;
            int requiredContext = this.requiredContext;
            boolean needsSurfaceHeights = (requiredContext & (VectorContextRequirements.SURFACE_HEIGHTS
                    | VectorContextRequirements.SURFACE_BIOMES
                    | VectorContextRequirements.PRELIMINARY_SURFACE)) != 0;
            vectorContext.prepareColumnState(chunk, needsSurfaceHeights);
            short[] surfaceHeights = vectorContext.surfaceHeights;

            if ((requiredContext & VectorContextRequirements.SURFACE_BIOMES) != 0) {
                int minQueryY = Integer.MAX_VALUE;
                int maxQueryY = Integer.MIN_VALUE;
                for (int idx = 0; idx < 256; idx++) {
                    int surfaceY = surfaceHeights[idx];
                    int queryY = execution.useLegacyRandomSource() ? 0 : surfaceY;
                    minQueryY = Math.min(minQueryY, queryY);
                    maxQueryY = Math.max(maxQueryY, queryY);
                }

                GABiomeManagerAccess biomeAccess = (GABiomeManagerAccess) (Object) execution.biomeManager();
                biomeLookup.prepare(
                        biomeAccess.bts$getNoiseBiomeSource(),
                        biomeAccess.bts$getBiomeZoomSeed(),
                        chunk,
                        execution.biomeManager(),
                        minQueryY,
                        maxQueryY
                );

                for (int idx = 0; idx < 256; idx++) {
                    int localX = idx & 15;
                    int localZ = idx >> 4;
                    int globalX = minBlockX + localX;
                    int globalZ = minBlockZ + localZ;
                    int surfaceY = surfaceHeights[idx];
                    Holder<Biome> biome = biomeLookup.getBiomeAt(scratch.biomePos.set(globalX, execution.useLegacyRandomSource() ? 0 : surfaceY, globalZ));
                    surfaceBiomes[idx] = biome;

                    if (biome.is(Biomes.ERODED_BADLANDS) || biome.is(Biomes.FROZEN_OCEAN) || biome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                        return false;
                    }
                }
            } else if (hasPotentialVanillaSpecialBiome(execution, chunk, surfaceHeights, needsSurfaceHeights)) {
                return false;
            }

            if ((requiredContext & VectorContextRequirements.SURFACE_DEPTHS) != 0) {
                vectorContext.prepareSurfaceDepthCache(surfaceSystem, minBlockX, minBlockZ);
            }
            if ((requiredContext & VectorContextRequirements.SECONDARY_SURFACE_NOISE) != 0) {
                vectorContext.prepareSecondarySurfaceNoiseCache(surfaceSystem, minBlockX, minBlockZ);
            }
            if ((requiredContext & VectorContextRequirements.PRELIMINARY_SURFACE) != 0) {
                vectorContext.preparePreliminarySurface(execution.noiseChunk(), minBlockX, minBlockZ);
            }

            Arrays.fill(scratch.previousSectionBottomDepths, 0);
            for (int sectionIndex = sections.length - 1; sectionIndex >= 0; sectionIndex--) {
                LevelChunkSection section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir()) {
                    Arrays.fill(scratch.previousSectionBottomDepths, 0);
                    continue;
                }
                int[] source = LevelChunkSection$FlatBlockArray.rawData(section);
                if (source == null) {
                    return false;
                }
                int[] working = Arrays.copyOf(source, source.length);
                int sectionStartY = chunk.getSectionYFromSectionIndex(sectionIndex) * 16;
                vectorContext.updateForSection(minBlockX, sectionStartY, minBlockZ);
                vectorContext.calculateStoneDepthsAndLoadStoneMask(working, scratch.previousSectionBottomDepths, scratch.stoneMask);
                if (scratch.stoneMask.isEmpty()) {
                    continue;
                }

                Mask4096 activeMask = scratch.activeMask;
                activeMask.copyFrom(scratch.stoneMask);

                this.rootRule.apply(working, activeMask, vectorContext);
                if (!Arrays.equals(source, working)) {
                    scratch.acquirePending().reset(section, working, source, sectionStartY, minBlockX, minBlockZ);
                }
            }

            for (int i = 0; i < scratch.pendingCount; i++) {
                if (!scratch.pendingSections[i].canCommit()) {
                    return false;
                }
            }
            for (int i = 0; i < scratch.pendingCount; i++) {
                if (!scratch.pendingSections[i].commit(chunk)) {
                    return false;
                }
            }

            return true;
        } finally {
            biomeLookup.dispose();
            Arrays.fill(surfaceBiomes, null);
            scratch.activeMask.clear();
            if (vectorContext != null) {
                vectorContext.clear();
            }
            scratch.clearPending();
        }
    }

    private static void publishWrite(ChunkAccess chunk, BlockPos pos, BlockState state) {
        for (Heightmap.Types type : Heightmap.Types.values()) {
            chunk.getOrCreateHeightmapUnprimed(type).update(pos.getX() & 15, pos.getY(), pos.getZ() & 15, state);
        }
        if (!state.getFluidState().isEmpty()) {
            chunk.markPosForPostprocessing(pos);
        }
        GAWorkspaceWriteBridge.mirrorCurrent(chunk, pos, state);
    }

    private static boolean hasPotentialVanillaSpecialBiome(
            SurfaceExecutionContext execution,
            ChunkAccess chunk,
            short[] surfaceHeights,
            boolean surfaceHeightsLoaded
    ) {
        int minQueryY = 0;
        int maxQueryY = 0;
        if (!execution.useLegacyRandomSource()) {
            minQueryY = Integer.MAX_VALUE;
            maxQueryY = Integer.MIN_VALUE;
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int xz = localX | (localZ << 4);
                    int surfaceY = surfaceHeightsLoaded
                            ? surfaceHeights[xz]
                            : chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) + 1;
                    minQueryY = Math.min(minQueryY, surfaceY);
                    maxQueryY = Math.max(maxQueryY, surfaceY);
                }
            }
        }

        GABiomeManagerAccess biomeAccess = (GABiomeManagerAccess) (Object) execution.biomeManager();
        var source = biomeAccess.bts$getNoiseBiomeSource();
        if (source == null) {
            return true;
        }

        ChunkPos chunkPos = chunk.getPos();
        int biomeOffset = 2;
        int qMinX = QuartPos.fromBlock(chunkPos.getMinBlockX() - biomeOffset);
        int qMaxX = QuartPos.fromBlock(chunkPos.getMinBlockX() + 15 - biomeOffset) + 1;
        int qMinZ = QuartPos.fromBlock(chunkPos.getMinBlockZ() - biomeOffset);
        int qMaxZ = QuartPos.fromBlock(chunkPos.getMinBlockZ() + 15 - biomeOffset) + 1;
        int qMinY = QuartPos.fromBlock(minQueryY - biomeOffset);
        int qMaxY = QuartPos.fromBlock(maxQueryY - biomeOffset) + 1;

        SpecialBiomeGuardKey key = new SpecialBiomeGuardKey(
                source,
                biomeAccess.bts$getBiomeZoomSeed(),
                chunkPos.x,
                chunkPos.z,
                qMinY,
                qMaxY
        );
        Boolean cached = SPECIAL_BIOME_GUARD_CACHE.getIfPresent(key);
        if (cached != null) {
            return cached;
        }

        boolean hasSpecialBiome = false;
        for (int qx = qMinX; qx <= qMaxX; qx++) {
            for (int qz = qMinZ; qz <= qMaxZ; qz++) {
                for (int qy = qMinY; qy <= qMaxY; qy++) {
                    if (isVanillaSpecialBiome(source.getNoiseBiome(qx, qy, qz))) {
                        hasSpecialBiome = true;
                        break;
                    }
                }
                if (hasSpecialBiome) {
                    break;
                }
            }
            if (hasSpecialBiome) {
                break;
            }
        }
        SPECIAL_BIOME_GUARD_CACHE.put(key, hasSpecialBiome);
        return hasSpecialBiome;
    }

    private static boolean isVanillaSpecialBiome(Holder<Biome> biome) {
        return biome != null
                && (biome.is(Biomes.ERODED_BADLANDS)
                || biome.is(Biomes.FROZEN_OCEAN)
                || biome.is(Biomes.DEEP_FROZEN_OCEAN));
    }

    private record SpecialBiomeGuardKey(
            Object source,
            long seed,
            int chunkX,
            int chunkZ,
            int qMinY,
            int qMaxY
    ) {
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof SpecialBiomeGuardKey key
                    && this.source == key.source
                    && this.seed == key.seed
                    && this.chunkX == key.chunkX
                    && this.chunkZ == key.chunkZ
                    && this.qMinY == key.qMinY
                    && this.qMaxY == key.qMaxY;
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(this.source), this.seed, this.chunkX, this.chunkZ, this.qMinY, this.qMaxY);
        }
    }

    private static BlockState defaultBlock(SurfaceSystem surfaceSystem) {
        if (DEFAULT_BLOCK == null) {
            return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        }
        try {
            return (BlockState) DEFAULT_BLOCK.get(surfaceSystem);
        } catch (IllegalAccessException e) {
            return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        }
    }

    private static Field defaultBlockField() {
        try {
            Field field = SurfaceSystem.class.getDeclaredField("defaultBlock");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static final class PendingSection {
        private LevelChunkSection section;
        private int[] working;
        private int[] changedIndices = new int[16];
        private int[] changedStateIds = new int[16];
        private int changedCount;
        private int sectionStartY;
        private int minBlockX;
        private int minBlockZ;

        private void reset(LevelChunkSection section, int[] working, int[] original, int sectionStartY, int minBlockX, int minBlockZ) {
            this.section = section;
            this.working = working;
            this.sectionStartY = sectionStartY;
            this.minBlockX = minBlockX;
            this.minBlockZ = minBlockZ;
            this.changedCount = 0;
            for (int index = 0; index < 4096; index++) {
                int newId = working[index];
                if (newId != original[index]) {
                    record(index, newId);
                }
            }
        }

        private boolean commit(ChunkAccess chunk) {
            if (!canCommit()) {
                return false;
            }
            LevelChunkSection$FlatBlockArray flatBlockArray = (LevelChunkSection$FlatBlockArray) this.section;
            if (!flatBlockArray.bts$copyRawBlockDataForGeneration(this.working)) {
                return false;
            }
            for (int i = 0; i < this.changedCount; i++) {
                int index = this.changedIndices[i];
                int localX = index & 15;
                int localZ = (index >>> 4) & 15;
                int localY = (index >>> 8) & 15;
                BlockState state = FastBlockStateCache.getBlockState(this.changedStateIds[i]);
                BlockPos pos = new BlockPos(this.minBlockX + localX, this.sectionStartY + localY, this.minBlockZ + localZ);
                publishWrite(chunk, pos, state);
            }
            return true;
        }

        private boolean canCommit() {
            return this.section instanceof LevelChunkSection$FlatBlockArray flatBlockArray
                    && this.working != null
                    && flatBlockArray.bts$getRawBlockData() != null;
        }

        private void clear() {
            this.section = null;
            this.working = null;
            this.changedCount = 0;
        }

        private void record(int index, int stateId) {
            if (this.changedCount == this.changedIndices.length) {
                int newLength = this.changedIndices.length << 1;
                this.changedIndices = Arrays.copyOf(this.changedIndices, newLength);
                this.changedStateIds = Arrays.copyOf(this.changedStateIds, newLength);
            }
            this.changedIndices[this.changedCount] = index;
            this.changedStateIds[this.changedCount] = stateId;
            this.changedCount++;
        }
    }

    private static final class Scratch {
        private final Holder<Biome>[] surfaceBiomes = new Holder[256];
        private final GASurfaceChunkBiomeLookup biomeLookup = new GASurfaceChunkBiomeLookup();
        private final BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos();
        private final int[] previousSectionBottomDepths = new int[256];
        private final Mask4096 stoneMask = new Mask4096();
        private final Mask4096 activeMask = new Mask4096();
        private PendingSection[] pendingSections = new PendingSection[16];
        private int pendingCount;
        private VectorChunkContext vectorContext;

        private PendingSection acquirePending() {
            int index = this.pendingCount;
            if (index == this.pendingSections.length) {
                this.pendingSections = Arrays.copyOf(this.pendingSections, this.pendingSections.length << 1);
            }
            PendingSection pending = this.pendingSections[index];
            if (pending == null) {
                pending = new PendingSection();
                this.pendingSections[index] = pending;
            }
            this.pendingCount = index + 1;
            return pending;
        }

        private void clearPending() {
            for (int i = 0; i < this.pendingCount; i++) {
                this.pendingSections[i].clear();
            }
            this.pendingCount = 0;
        }

        private void clearRefs() {
            Arrays.fill(this.surfaceBiomes, null);
            this.activeMask.clear();
            clearPending();
        }
    }
}

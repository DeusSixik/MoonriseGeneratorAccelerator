package dev.sixik.generator_accelerator.common.surface.mixin;

import dev.sixik.generator_accelerator.api.mixin.InjectHelper;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.surface.GASurfaceChunkBiomeLookup;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceExecutor;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceMetrics;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceProgram;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceProgramCache;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceRequirements;
import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceScratch;
import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface.vector.VectorBlockColumn;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;

@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystem$new_build_surface {

    @Unique
    private static final ThreadLocal<SurfaceScratch> BTS$SURFACE_SCRATCH = ThreadLocal.withInitial(SurfaceScratch::new);

    @Unique
    private static final ThreadLocal<Holder<Biome>[]> BTS$SURFACE_BIOMES = ThreadLocal.withInitial(() -> new Holder[256]);
    @Unique
    private static final ThreadLocal<GASurfaceChunkBiomeLookup> BTS$CHUNK_BIOME_LOOKUP = ThreadLocal.withInitial(GASurfaceChunkBiomeLookup::new);
    @Unique
    private static final ThreadLocal<VectorChunkContext> BTS$VECTOR_CONTEXT = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$COLUMN_POS = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$BIOME_POS = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    @Unique
    private static final ThreadLocal<VectorBlockColumn> BTS$VECTOR_COLUMN = new ThreadLocal<>();

    @Unique
    private final SurfaceSystem bts$this = (SurfaceSystem) (Object) this;

    @Shadow
    @Final
    private BlockState defaultBlock;

    @Shadow
    protected abstract void erodedBadlandsExtension(BlockColumn blockColumn, int i, int j, int k, LevelHeightAccessor levelHeightAccessor);

    @Shadow
    protected abstract void frozenOceanExtension(int i, Biome biome, BlockColumn blockColumn, BlockPos.MutableBlockPos mutableBlockPos, int j, int k, int l);

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public void buildSurface(
            RandomState pRandomState,
            BiomeManager pBiomeManager,
            Registry<Biome> unused,
            boolean pUseLegacyRandomSource,
            WorldGenerationContext pContext,
            final ChunkAccess pChunk,
            NoiseChunk pNoiseChunk,
            SurfaceRules.RuleSource ruleSource
    ) {
        final GASurfaceChunkBiomeLookup bts$chunkBiome = BTS$CHUNK_BIOME_LOOKUP.get();
        try {
            final SurfaceProgram pSurfaceProgram = SurfaceProgramCache.getOrCompile(ruleSource);
            final SurfaceScratch scratch = BTS$SURFACE_SCRATCH.get();
            final ChunkPos chunkpos = pChunk.getPos();
            final int minBlockX = chunkpos.getMinBlockX();
            final int minBlockZ = chunkpos.getMinBlockZ();

            Holder<Biome>[] surfaceBiomes = BTS$SURFACE_BIOMES.get();
            int defaultBlockId = GA$BlockStateExtension.get(this.defaultBlock).bts$getFastId();
            VectorChunkContext ctx = BTS$VECTOR_CONTEXT.get();
            if (ctx == null) {
                ctx = new VectorChunkContext(surfaceBiomes, defaultBlockId, pContext, pRandomState, bts$this);
                BTS$VECTOR_CONTEXT.set(ctx);
            } else {
                ctx.reset(surfaceBiomes, defaultBlockId, pContext, pRandomState, bts$this);
            }
            boolean hasFrozenOcean = false;

            final LevelChunkSection[] sections = pChunk.getSections();
            final BlockPos.MutableBlockPos columnPos = BTS$COLUMN_POS.get();
            final BlockPos.MutableBlockPos biomePos = BTS$BIOME_POS.get();
            VectorBlockColumn fastColumn = BTS$VECTOR_COLUMN.get();
            if (fastColumn == null) {
                fastColumn = new VectorBlockColumn(pChunk, sections, columnPos);
                BTS$VECTOR_COLUMN.set(fastColumn);
            } else {
                fastColumn.reset(pChunk, sections, columnPos);
            }

            long biomePrepStart = SurfaceMetrics.startTimer();
            ctx.buildDepthMap(pChunk);
            short[] surfaceHeights = ctx.surfaceHeights;
            int minQueryY = Integer.MAX_VALUE;
            int maxQueryY = Integer.MIN_VALUE;
            for (int idx = 0; idx < 256; idx++) {
                int surfaceY = surfaceHeights[idx];
                int queryY = pUseLegacyRandomSource ? 0 : surfaceY;
                if (queryY < minQueryY) {
                    minQueryY = queryY;
                }
                if (queryY > maxQueryY) {
                    maxQueryY = queryY;
                }
            }

            GABiomeManagerAccess bts$access = (GABiomeManagerAccess) (Object) pBiomeManager;
            bts$chunkBiome.prepare(
                    bts$access.bts$getNoiseBiomeSource(),
                    bts$access.bts$getBiomeZoomSeed(),
                    pChunk,
                    pBiomeManager,
                    minQueryY,
                    maxQueryY
            );

            for (int idx = 0; idx < 256; idx++) {
                int x = idx & 15;
                int z = idx >> 4;
                int globalX = minBlockX + x;
                int globalZ = minBlockZ + z;
                int surfaceY = surfaceHeights[idx];

                Holder<Biome> biome = bts$chunkBiome.getBiomeAt(biomePos.set(globalX, pUseLegacyRandomSource ? 0 : surfaceY, globalZ));

                surfaceBiomes[idx] = biome;

                if (biome.is(Biomes.ERODED_BADLANDS)) {
                    columnPos.setX(globalX).setZ(globalZ);
                    this.erodedBadlandsExtension(fastColumn, globalX, globalZ, surfaceY, pChunk);
                } else if (biome.is(Biomes.FROZEN_OCEAN) || biome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                    hasFrozenOcean = true;
                }
            }
            SurfaceMetrics.recordBiomePrepTime(biomePrepStart);

            boolean needsSurfaceDepth = hasFrozenOcean
                    || pSurfaceProgram.requires(SurfaceRequirements.SURFACE_DEPTH | SurfaceRequirements.PRELIMINARY_SURFACE);
            if (needsSurfaceDepth) {
                long surfaceDepthStart = SurfaceMetrics.startTimer();
                ctx.prepareSurfaceDepthCache(bts$this, minBlockX, minBlockZ);
                SurfaceMetrics.recordSurfaceDepthTime(surfaceDepthStart);
            }
            if (pSurfaceProgram.requires(SurfaceRequirements.SECONDARY_SURFACE)) {
                long secondarySurfaceStart = SurfaceMetrics.startTimer();
                ctx.prepareSecondarySurfaceNoiseCache(bts$this, minBlockX, minBlockZ);
                SurfaceMetrics.recordSecondarySurfaceTime(secondarySurfaceStart);
            }
            if (hasFrozenOcean || pSurfaceProgram.requires(SurfaceRequirements.PRELIMINARY_SURFACE)) {
                long preliminarySurfaceStart = SurfaceMetrics.startTimer();
                ctx.preparePreliminarySurface(pNoiseChunk, minBlockX, minBlockZ);
                SurfaceMetrics.recordPreliminarySurfaceTime(preliminarySurfaceStart);
            }

            int[] previousSectionBottomDepths = scratch.previousSectionBottomDepths;
            boolean needsStoneDepths = pSurfaceProgram.requires(SurfaceRequirements.STONE_DEPTH | SurfaceRequirements.WATER);
            boolean previousDepthsZero = true;
            if (needsStoneDepths) {
                Arrays.fill(previousSectionBottomDepths, 0);
            }
            Mask4096 stoneMask = scratch.stoneMask;

            for (int sectionIndex = sections.length - 1; sectionIndex >= 0; sectionIndex--) {
                LevelChunkSection section = sections[sectionIndex];

                if (section == null || section.hasOnlyAir()) {
                    SurfaceMetrics.emptySectionSkipped();
                    if (needsStoneDepths && !previousDepthsZero) {
                        Arrays.fill(previousSectionBottomDepths, 0);
                        previousDepthsZero = true;
                    }
                    continue;
                }

                int[] rawBlockData = ((LevelChunkSection$FlatBlockArray) section).bts$getRawBlockData();
                if (rawBlockData == null) {
                    SurfaceMetrics.rawBlockArrayMiss();
                    continue;
                }
                SurfaceMetrics.sectionProcessed();

                int sectionStartY = pChunk.getSectionYFromSectionIndex(sectionIndex) * 16;
                ctx.updateForSection(minBlockX, sectionStartY, minBlockZ);
                if (needsStoneDepths) {
                    long stoneDepthStart = SurfaceMetrics.startTimer();
                    ctx.calculateStoneDepthsAndLoadStoneMask(rawBlockData, previousSectionBottomDepths, stoneMask);
                    SurfaceMetrics.recordStoneDepthTime(stoneDepthStart);
                    previousDepthsZero = false;
                } else {
                    long stoneMaskStart = SurfaceMetrics.startTimer();
                    stoneMask.loadMatchingBlockIds(rawBlockData, defaultBlockId);
                    SurfaceMetrics.recordStoneMaskLoadTime(stoneMaskStart);
                }

                if (stoneMask.isEmpty()) {
                    SurfaceMetrics.stonelessSectionSkipped();
                    continue;
                }

                long programApplyStart = SurfaceMetrics.startTimer();
                SurfaceExecutor.apply(rawBlockData, stoneMask, ctx, pSurfaceProgram, scratch);
                SurfaceMetrics.recordProgramApplyTime(programApplyStart);

                if (pSurfaceProgram.mayWriteFluid()) {
                    long fluidPostprocessStart = SurfaceMetrics.startTimer();
                    BlockPos.MutableBlockPos mutPos = scratch.postProcessPos;
                    long[] stoneWords = stoneMask.words();
                    for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
                        long word = stoneWords[wordIndex];
                        while (word != 0L) {
                            int bit = Long.numberOfTrailingZeros(word);
                            int i = (wordIndex << 6) + bit;
                            int newBlockId = rawBlockData[i];
                            if (newBlockId != defaultBlockId) {
                                BlockState newState = FastBlockStateCache.getBlockState(newBlockId);
                                if (!newState.getFluidState().isEmpty()) {
                                    int lx = i & 15;
                                    int lz = (i >> 4) & 15;
                                    int ly = (i >> 8) & 15;
                                    pChunk.markPosForPostprocessing(mutPos.set(minBlockX + lx, sectionStartY + ly, minBlockZ + lz));
                                }
                            }
                            word &= word - 1L;
                        }
                    }
                    SurfaceMetrics.recordFluidPostprocessTime(fluidPostprocessStart);
                }
            }

            if (hasFrozenOcean) {
                long frozenOceanStart = SurfaceMetrics.startTimer();
                for (int idx = 0; idx < 256; idx++) {
                    Holder<Biome> biome = surfaceBiomes[idx];
                    if (biome.is(Biomes.FROZEN_OCEAN) || biome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                        int x = idx & 15;
                        int z = idx >> 4;
                        int globalX = minBlockX + x;
                        int globalZ = minBlockZ + z;
                        int surfaceY = surfaceHeights[idx];

                        columnPos.setX(globalX).setZ(globalZ);
                        biomePos.set(globalX, pUseLegacyRandomSource ? 0 : surfaceY, globalZ);

                        this.frozenOceanExtension(ctx.minSurfaceLevels[idx], biome.value(), fastColumn, biomePos, globalX, globalZ, surfaceY);
                    }
                }
                SurfaceMetrics.recordFrozenOceanTime(frozenOceanStart);
            }

            InjectHelper.inject();
        } finally {
            bts$chunkBiome.dispose();
        }
    }
}

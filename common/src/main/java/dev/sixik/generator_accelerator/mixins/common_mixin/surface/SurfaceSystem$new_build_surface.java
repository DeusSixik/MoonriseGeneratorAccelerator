package dev.sixik.generator_accelerator.mixins.common_mixin.surface;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.surface.GASurfaceChunkBiomeLookup;
import dev.sixik.generator_accelerator.common.surface.vector.VectorBlockColumn;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRuleCompiler;
import dev.sixik.generator_accelerator.common.surface.vector.VectorSurfaceRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.*;
import org.spongepowered.asm.mixin.*;

import java.util.Arrays;
import java.util.BitSet;

@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystem$new_build_surface {

    @Unique
    private static final ThreadLocal<BitSet> BTS$STONE_MASK = ThreadLocal.withInitial(() -> new BitSet(4096));
    @Unique
    private static final ThreadLocal<BitSet> BTS$WORKING_MASK = ThreadLocal.withInitial(() -> new BitSet(4096));

    @Unique
    private static final ThreadLocal<Holder<Biome>[]> BTS$SURFACE_BIOMES = ThreadLocal.withInitial(() -> new Holder[256]);
    @Unique
    private static final ThreadLocal<GASurfaceChunkBiomeLookup> BTS$CHUNK_BIOME_LOOKUP = ThreadLocal.withInitial(GASurfaceChunkBiomeLookup::new);

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
        GABiomeManagerAccess bts$access = (GABiomeManagerAccess) (Object) pBiomeManager;
        bts$chunkBiome.prepare(
                bts$access.bts$getNoiseBiomeSource(),
                bts$access.bts$getBiomeZoomSeed(),
                pChunk,
                pBiomeManager
        );
        try {
            final VectorSurfaceRules pVectorRules = new VectorSurfaceRules(VectorRuleCompiler.compileRule(ruleSource));
            final ChunkPos chunkpos = pChunk.getPos();
            final int minBlockX = chunkpos.getMinBlockX();
            final int minBlockZ = chunkpos.getMinBlockZ();

            Holder<Biome>[] surfaceBiomes = BTS$SURFACE_BIOMES.get();
            boolean hasFrozenOcean = false;

            final LevelChunkSection[] sections = pChunk.getSections();
            final BlockPos.MutableBlockPos columnPos = new BlockPos.MutableBlockPos();
            final VectorBlockColumn fastColumn = new VectorBlockColumn(pChunk, sections, columnPos);
            final BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos();

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int globalX = minBlockX + x;
                    int globalZ = minBlockZ + z;
                    int surfaceY = pChunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;

                    Holder<Biome> biome = bts$chunkBiome.getBiomeAt(biomePos.set(globalX, pUseLegacyRandomSource ? 0 : surfaceY, globalZ));

                    surfaceBiomes[x | (z << 4)] = biome;

                    if (biome.is(Biomes.ERODED_BADLANDS)) {
                        columnPos.setX(globalX).setZ(globalZ);
                        this.erodedBadlandsExtension(fastColumn, globalX, globalZ, surfaceY, pChunk);
                    } else if (biome.is(Biomes.FROZEN_OCEAN) || biome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                        hasFrozenOcean = true;
                    }
                }
            }

            VectorChunkContext ctx = new VectorChunkContext(surfaceBiomes, this.defaultBlock, pContext, pRandomState, bts$this);

            ctx.buildDepthMap(pChunk);
            ctx.prepareNoiseCaches(bts$this, minBlockX, minBlockZ);
            ctx.preparePreliminarySurface(pNoiseChunk, minBlockX, minBlockZ);

            int[] previousSectionBottomDepths = new int[256];
            BitSet stoneMask = BTS$STONE_MASK.get();
            BitSet workingMask = BTS$WORKING_MASK.get();

            for (int sectionIndex = sections.length - 1; sectionIndex >= 0; sectionIndex--) {
                LevelChunkSection section = sections[sectionIndex];

                if (section == null || section.hasOnlyAir()) {
                    Arrays.fill(previousSectionBottomDepths, 0);
                    continue;
                }

                int[] rawBlockData = ((LevelChunkSection$FlatBlockArray) section).bts$getRawBlockData();
                if (rawBlockData == null) continue;

                int sectionStartY = pChunk.getSectionYFromSectionIndex(sectionIndex) * 16;
                ctx.updateForSection(minBlockX, sectionStartY, minBlockZ);
                ctx.calculateStoneDepths(rawBlockData, previousSectionBottomDepths);

                stoneMask.clear();
                int defaultBlockId = GA$BlockStateExtension.get(this.defaultBlock).bts$getFastId();

                for (int i = 0; i < 4096; i++) {
                    if (rawBlockData[i] == defaultBlockId) stoneMask.set(i);
                }

                if (stoneMask.isEmpty()) continue;

                workingMask.clear();
                workingMask.or(stoneMask);

                pVectorRules.applyToSection(rawBlockData, workingMask, ctx);

                BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
                for (int i = stoneMask.nextSetBit(0); i >= 0; i = stoneMask.nextSetBit(i + 1)) {
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
                }
            }

            if (hasFrozenOcean) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int idx = x | (z << 4);
                        Holder<Biome> biome = surfaceBiomes[idx];

                        if (biome.is(Biomes.FROZEN_OCEAN) || biome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                            int globalX = minBlockX + x;
                            int globalZ = minBlockZ + z;
                            int surfaceY = pChunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;

                            columnPos.setX(globalX).setZ(globalZ);
                            biomePos.set(globalX, pUseLegacyRandomSource ? 0 : surfaceY, globalZ);

                            int minSurfaceLvl = ctx.minSurfaceLevels[idx];
                            this.frozenOceanExtension(minSurfaceLvl, biome.value(), fastColumn, biomePos, globalX, globalZ, surfaceY);
                        }
                    }
                }
            }
        } finally {
            bts$chunkBiome.dispose();
        }
    }
}

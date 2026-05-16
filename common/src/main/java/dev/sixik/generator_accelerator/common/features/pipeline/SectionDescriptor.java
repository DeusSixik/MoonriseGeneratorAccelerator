package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;

import java.util.Arrays;
import java.util.function.Predicate;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public final class SectionDescriptor {

    public static final int SECTION_EDGE = 16;
    public static final int SECTION_BLOCK_COUNT = SECTION_EDGE * SECTION_EDGE * SECTION_EDGE;
    public static final int COLUMN_COUNT = SECTION_EDGE * SECTION_EDGE;
    public static final int EMPTY_LOCAL_Y = -1;

    public static final int PALETTE_AIR = 1;
    public static final int PALETTE_WATER = 1 << 1;
    public static final int PALETTE_LAVA = 1 << 2;
    public static final int PALETTE_SOLID = 1 << 3;

    public static final int CLASS_STONE_LIKE = 1;
    public static final int CLASS_DIRT_LIKE = 1 << 1;
    public static final int CLASS_REPLACEABLE = 1 << 2;
    public static final int CLASS_ORE_TARGET = 1 << 3;
    public static final int CLASS_SURFACE_CANDIDATE = 1 << 4;
    public static final int CLASS_TREE_SOIL = 1 << 5;

    private static final Predicate<BlockState> OCEAN_FLOOR_OPAQUE = Heightmap.Types.OCEAN_FLOOR.isOpaque();
    private static final Predicate<BlockState> MOTION_BLOCKING_OPAQUE = Heightmap.Types.MOTION_BLOCKING.isOpaque();
    private static final int MASK_AIR = 1;
    private static final int MASK_WATER = 1 << 1;
    private static final int MASK_LAVA = 1 << 2;
    private static final int MASK_SOLID = 1 << 3;
    private static final int MASK_MOTION_BLOCKING = 1 << 4;
    private static final int MASK_REPLACEABLE = 1 << 5;
    private static final int MASK_STONE_LIKE = 1 << 6;
    private static final int MASK_DIRT_LIKE = 1 << 7;
    private static final int MASK_TREE_SOIL = 1 << 8;
    private static final int[] PALETTE_FLAG_BITS = {
            PALETTE_AIR,
            PALETTE_WATER,
            PALETTE_LAVA,
            PALETTE_SOLID
    };
    private static final int[] BLOCK_CLASS_FLAG_BITS = {
            CLASS_STONE_LIKE,
            CLASS_DIRT_LIKE,
            CLASS_REPLACEABLE,
            CLASS_ORE_TARGET,
            CLASS_SURFACE_CANDIDATE,
            CLASS_TREE_SOIL
    };

    private static volatile CachedStateFacts stateFactsById;

    public ChunkAccess chunk;
    public LevelChunkSection section;
    public int sectionX;
    public int sectionY;
    public int sectionZ;
    public int paletteFlags;
    public int blockClassFlags;
    public int minFilledY;
    public int maxFilledY;
    public boolean hasAir;
    public boolean hasWater;
    public boolean hasLava;
    public boolean hasStoneLike;
    public boolean hasDirtLike;
    public boolean hasReplaceable;
    public boolean hasOreTarget;
    public boolean hasSurfaceCandidate;
    public boolean hasTreeSoil;

    private final int[] columnPaletteFlags = new int[COLUMN_COUNT];
    private final int[] columnBlockClassFlags = new int[COLUMN_COUNT];
    private final int[] columnAirMask = new int[COLUMN_COUNT];
    private final int[] columnWaterMask = new int[COLUMN_COUNT];
    private final int[] columnLavaMask = new int[COLUMN_COUNT];
    private final int[] columnSolidMask = new int[COLUMN_COUNT];
    private final int[] columnMotionBlockingMask = new int[COLUMN_COUNT];
    private final int[] columnReplaceableMask = new int[COLUMN_COUNT];
    private final int[] columnStoneLikeMask = new int[COLUMN_COUNT];
    private final int[] columnDirtLikeMask = new int[COLUMN_COUNT];
    private final int[] columnTreeSoilMask = new int[COLUMN_COUNT];
    private final byte[] columnMinFilledLocalY = new byte[COLUMN_COUNT];
    private final byte[] columnMaxFilledLocalY = new byte[COLUMN_COUNT];

    private int countPaletteAir;
    private int countPaletteWater;
    private int countPaletteLava;
    private int countPaletteSolid;

    private int countBlockClassStoneLike;
    private int countBlockClassDirtLike;
    private int countBlockClassReplaceable;
    private int countBlockClassOreTarget;
    private int countBlockClassSurfaceCandidate;
    private int countBlockClassTreeSoil;

    private final int[] minFilledLocalYCounts = new int[SECTION_EDGE];
    private final int[] maxFilledLocalYCounts = new int[SECTION_EDGE];
    private boolean allAirSection;

    public SectionDescriptor() {
        this.clearMetadata();
    }

    public void clear() {
        this.clearMetadata();
    }

    private void clearMetadata() {
        this.chunk = null;
        this.section = null;
        this.sectionX = 0;
        this.sectionY = 0;
        this.sectionZ = 0;
        this.paletteFlags = 0;
        this.blockClassFlags = 0;
        this.minFilledY = Integer.MAX_VALUE;
        this.maxFilledY = Integer.MIN_VALUE;
        this.hasAir = false;
        this.hasWater = false;
        this.hasLava = false;
        this.hasStoneLike = false;
        this.hasDirtLike = false;
        this.hasReplaceable = false;
        this.hasOreTarget = false;
        this.hasSurfaceCandidate = false;
        this.hasTreeSoil = false;
        this.allAirSection = false;
        this.countPaletteAir = 0;
        this.countPaletteWater = 0;
        this.countPaletteLava = 0;
        this.countPaletteSolid = 0;
        this.countBlockClassStoneLike = 0;
        this.countBlockClassDirtLike = 0;
        this.countBlockClassReplaceable = 0;
        this.countBlockClassOreTarget = 0;
        this.countBlockClassSurfaceCandidate = 0;
        this.countBlockClassTreeSoil = 0;
        Arrays.fill(this.minFilledLocalYCounts, 0);
        Arrays.fill(this.maxFilledLocalYCounts, 0);
    }

    private void clearColumnData() {
        Arrays.fill(this.columnPaletteFlags, 0);
        Arrays.fill(this.columnBlockClassFlags, 0);
        Arrays.fill(this.columnAirMask, 0);
        Arrays.fill(this.columnWaterMask, 0);
        Arrays.fill(this.columnLavaMask, 0);
        Arrays.fill(this.columnSolidMask, 0);
        Arrays.fill(this.columnMotionBlockingMask, 0);
        Arrays.fill(this.columnReplaceableMask, 0);
        Arrays.fill(this.columnStoneLikeMask, 0);
        Arrays.fill(this.columnDirtLikeMask, 0);
        Arrays.fill(this.columnTreeSoilMask, 0);
        this.resetColumnRanges();
    }

    public boolean isEmpty() {
        return this.minFilledY == Integer.MAX_VALUE;
    }

    public boolean containsBlockY(int blockY) {
        int minBlockY = this.sectionY << 4;
        return blockY >= minBlockY && blockY < minBlockY + SECTION_EDGE;
    }

    public boolean hasPaletteFlag(int flags) {
        return (this.paletteFlags & flags) != 0;
    }

    public boolean hasBlockClassFlag(int flags) {
        return (this.blockClassFlags & flags) != 0;
    }

    public int columnPaletteFlags(int localX, int localZ) {
        if (this.allAirSection) {
            return PALETTE_AIR;
        }
        return this.columnPaletteFlags[columnIndex(localX, localZ)];
    }

    public int columnBlockClassFlags(int localX, int localZ) {
        if (this.allAirSection) {
            return CLASS_REPLACEABLE;
        }
        return this.columnBlockClassFlags[columnIndex(localX, localZ)];
    }

    public int columnAirMask(int localX, int localZ) {
        if (this.allAirSection) {
            return 0xFFFF;
        }
        return this.columnAirMask[columnIndex(localX, localZ)];
    }

    public int columnWaterMask(int localX, int localZ) {
        if (this.allAirSection) {
            return 0;
        }
        return this.columnWaterMask[columnIndex(localX, localZ)];
    }

    public int columnLavaMask(int localX, int localZ) {
        if (this.allAirSection) {
            return 0;
        }
        return this.columnLavaMask[columnIndex(localX, localZ)];
    }

    public int columnSolidMask(int localX, int localZ) {
        if (this.allAirSection) {
            return 0;
        }
        return this.columnSolidMask[columnIndex(localX, localZ)];
    }

    public int columnReplaceableMask(int localX, int localZ) {
        if (this.allAirSection) {
            return 0xFFFF;
        }
        return this.columnReplaceableMask[columnIndex(localX, localZ)];
    }

    public int columnMotionBlockingMask(int localX, int localZ) {
        if (this.allAirSection) {
            return 0;
        }
        return this.columnMotionBlockingMask[columnIndex(localX, localZ)];
    }

    public int columnStoneLikeMask(int localX, int localZ) {
        if (this.allAirSection) {
            return 0;
        }
        return this.columnStoneLikeMask[columnIndex(localX, localZ)];
    }

    public int columnDirtLikeMask(int localX, int localZ) {
        if (this.allAirSection) {
            return 0;
        }
        return this.columnDirtLikeMask[columnIndex(localX, localZ)];
    }

    public int columnTreeSoilMask(int localX, int localZ) {
        if (this.allAirSection) {
            return 0;
        }
        return this.columnTreeSoilMask[columnIndex(localX, localZ)];
    }

    public boolean isColumnEmpty(int localX, int localZ) {
        if (this.allAirSection) {
            return true;
        }
        return this.columnMinFilledLocalY[columnIndex(localX, localZ)] == EMPTY_LOCAL_Y;
    }

    public int columnMinFilledBlockY(int localX, int localZ) {
        if (this.allAirSection) {
            return Integer.MAX_VALUE;
        }
        int index = columnIndex(localX, localZ);
        int localY = this.columnMinFilledLocalY[index];
        return localY == EMPTY_LOCAL_Y ? Integer.MAX_VALUE : (this.sectionY << 4) + localY;
    }

    public int columnMaxFilledBlockY(int localX, int localZ) {
        if (this.allAirSection) {
            return Integer.MIN_VALUE;
        }
        int index = columnIndex(localX, localZ);
        int localY = this.columnMaxFilledLocalY[index];
        return localY == EMPTY_LOCAL_Y ? Integer.MIN_VALUE : (this.sectionY << 4) + localY;
    }

    public boolean columnHasPaletteFlag(int localX, int localZ, int flags) {
        return (this.columnPaletteFlags(localX, localZ) & flags) != 0;
    }

    public boolean columnHasBlockClassFlag(int localX, int localZ, int flags) {
        return (this.columnBlockClassFlags(localX, localZ) & flags) != 0;
    }

    public boolean columnHasAnyAirBetween(int localX, int localZ, int fromLocalYInclusive, int toLocalYInclusive) {
        return this.columnMaskIntersects(this.columnAirMask, localX, localZ, fromLocalYInclusive, toLocalYInclusive);
    }

    public boolean columnHasAirAt(int blockX, int blockY, int blockZ) {
        return this.columnMaskContainsBlockPos(this.columnAirMask, blockX, blockY, blockZ);
    }

    public boolean columnHasWaterAt(int blockX, int blockY, int blockZ) {
        return this.columnMaskContainsBlockPos(this.columnWaterMask, blockX, blockY, blockZ);
    }

    public boolean columnHasAnyWaterBetween(int localX, int localZ, int fromLocalYInclusive, int toLocalYInclusive) {
        return this.columnMaskIntersects(this.columnWaterMask, localX, localZ, fromLocalYInclusive, toLocalYInclusive);
    }

    public boolean columnHasLavaAt(int blockX, int blockY, int blockZ) {
        return this.columnMaskContainsBlockPos(this.columnLavaMask, blockX, blockY, blockZ);
    }

    public boolean columnHasAnyLavaBetween(int localX, int localZ, int fromLocalYInclusive, int toLocalYInclusive) {
        return this.columnMaskIntersects(this.columnLavaMask, localX, localZ, fromLocalYInclusive, toLocalYInclusive);
    }

    public boolean columnHasAnySolidBetween(int localX, int localZ, int fromLocalYInclusive, int toLocalYInclusive) {
        return this.columnMaskIntersects(this.columnSolidMask, localX, localZ, fromLocalYInclusive, toLocalYInclusive);
    }

    public boolean columnHasAnyReplaceableBetween(int localX, int localZ, int fromLocalYInclusive, int toLocalYInclusive) {
        return this.columnMaskIntersects(this.columnReplaceableMask, localX, localZ, fromLocalYInclusive, toLocalYInclusive);
    }

    public boolean columnHasOpenAt(int blockX, int blockY, int blockZ) {
        int localY = localY(blockY);
        if (localY < 0) {
            return false;
        }
        if (this.allAirSection) {
            return true;
        }
        int index = columnIndexFromBlock(blockX, blockZ);
        int bit = 1 << localY;
        return ((this.columnAirMask[index] | this.columnReplaceableMask[index]) & bit) != 0;
    }

    public boolean columnHasFluidAt(int blockX, int blockY, int blockZ) {
        int localY = localY(blockY);
        if (localY < 0) {
            return false;
        }
        if (this.allAirSection) {
            return false;
        }
        int index = columnIndexFromBlock(blockX, blockZ);
        int bit = 1 << localY;
        return ((this.columnWaterMask[index] | this.columnLavaMask[index]) & bit) != 0;
    }

    public boolean columnHasSolidAt(int blockX, int blockY, int blockZ) {
        int localY = localY(blockY);
        if (localY < 0) {
            return false;
        }
        if (this.allAirSection) {
            return false;
        }
        int index = columnIndexFromBlock(blockX, blockZ);
        return (this.columnSolidMask[index] & (1 << localY)) != 0;
    }

    public boolean columnHasGroundSupportAt(int blockX, int blockY, int blockZ) {
        int localY = localY(blockY);
        if (localY < 0) {
            return false;
        }
        if (this.allAirSection) {
            return false;
        }
        int index = columnIndexFromBlock(blockX, blockZ);
        int bit = 1 << localY;
        return ((this.columnSolidMask[index]
                | this.columnStoneLikeMask[index]
                | this.columnDirtLikeMask[index]
                | this.columnTreeSoilMask[index]) & bit) != 0;
    }

    public int columnHighestFilledBlockY(int localX, int localZ) {
        return this.columnMaxFilledBlockY(localX, localZ);
    }

    public int columnHighestSolidBlockY(int localX, int localZ) {
        return this.columnHighestMaskedBlockY(this.columnSolidMask, localX, localZ);
    }

    public int columnHighestFluidBlockY(int localX, int localZ) {
        if (this.allAirSection) {
            return Integer.MIN_VALUE;
        }
        return this.columnHighestMaskedBlockY(this.columnWaterMask[columnIndex(localX, localZ)] | this.columnLavaMask[columnIndex(localX, localZ)], localX, localZ);
    }

    public int columnHighestWaterBlockY(int localX, int localZ) {
        return this.columnHighestMaskedBlockY(this.columnWaterMask, localX, localZ);
    }

    public int columnHighestLavaBlockY(int localX, int localZ) {
        return this.columnHighestMaskedBlockY(this.columnLavaMask, localX, localZ);
    }

    public int columnHighestMotionBlockingBlockY(int localX, int localZ) {
        return this.columnHighestMaskedBlockY(this.columnMotionBlockingMask, localX, localZ);
    }

    public boolean columnMayContainTreeVolume(int localX, int localZ) {
        if (this.allAirSection) {
            return true;
        }
        int index = columnIndex(localX, localZ);
        return this.columnAirMask[index] != 0 || this.columnReplaceableMask[index] != 0;
    }

    public boolean columnMaySupportTreeBase(int localX, int localZ) {
        int flags = this.columnBlockClassFlags(localX, localZ);
        return (flags & (CLASS_TREE_SOIL | CLASS_SURFACE_CANDIDATE)) != 0;
    }

    public boolean mayContainTreeVolume() {
        return this.hasAir || this.hasReplaceable;
    }

    public boolean maySupportTreeBase() {
        return this.hasTreeSoil || this.hasSurfaceCandidate;
    }

    void build(ChunkAccess chunk, LevelChunkSection section, int sectionX, int sectionY, int sectionZ) {
        this.clearMetadata();
        this.chunk = chunk;
        this.section = section;
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;

        if (section == null || section.hasOnlyAir()) {
            this.markAllAirSection();
            return;
        }

        int[] raw = LevelChunkSection$FlatBlockArray.rawData(section);
        if (raw != null) {
            this.scanRawSection(raw);
            return;
        }

        this.clearColumnData();
        this.scanSection(section);
    }

    void rebuildColumn(int localX, int localZ) {
        if (this.section == null) {
            return;
        }
        if (this.allAirSection) {
            this.build(this.chunk, this.section, this.sectionX, this.sectionY, this.sectionZ);
            return;
        }

        int columnIndex = columnIndex(localX, localZ);
        int oldPaletteFlags = this.columnPaletteFlags[columnIndex];
        int oldBlockClassFlags = this.columnBlockClassFlags[columnIndex];
        int oldMinFilledLocalY = this.columnMinFilledLocalY[columnIndex];
        int oldMaxFilledLocalY = this.columnMaxFilledLocalY[columnIndex];
        int[] raw = LevelChunkSection$FlatBlockArray.rawData(this.section);
        if (raw != null) {
            this.scanRawColumn(raw, columnIndex);
            this.refreshAggregatesAfterColumnChange(columnIndex, oldPaletteFlags, oldBlockClassFlags, oldMinFilledLocalY, oldMaxFilledLocalY);
            return;
        }

        this.clearColumn(columnIndex);
        for (int localY = 0; localY < SECTION_EDGE; localY++) {
            this.acceptColumnState(this.section.getBlockState(localX, localY, localZ), columnIndex, localY, 1 << localY);
        }
        this.finishColumnFlags(columnIndex);
        this.refreshAggregatesAfterColumnChange(columnIndex, oldPaletteFlags, oldBlockClassFlags, oldMinFilledLocalY, oldMaxFilledLocalY);
    }

    void updateBlockState(int blockX, int blockY, int blockZ, BlockState newState) {
        if (this.section == null || !this.containsBlockY(blockY)) {
            return;
        }
        if (this.allAirSection) {
            if (newState.isAir()) {
                return;
            }
            this.build(this.chunk, this.section, this.sectionX, this.sectionY, this.sectionZ);
            return;
        }

        int columnIndex = columnIndexFromBlock(blockX, blockZ);
        int oldPaletteFlags = this.columnPaletteFlags[columnIndex];
        int oldBlockClassFlags = this.columnBlockClassFlags[columnIndex];
        int oldMinFilledLocalY = this.columnMinFilledLocalY[columnIndex];
        int oldMaxFilledLocalY = this.columnMaxFilledLocalY[columnIndex];
        int localY = blockY & 15;
        int bit = 1 << localY;
        int clearMask = ~bit;

        int airMask = this.columnAirMask[columnIndex] & clearMask;
        int waterMask = this.columnWaterMask[columnIndex] & clearMask;
        int lavaMask = this.columnLavaMask[columnIndex] & clearMask;
        int solidMask = this.columnSolidMask[columnIndex] & clearMask;
        int motionBlockingMask = this.columnMotionBlockingMask[columnIndex] & clearMask;
        int replaceableMask = this.columnReplaceableMask[columnIndex] & clearMask;
        int stoneLikeMask = this.columnStoneLikeMask[columnIndex] & clearMask;
        int dirtLikeMask = this.columnDirtLikeMask[columnIndex] & clearMask;
        int treeSoilMask = this.columnTreeSoilMask[columnIndex] & clearMask;

        int stateId = Block.getId(newState);
        CachedStateFacts facts = stateFactsById();
        int maskFlags = stateId >= 0 && stateId < facts.maskFlags.length
                ? facts.maskFlags[stateId]
                : maskFlagsForState(newState);

        airMask |= (maskFlags & MASK_AIR) << localY;
        waterMask |= ((maskFlags & MASK_WATER) << localY) >>> 1;
        lavaMask |= ((maskFlags & MASK_LAVA) << localY) >>> 2;
        solidMask |= ((maskFlags & MASK_SOLID) << localY) >>> 3;
        motionBlockingMask |= ((maskFlags & MASK_MOTION_BLOCKING) << localY) >>> 4;
        replaceableMask |= ((maskFlags & MASK_REPLACEABLE) << localY) >>> 5;
        stoneLikeMask |= ((maskFlags & MASK_STONE_LIKE) << localY) >>> 6;
        dirtLikeMask |= ((maskFlags & MASK_DIRT_LIKE) << localY) >>> 7;
        treeSoilMask |= ((maskFlags & MASK_TREE_SOIL) << localY) >>> 8;

        this.columnAirMask[columnIndex] = airMask;
        this.columnWaterMask[columnIndex] = waterMask;
        this.columnLavaMask[columnIndex] = lavaMask;
        this.columnSolidMask[columnIndex] = solidMask;
        this.columnMotionBlockingMask[columnIndex] = motionBlockingMask;
        this.columnReplaceableMask[columnIndex] = replaceableMask;
        this.columnStoneLikeMask[columnIndex] = stoneLikeMask;
        this.columnDirtLikeMask[columnIndex] = dirtLikeMask;
        this.columnTreeSoilMask[columnIndex] = treeSoilMask;

        int paletteFlags = flagsFromColumnMasks(airMask, waterMask, lavaMask, solidMask);
        int blockClassFlags = classFlagsFromColumnMasks(replaceableMask, stoneLikeMask, dirtLikeMask, treeSoilMask);
        if (this.finishSurfaceCandidate(paletteFlags, blockClassFlags)) {
            blockClassFlags |= CLASS_SURFACE_CANDIDATE;
        }
        this.columnPaletteFlags[columnIndex] = paletteFlags;
        this.columnBlockClassFlags[columnIndex] = blockClassFlags;

        int filledMask = (~airMask) & 0xFFFF;
        this.columnMinFilledLocalY[columnIndex] = (byte) (filledMask == 0 ? EMPTY_LOCAL_Y : Integer.numberOfTrailingZeros(filledMask));
        this.columnMaxFilledLocalY[columnIndex] = (byte) (filledMask == 0 ? EMPTY_LOCAL_Y : 31 - Integer.numberOfLeadingZeros(filledMask));
        this.refreshAggregatesAfterColumnChange(columnIndex, oldPaletteFlags, oldBlockClassFlags, oldMinFilledLocalY, oldMaxFilledLocalY);
    }

    private void markAllAirSection() {
        this.allAirSection = true;
        this.paletteFlags = PALETTE_AIR;
        this.blockClassFlags = CLASS_REPLACEABLE;
        this.hasAir = true;
        this.hasReplaceable = true;
    }

    private void scanSection(LevelChunkSection section) {
        for (int localY = 0; localY < SECTION_EDGE; localY++) {
            int bit = 1 << localY;
            for (int localZ = 0; localZ < SECTION_EDGE; localZ++) {
                for (int localX = 0; localX < SECTION_EDGE; localX++) {
                    this.acceptColumnState(section.getBlockState(localX, localY, localZ), columnIndex(localX, localZ), localY, bit);
                }
            }
        }
        for (int columnIndex = 0; columnIndex < COLUMN_COUNT; columnIndex++) {
            this.finishColumnFlags(columnIndex);
        }
        this.refreshAggregates();
    }

    private void scanRawSection(int[] raw) {
        CachedStateFacts factsById = stateFactsById();
        int[] paletteFlagsById = factsById.paletteFlags;
        int[] blockClassFlagsById = factsById.blockClassFlags;
        int[] maskFlagsById = factsById.maskFlags;
        int[] filledBitsById = factsById.filledBits;
        int aggregatePaletteFlags = 0;
        int aggregateBlockClassFlags = 0;
        int aggregateMinFilledY = Integer.MAX_VALUE;
        int aggregateMaxFilledY = Integer.MIN_VALUE;
        int minSectionBlockY = this.sectionY << 4;

        for (int localZ = 0; localZ < SECTION_EDGE; localZ++) {
            int zBase = localZ << 4;
            for (int localX = 0; localX < SECTION_EDGE; localX++) {
                int columnIndex = zBase | localX;
                int paletteFlags = 0;
                int blockClassFlags = 0;
                int airMask = 0;
                int waterMask = 0;
                int lavaMask = 0;
                int solidMask = 0;
                int motionBlockingMask = 0;
                int replaceableMask = 0;
                int stoneLikeMask = 0;
                int dirtLikeMask = 0;
                int treeSoilMask = 0;
                int filledMask = 0;

                for (int localY = 0; localY < SECTION_EDGE; localY++) {
                    int stateId = raw[(localY << 8) | columnIndex];
                    paletteFlags |= paletteFlagsById[stateId];
                    blockClassFlags |= blockClassFlagsById[stateId];

                    int maskFlags = maskFlagsById[stateId];
                    airMask |= (maskFlags & MASK_AIR) << localY;
                    waterMask |= ((maskFlags & MASK_WATER) << localY) >>> 1;
                    lavaMask |= ((maskFlags & MASK_LAVA) << localY) >>> 2;
                    solidMask |= ((maskFlags & MASK_SOLID) << localY) >>> 3;
                    motionBlockingMask |= ((maskFlags & MASK_MOTION_BLOCKING) << localY) >>> 4;
                    replaceableMask |= ((maskFlags & MASK_REPLACEABLE) << localY) >>> 5;
                    stoneLikeMask |= ((maskFlags & MASK_STONE_LIKE) << localY) >>> 6;
                    dirtLikeMask |= ((maskFlags & MASK_DIRT_LIKE) << localY) >>> 7;
                    treeSoilMask |= ((maskFlags & MASK_TREE_SOIL) << localY) >>> 8;
                    filledMask |= filledBitsById[stateId] << localY;
                }

                if (this.finishSurfaceCandidate(paletteFlags, blockClassFlags)) {
                    blockClassFlags |= CLASS_SURFACE_CANDIDATE;
                }
                int minFilledLocalY = filledMask == 0 ? EMPTY_LOCAL_Y : Integer.numberOfTrailingZeros(filledMask);
                int maxFilledLocalY = filledMask == 0 ? EMPTY_LOCAL_Y : 31 - Integer.numberOfLeadingZeros(filledMask);

                this.columnPaletteFlags[columnIndex] = paletteFlags;
                this.columnBlockClassFlags[columnIndex] = blockClassFlags;
                this.columnAirMask[columnIndex] = airMask;
                this.columnWaterMask[columnIndex] = waterMask;
                this.columnLavaMask[columnIndex] = lavaMask;
                this.columnSolidMask[columnIndex] = solidMask;
                this.columnMotionBlockingMask[columnIndex] = motionBlockingMask;
                this.columnReplaceableMask[columnIndex] = replaceableMask;
                this.columnStoneLikeMask[columnIndex] = stoneLikeMask;
                this.columnDirtLikeMask[columnIndex] = dirtLikeMask;
                this.columnTreeSoilMask[columnIndex] = treeSoilMask;
                this.columnMinFilledLocalY[columnIndex] = (byte) minFilledLocalY;
                this.columnMaxFilledLocalY[columnIndex] = (byte) maxFilledLocalY;
                this.addColumnCounts(paletteFlags, blockClassFlags, minFilledLocalY, maxFilledLocalY);

                aggregatePaletteFlags |= paletteFlags;
                aggregateBlockClassFlags |= blockClassFlags;
                if (minFilledLocalY != EMPTY_LOCAL_Y) {
                    int minBlockY = minSectionBlockY + minFilledLocalY;
                    if (minBlockY < aggregateMinFilledY) {
                        aggregateMinFilledY = minBlockY;
                    }
                    int maxBlockY = minSectionBlockY + maxFilledLocalY;
                    if (maxBlockY > aggregateMaxFilledY) {
                        aggregateMaxFilledY = maxBlockY;
                    }
                }
            }
        }
        this.finishAggregates(aggregatePaletteFlags, aggregateBlockClassFlags, aggregateMinFilledY, aggregateMaxFilledY);
    }

    private void scanRawColumn(int[] raw, int columnIndex) {
        CachedStateFacts factsById = stateFactsById();
        int[] paletteFlagsById = factsById.paletteFlags;
        int[] blockClassFlagsById = factsById.blockClassFlags;
        int[] maskFlagsById = factsById.maskFlags;
        int[] filledBitsById = factsById.filledBits;
        int paletteFlags = 0;
        int blockClassFlags = 0;
        int airMask = 0;
        int waterMask = 0;
        int lavaMask = 0;
        int solidMask = 0;
        int motionBlockingMask = 0;
        int replaceableMask = 0;
        int stoneLikeMask = 0;
        int dirtLikeMask = 0;
        int treeSoilMask = 0;
        int filledMask = 0;

        for (int localY = 0; localY < SECTION_EDGE; localY++) {
            int stateId = raw[(localY << 8) | columnIndex];
            paletteFlags |= paletteFlagsById[stateId];
            blockClassFlags |= blockClassFlagsById[stateId];

            int maskFlags = maskFlagsById[stateId];
            airMask |= (maskFlags & MASK_AIR) << localY;
            waterMask |= ((maskFlags & MASK_WATER) << localY) >>> 1;
            lavaMask |= ((maskFlags & MASK_LAVA) << localY) >>> 2;
            solidMask |= ((maskFlags & MASK_SOLID) << localY) >>> 3;
            motionBlockingMask |= ((maskFlags & MASK_MOTION_BLOCKING) << localY) >>> 4;
            replaceableMask |= ((maskFlags & MASK_REPLACEABLE) << localY) >>> 5;
            stoneLikeMask |= ((maskFlags & MASK_STONE_LIKE) << localY) >>> 6;
            dirtLikeMask |= ((maskFlags & MASK_DIRT_LIKE) << localY) >>> 7;
            treeSoilMask |= ((maskFlags & MASK_TREE_SOIL) << localY) >>> 8;
            filledMask |= filledBitsById[stateId] << localY;
        }

        if (this.finishSurfaceCandidate(paletteFlags, blockClassFlags)) {
            blockClassFlags |= CLASS_SURFACE_CANDIDATE;
        }
        int minFilledLocalY = filledMask == 0 ? EMPTY_LOCAL_Y : Integer.numberOfTrailingZeros(filledMask);
        int maxFilledLocalY = filledMask == 0 ? EMPTY_LOCAL_Y : 31 - Integer.numberOfLeadingZeros(filledMask);

        this.columnPaletteFlags[columnIndex] = paletteFlags;
        this.columnBlockClassFlags[columnIndex] = blockClassFlags;
        this.columnAirMask[columnIndex] = airMask;
        this.columnWaterMask[columnIndex] = waterMask;
        this.columnLavaMask[columnIndex] = lavaMask;
        this.columnSolidMask[columnIndex] = solidMask;
        this.columnMotionBlockingMask[columnIndex] = motionBlockingMask;
        this.columnReplaceableMask[columnIndex] = replaceableMask;
        this.columnStoneLikeMask[columnIndex] = stoneLikeMask;
        this.columnDirtLikeMask[columnIndex] = dirtLikeMask;
        this.columnTreeSoilMask[columnIndex] = treeSoilMask;
        this.columnMinFilledLocalY[columnIndex] = (byte) minFilledLocalY;
        this.columnMaxFilledLocalY[columnIndex] = (byte) maxFilledLocalY;
    }

    private void acceptColumnState(BlockState state, int columnIndex, int localY, int bit) {
        Block block = state.getBlock();
        FluidState fluidState = state.getFluidState();

        if (state.isAir()) {
            this.columnPaletteFlags[columnIndex] |= PALETTE_AIR;
            this.columnAirMask[columnIndex] |= bit;
            this.markColumnReplaceable(columnIndex, bit);
            return;
        }

        this.markColumnFilled(columnIndex, localY);

        if (!fluidState.isEmpty()) {
            if (fluidState.getType() == Fluids.WATER) {
                this.columnPaletteFlags[columnIndex] |= PALETTE_WATER;
                this.columnWaterMask[columnIndex] |= bit;
                this.markColumnReplaceable(columnIndex, bit);
            } else if (fluidState.getType() == Fluids.LAVA) {
                this.columnPaletteFlags[columnIndex] |= PALETTE_LAVA;
                this.columnLavaMask[columnIndex] |= bit;
            }
        }

        if (OCEAN_FLOOR_OPAQUE.test(state)) {
            this.columnPaletteFlags[columnIndex] |= PALETTE_SOLID;
            this.columnSolidMask[columnIndex] |= bit;
        }
        if (MOTION_BLOCKING_OPAQUE.test(state)) {
            this.columnMotionBlockingMask[columnIndex] |= bit;
        }

        if (isStoneLike(state, block)) {
            this.columnBlockClassFlags[columnIndex] |= CLASS_STONE_LIKE | CLASS_ORE_TARGET;
            this.columnStoneLikeMask[columnIndex] |= bit;
        } else if (isDirtLike(state, block)) {
            this.columnBlockClassFlags[columnIndex] |= CLASS_DIRT_LIKE | CLASS_TREE_SOIL;
            this.columnDirtLikeMask[columnIndex] |= bit;
            this.columnTreeSoilMask[columnIndex] |= bit;
        } else if (isTreeSoilLike(state, block)) {
            this.columnBlockClassFlags[columnIndex] |= CLASS_TREE_SOIL;
            this.columnTreeSoilMask[columnIndex] |= bit;
        }

        if (isLooseReplaceable(block)) {
            this.markColumnReplaceable(columnIndex, bit);
        }
    }

    private void finishColumnFlags(int columnIndex) {
        if (this.finishSurfaceCandidate(this.columnPaletteFlags[columnIndex], this.columnBlockClassFlags[columnIndex])) {
            this.columnBlockClassFlags[columnIndex] |= CLASS_SURFACE_CANDIDATE;
        }
    }

    private boolean finishSurfaceCandidate(int paletteFlags, int classFlags) {
        boolean hasAirLike = (paletteFlags & PALETTE_AIR) != 0;
        boolean hasSurfaceBase = (classFlags & (CLASS_DIRT_LIKE | CLASS_STONE_LIKE)) != 0 || (paletteFlags & PALETTE_SOLID) != 0;
        return hasAirLike && hasSurfaceBase;
    }

    private void markColumnFilled(int columnIndex, int localY) {
        if (this.columnMinFilledLocalY[columnIndex] == EMPTY_LOCAL_Y) {
            this.columnMinFilledLocalY[columnIndex] = (byte) localY;
        }
        this.columnMaxFilledLocalY[columnIndex] = (byte) localY;
    }

    private void markColumnReplaceable(int columnIndex, int bit) {
        this.columnBlockClassFlags[columnIndex] |= CLASS_REPLACEABLE;
        this.columnReplaceableMask[columnIndex] |= bit;
    }

    private void clearColumn(int columnIndex) {
        this.columnPaletteFlags[columnIndex] = 0;
        this.columnBlockClassFlags[columnIndex] = 0;
        this.columnAirMask[columnIndex] = 0;
        this.columnWaterMask[columnIndex] = 0;
        this.columnLavaMask[columnIndex] = 0;
        this.columnSolidMask[columnIndex] = 0;
        this.columnMotionBlockingMask[columnIndex] = 0;
        this.columnReplaceableMask[columnIndex] = 0;
        this.columnStoneLikeMask[columnIndex] = 0;
        this.columnDirtLikeMask[columnIndex] = 0;
        this.columnTreeSoilMask[columnIndex] = 0;
        this.columnMinFilledLocalY[columnIndex] = (byte) EMPTY_LOCAL_Y;
        this.columnMaxFilledLocalY[columnIndex] = (byte) EMPTY_LOCAL_Y;
    }

    private void refreshAggregates() {
        this.countPaletteAir = 0;
        this.countPaletteWater = 0;
        this.countPaletteLava = 0;
        this.countPaletteSolid = 0;
        this.countBlockClassStoneLike = 0;
        this.countBlockClassDirtLike = 0;
        this.countBlockClassReplaceable = 0;
        this.countBlockClassOreTarget = 0;
        this.countBlockClassSurfaceCandidate = 0;
        this.countBlockClassTreeSoil = 0;
        Arrays.fill(this.minFilledLocalYCounts, 0);
        Arrays.fill(this.maxFilledLocalYCounts, 0);

        int paletteFlags = 0;
        int blockClassFlags = 0;
        int minFilledY = Integer.MAX_VALUE;
        int maxFilledY = Integer.MIN_VALUE;

        int minSectionBlockY = this.sectionY << 4;
        for (int columnIndex = 0; columnIndex < COLUMN_COUNT; columnIndex++) {
            paletteFlags |= this.columnPaletteFlags[columnIndex];
            blockClassFlags |= this.columnBlockClassFlags[columnIndex];

            int minLocalY = this.columnMinFilledLocalY[columnIndex];
            if (minLocalY != EMPTY_LOCAL_Y) {
                this.minFilledLocalYCounts[minLocalY]++;
                int blockY = minSectionBlockY + minLocalY;
                if (blockY < minFilledY) {
                    minFilledY = blockY;
                }
            }

            int maxLocalY = this.columnMaxFilledLocalY[columnIndex];
            if (maxLocalY != EMPTY_LOCAL_Y) {
                this.maxFilledLocalYCounts[maxLocalY]++;
                int blockY = minSectionBlockY + maxLocalY;
                if (blockY > maxFilledY) {
                    maxFilledY = blockY;
                }
            }

            this.addPaletteFlagCounts(this.columnPaletteFlags[columnIndex]);
            this.addBlockFlagCounts(this.columnBlockClassFlags[columnIndex]);
        }

        this.finishAggregates(paletteFlags, blockClassFlags, minFilledY, maxFilledY);
    }

    private void refreshAggregatesAfterColumnChange(
            int columnIndex,
            int oldPaletteFlags,
            int oldBlockClassFlags,
            int oldMinFilledLocalY,
            int oldMaxFilledLocalY
    ) {
        this.removeColumnCounts(oldPaletteFlags, oldBlockClassFlags, oldMinFilledLocalY, oldMaxFilledLocalY);
        this.addColumnCounts(
                this.columnPaletteFlags[columnIndex],
                this.columnBlockClassFlags[columnIndex],
                this.columnMinFilledLocalY[columnIndex],
                this.columnMaxFilledLocalY[columnIndex]
        );
        this.finishAggregates(
                this.flagsFromPalette(),
                this.flagsFromBlock(),
                this.minFilledBlockYFromCounts(),
                this.maxFilledBlockYFromCounts()
        );
    }

    private void addColumnCounts(int paletteFlags, int blockClassFlags, int minFilledLocalY, int maxFilledLocalY) {
        this.addPaletteFlagCounts(paletteFlags);
        this.addBlockFlagCounts(blockClassFlags);
        if (minFilledLocalY != EMPTY_LOCAL_Y) {
            this.minFilledLocalYCounts[minFilledLocalY]++;
        }
        if (maxFilledLocalY != EMPTY_LOCAL_Y) {
            this.maxFilledLocalYCounts[maxFilledLocalY]++;
        }
    }

    private void removeColumnCounts(int paletteFlags, int blockClassFlags, int minFilledLocalY, int maxFilledLocalY) {
        this.removePaletteFlagCounts(paletteFlags);
        this.removeBlockFlagCounts(blockClassFlags);
        if (minFilledLocalY != EMPTY_LOCAL_Y && this.minFilledLocalYCounts[minFilledLocalY] > 0) {
            this.minFilledLocalYCounts[minFilledLocalY]--;
        }
        if (maxFilledLocalY != EMPTY_LOCAL_Y && this.maxFilledLocalYCounts[maxFilledLocalY] > 0) {
            this.maxFilledLocalYCounts[maxFilledLocalY]--;
        }
    }

    private void addBlockFlagCounts(int flags) {
        if((flags & CLASS_STONE_LIKE) != 0) {
            this.countBlockClassStoneLike++;
        }
        if((flags & CLASS_DIRT_LIKE) != 0) {
            this.countBlockClassDirtLike++;
        }
        if((flags & CLASS_REPLACEABLE) != 0) {
            this.countBlockClassReplaceable++;
        }
        if((flags & CLASS_ORE_TARGET) != 0) {
            this.countBlockClassOreTarget++;
        }
        if((flags & CLASS_SURFACE_CANDIDATE) != 0) {
            this.countBlockClassSurfaceCandidate++;
        }
        if((flags & CLASS_TREE_SOIL) != 0) {
            this.countBlockClassTreeSoil++;
        }
    }

    private void addPaletteFlagCounts(int flags) {
        if((flags & PALETTE_AIR) != 0) {
            countPaletteAir++;
        }
        if((flags & PALETTE_WATER) != 0) {
            countPaletteWater++;
        }
        if((flags & PALETTE_LAVA) != 0) {
            countPaletteLava++;
        }
        if((flags & PALETTE_SOLID) != 0) {
            countPaletteSolid++;
        }
    }

    private void removePaletteFlagCounts(int flags) {
        if((flags & PALETTE_AIR) != 0 && this.countPaletteAir > 0) {
            countPaletteAir--;
        }
        if((flags & PALETTE_WATER) != 0 && this.countPaletteWater > 0) {
            countPaletteWater--;
        }
        if((flags & PALETTE_LAVA) != 0 && this.countPaletteLava > 0) {
            countPaletteLava--;
        }
        if((flags & PALETTE_SOLID) != 0 && this.countPaletteSolid > 0) {
            countPaletteSolid--;
        }
    }

    private int flagsFromPalette() {
        int flags = 0;
        if(countPaletteAir > 0) {
            flags |= PALETTE_AIR;
        }
        if(countPaletteWater > 0) {
            flags |= PALETTE_WATER;
        }
        if(countPaletteLava > 0) {
            flags |= PALETTE_LAVA;
        }
        if(countPaletteSolid > 0) {
            flags |= PALETTE_SOLID;
        }
        return flags;
    }

    private void removeBlockFlagCounts(int flags) {
        if((flags & CLASS_STONE_LIKE) != 0 && this.countBlockClassStoneLike > 0) {
            countBlockClassStoneLike--;
        }
        if((flags & CLASS_DIRT_LIKE) != 0 && this.countBlockClassDirtLike > 0) {
            countBlockClassDirtLike--;
        }
        if((flags & CLASS_REPLACEABLE) != 0 && this.countBlockClassReplaceable > 0) {
            countBlockClassReplaceable--;
        }
        if((flags & CLASS_ORE_TARGET) != 0 && this.countBlockClassOreTarget > 0) {
            countBlockClassOreTarget--;
        }
        if((flags & CLASS_SURFACE_CANDIDATE) != 0 && this.countBlockClassSurfaceCandidate > 0) {
            countBlockClassSurfaceCandidate--;
        }
        if((flags & CLASS_TREE_SOIL) != 0 && this.countBlockClassTreeSoil > 0) {
            countBlockClassTreeSoil--;
        }
    }

    private int flagsFromBlock() {
        int flags = 0;
        if(countBlockClassStoneLike > 0) {
            flags |= CLASS_STONE_LIKE;
        }
        if(countBlockClassDirtLike > 0) {
            flags |= CLASS_DIRT_LIKE;
        }
        if(countBlockClassReplaceable > 0) {
            flags |= CLASS_REPLACEABLE;
        }
        if(countBlockClassOreTarget > 0) {
            flags |= CLASS_ORE_TARGET;
        }
        if(countBlockClassSurfaceCandidate > 0) {
            flags |= CLASS_SURFACE_CANDIDATE;
        }
        if(countBlockClassTreeSoil > 0) {
            flags |= CLASS_TREE_SOIL;
        }
        return flags;
    }

    private int minFilledBlockYFromCounts() {
        int minSectionBlockY = this.sectionY << 4;
        for (int localY = 0; localY < SECTION_EDGE; localY++) {
            if (this.minFilledLocalYCounts[localY] > 0) {
                return minSectionBlockY + localY;
            }
        }
        return Integer.MAX_VALUE;
    }

    private int maxFilledBlockYFromCounts() {
        int minSectionBlockY = this.sectionY << 4;
        for (int localY = SECTION_EDGE - 1; localY >= 0; localY--) {
            if (this.maxFilledLocalYCounts[localY] > 0) {
                return minSectionBlockY + localY;
            }
        }
        return Integer.MIN_VALUE;
    }

    private void finishAggregates(int paletteFlags, int blockClassFlags, int minFilledY, int maxFilledY) {
        this.paletteFlags = paletteFlags;
        this.blockClassFlags = blockClassFlags;
        this.minFilledY = minFilledY;
        this.maxFilledY = maxFilledY;

        this.hasSurfaceCandidate = this.finishSurfaceCandidate(this.paletteFlags, this.blockClassFlags);
        if (this.hasSurfaceCandidate) {
            this.blockClassFlags |= CLASS_SURFACE_CANDIDATE;
        }
        this.hasAir = (this.paletteFlags & PALETTE_AIR) != 0;
        this.hasWater = (this.paletteFlags & PALETTE_WATER) != 0;
        this.hasLava = (this.paletteFlags & PALETTE_LAVA) != 0;
        this.hasStoneLike = (this.blockClassFlags & CLASS_STONE_LIKE) != 0;
        this.hasDirtLike = (this.blockClassFlags & CLASS_DIRT_LIKE) != 0;
        this.hasReplaceable = (this.blockClassFlags & CLASS_REPLACEABLE) != 0;
        this.hasOreTarget = (this.blockClassFlags & CLASS_ORE_TARGET) != 0;
        this.hasTreeSoil = (this.blockClassFlags & CLASS_TREE_SOIL) != 0;
    }

    private void resetColumnRanges() {
        Arrays.fill(this.columnMinFilledLocalY, (byte) EMPTY_LOCAL_Y);
        Arrays.fill(this.columnMaxFilledLocalY, (byte) EMPTY_LOCAL_Y);
    }

    private boolean columnMaskIntersects(int[] masks, int localX, int localZ, int fromLocalYInclusive, int toLocalYInclusive) {
        if (this.allAirSection) {
            return (masks == this.columnAirMask || masks == this.columnReplaceableMask)
                    && verticalMask(fromLocalYInclusive, toLocalYInclusive) != 0;
        }
        return (masks[columnIndex(localX, localZ)] & verticalMask(fromLocalYInclusive, toLocalYInclusive)) != 0;
    }

    private boolean columnMaskContainsBlockPos(int[] masks, int blockX, int blockY, int blockZ) {
        int localY = localY(blockY);
        if (localY < 0) {
            return false;
        }
        if (this.allAirSection) {
            return masks == this.columnAirMask || masks == this.columnReplaceableMask;
        }
        int index = columnIndexFromBlock(blockX, blockZ);
        return (masks[index] & (1 << localY)) != 0;
    }

    private int columnHighestMaskedBlockY(int[] masks, int localX, int localZ) {
        if (this.allAirSection) {
            return masks == this.columnAirMask || masks == this.columnReplaceableMask
                    ? (this.sectionY << 4) + 15
                    : Integer.MIN_VALUE;
        }
        return this.columnHighestMaskedBlockY(masks[columnIndex(localX, localZ)], localX, localZ);
    }

    private int columnHighestMaskedBlockY(int mask, int localX, int localZ) {
        if (mask == 0) {
            return Integer.MIN_VALUE;
        }
        int highestLocalY = 31 - Integer.numberOfLeadingZeros(mask);
        return (this.sectionY << 4) + highestLocalY;
    }

    private int localY(int blockY) {
        if (!this.containsBlockY(blockY)) {
            return -1;
        }
        return blockY & 15;
    }

    private static int columnIndex(int localX, int localZ) {
        return (localZ << 4) | localX;
    }

    private static int columnIndexFromBlock(int blockX, int blockZ) {
        return columnIndex(blockX & 15, blockZ & 15);
    }

    private static int verticalMask(int fromLocalYInclusive, int toLocalYInclusive) {
        int from = Math.max(0, fromLocalYInclusive);
        int to = Math.min(SECTION_EDGE - 1, toLocalYInclusive);
        if (from > to) {
            return 0;
        }
        return ((1 << (to - from + 1)) - 1) << from;
    }

    private static CachedStateFacts stateFactsById() {
        CachedStateFacts facts = stateFactsById;
        if (facts != null) {
            return facts;
        }
        synchronized (SectionDescriptor.class) {
            facts = stateFactsById;
            if (facts != null) {
                return facts;
            }
            BlockState[] states = FastBlockStateCache.STATES;
            if (states == null) {
                FastBlockStateCache.init(GeneratorAccelerator.platform);
                states = FastBlockStateCache.STATES;
            }
            int[] paletteFlags = new int[states.length];
            int[] blockClassFlags = new int[states.length];
            int[] maskFlags = new int[states.length];
            int[] filledBits = new int[states.length];
            for (int i = 0; i < states.length; i++) {
                computeStateFacts(states[i], i, paletteFlags, blockClassFlags, maskFlags, filledBits);
            }
            facts = new CachedStateFacts(paletteFlags, blockClassFlags, maskFlags, filledBits);
            stateFactsById = facts;
            return facts;
        }
    }

    private static void computeStateFacts(
            BlockState state,
            int index,
            int[] paletteFlagsById,
            int[] blockClassFlagsById,
            int[] maskFlagsById,
            int[] filledBitsById
    ) {
        if (state == null) {
            state = Blocks.AIR.defaultBlockState();
        }

        Block block = state.getBlock();
        int paletteFlags = 0;
        int blockClassFlags = 0;
        int maskFlags = 0;
        boolean filled = !state.isAir();

        if (!filled) {
            paletteFlagsById[index] = PALETTE_AIR;
            blockClassFlagsById[index] = CLASS_REPLACEABLE;
            maskFlagsById[index] = MASK_AIR | MASK_REPLACEABLE;
            return;
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (fluidState.getType() == Fluids.WATER) {
                paletteFlags |= PALETTE_WATER;
                blockClassFlags |= CLASS_REPLACEABLE;
                maskFlags |= MASK_WATER | MASK_REPLACEABLE;
            } else if (fluidState.getType() == Fluids.LAVA) {
                paletteFlags |= PALETTE_LAVA;
                maskFlags |= MASK_LAVA;
            }
        }

        if (OCEAN_FLOOR_OPAQUE.test(state)) {
            paletteFlags |= PALETTE_SOLID;
            maskFlags |= MASK_SOLID;
        }
        if (MOTION_BLOCKING_OPAQUE.test(state)) {
            maskFlags |= MASK_MOTION_BLOCKING;
        }

        if (isStoneLike(state, block)) {
            blockClassFlags |= CLASS_STONE_LIKE | CLASS_ORE_TARGET;
            maskFlags |= MASK_STONE_LIKE;
        } else if (isDirtLike(state, block)) {
            blockClassFlags |= CLASS_DIRT_LIKE | CLASS_TREE_SOIL;
            maskFlags |= MASK_DIRT_LIKE | MASK_TREE_SOIL;
        } else if (isTreeSoilLike(state, block)) {
            blockClassFlags |= CLASS_TREE_SOIL;
            maskFlags |= MASK_TREE_SOIL;
        }

        if (isLooseReplaceable(block)) {
            blockClassFlags |= CLASS_REPLACEABLE;
            maskFlags |= MASK_REPLACEABLE;
        }

        paletteFlagsById[index] = paletteFlags;
        blockClassFlagsById[index] = blockClassFlags;
        maskFlagsById[index] = maskFlags;
        filledBitsById[index] = 1;
    }

    private static int maskFlagsForState(BlockState state) {
        Block block = state.getBlock();
        FluidState fluidState = state.getFluidState();
        if (state.isAir()) {
            return MASK_AIR | MASK_REPLACEABLE;
        }

        int maskFlags = 0;
        if (!fluidState.isEmpty()) {
            if (fluidState.getType() == Fluids.WATER) {
                maskFlags |= MASK_WATER | MASK_REPLACEABLE;
            } else if (fluidState.getType() == Fluids.LAVA) {
                maskFlags |= MASK_LAVA;
            }
        }
        if (OCEAN_FLOOR_OPAQUE.test(state)) {
            maskFlags |= MASK_SOLID;
        }
        if (MOTION_BLOCKING_OPAQUE.test(state)) {
            maskFlags |= MASK_MOTION_BLOCKING;
        }
        if (isStoneLike(state, block)) {
            maskFlags |= MASK_STONE_LIKE;
        } else if (isDirtLike(state, block)) {
            maskFlags |= MASK_DIRT_LIKE | MASK_TREE_SOIL;
        } else if (isTreeSoilLike(state, block)) {
            maskFlags |= MASK_TREE_SOIL;
        }
        if (isLooseReplaceable(block)) {
            maskFlags |= MASK_REPLACEABLE;
        }
        return maskFlags;
    }

    private static int flagsFromColumnMasks(int airMask, int waterMask, int lavaMask, int solidMask) {
        int flags = 0;
        if (airMask != 0) flags |= PALETTE_AIR;
        if (waterMask != 0) flags |= PALETTE_WATER;
        if (lavaMask != 0) flags |= PALETTE_LAVA;
        if (solidMask != 0) flags |= PALETTE_SOLID;
        return flags;
    }

    private static int classFlagsFromColumnMasks(int replaceableMask, int stoneLikeMask, int dirtLikeMask, int treeSoilMask) {
        int flags = 0;
        if (replaceableMask != 0) flags |= CLASS_REPLACEABLE;
        if (stoneLikeMask != 0) flags |= CLASS_STONE_LIKE | CLASS_ORE_TARGET;
        if (dirtLikeMask != 0) flags |= CLASS_DIRT_LIKE | CLASS_TREE_SOIL;
        if (treeSoilMask != 0) flags |= CLASS_TREE_SOIL;
        return flags;
    }

    private static boolean isStoneLike(BlockState state, Block block) {
        return state.is(BlockTags.STONE_ORE_REPLACEABLES)
                || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.GRANITE || block == Blocks.DIORITE
                || block == Blocks.ANDESITE || block == Blocks.TUFF || block == Blocks.CALCITE || block == Blocks.NETHERRACK
                || block == Blocks.BASALT || block == Blocks.BLACKSTONE || block == Blocks.END_STONE;
    }

    private static boolean isDirtLike(BlockState state, Block block) {
        return state.is(BlockTags.DIRT)
                || block == Blocks.DIRT || block == Blocks.GRASS_BLOCK || block == Blocks.COARSE_DIRT || block == Blocks.PODZOL
                || block == Blocks.MYCELIUM || block == Blocks.ROOTED_DIRT || block == Blocks.MUD || block == Blocks.CLAY
                || block == Blocks.FARMLAND;
    }

    private static boolean isLooseReplaceable(Block block) {
        return block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS
                || block == Blocks.FERN || block == Blocks.LARGE_FERN || block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS;
    }

    private static boolean isTreeSoilLike(BlockState state, Block block) {
        return state.is(BlockTags.NYLIUM) || block == Blocks.MOSS_BLOCK;
    }

    private static final class CachedStateFacts {
        private final int[] paletteFlags;
        private final int[] blockClassFlags;
        private final int[] maskFlags;
        private final int[] filledBits;

        private CachedStateFacts(int[] paletteFlags, int[] blockClassFlags, int[] maskFlags, int[] filledBits) {
            this.paletteFlags = paletteFlags;
            this.blockClassFlags = blockClassFlags;
            this.maskFlags = maskFlags;
            this.filledBits = filledBits;
        }
    }

}

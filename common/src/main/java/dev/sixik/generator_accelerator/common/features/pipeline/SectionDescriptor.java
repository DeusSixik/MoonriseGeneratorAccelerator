package dev.sixik.generator_accelerator.common.features.pipeline;

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

    public SectionDescriptor() {
        this.resetColumnRanges();
    }

    public void clear() {
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
        return this.columnPaletteFlags[columnIndex(localX, localZ)];
    }

    public int columnBlockClassFlags(int localX, int localZ) {
        return this.columnBlockClassFlags[columnIndex(localX, localZ)];
    }

    public int columnAirMask(int localX, int localZ) {
        return this.columnAirMask[columnIndex(localX, localZ)];
    }

    public int columnWaterMask(int localX, int localZ) {
        return this.columnWaterMask[columnIndex(localX, localZ)];
    }

    public int columnLavaMask(int localX, int localZ) {
        return this.columnLavaMask[columnIndex(localX, localZ)];
    }

    public int columnSolidMask(int localX, int localZ) {
        return this.columnSolidMask[columnIndex(localX, localZ)];
    }

    public int columnReplaceableMask(int localX, int localZ) {
        return this.columnReplaceableMask[columnIndex(localX, localZ)];
    }

    public int columnMotionBlockingMask(int localX, int localZ) {
        return this.columnMotionBlockingMask[columnIndex(localX, localZ)];
    }

    public int columnStoneLikeMask(int localX, int localZ) {
        return this.columnStoneLikeMask[columnIndex(localX, localZ)];
    }

    public int columnDirtLikeMask(int localX, int localZ) {
        return this.columnDirtLikeMask[columnIndex(localX, localZ)];
    }

    public int columnTreeSoilMask(int localX, int localZ) {
        return this.columnTreeSoilMask[columnIndex(localX, localZ)];
    }

    public boolean isColumnEmpty(int localX, int localZ) {
        return this.columnMinFilledLocalY[columnIndex(localX, localZ)] == EMPTY_LOCAL_Y;
    }

    public int columnMinFilledBlockY(int localX, int localZ) {
        int index = columnIndex(localX, localZ);
        int localY = this.columnMinFilledLocalY[index];
        return localY == EMPTY_LOCAL_Y ? Integer.MAX_VALUE : (this.sectionY << 4) + localY;
    }

    public int columnMaxFilledBlockY(int localX, int localZ) {
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
        int index = columnIndexFromBlock(blockX, blockZ);
        int bit = 1 << localY;
        return ((this.columnAirMask[index] | this.columnReplaceableMask[index]) & bit) != 0;
    }

    public boolean columnHasFluidAt(int blockX, int blockY, int blockZ) {
        int localY = localY(blockY);
        if (localY < 0) {
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
        int index = columnIndexFromBlock(blockX, blockZ);
        return (this.columnSolidMask[index] & (1 << localY)) != 0;
    }

    public boolean columnHasGroundSupportAt(int blockX, int blockY, int blockZ) {
        int localY = localY(blockY);
        if (localY < 0) {
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
        this.clear();
        this.chunk = chunk;
        this.section = section;
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;

        if (section == null || section.hasOnlyAir()) {
            this.hasAir = true;
            this.paletteFlags = PALETTE_AIR;
            Arrays.fill(this.columnPaletteFlags, PALETTE_AIR);
            Arrays.fill(this.columnAirMask, 0xFFFF);
            Arrays.fill(this.columnReplaceableMask, 0xFFFF);
            Arrays.fill(this.columnBlockClassFlags, CLASS_REPLACEABLE);
            this.blockClassFlags = CLASS_REPLACEABLE;
            this.hasReplaceable = true;
            return;
        }

        this.scanSection(section);
    }

    void rebuildColumn(int localX, int localZ) {
        if (this.section == null) {
            return;
        }

        int columnIndex = columnIndex(localX, localZ);
        this.clearColumn(columnIndex);
        for (int localY = 0; localY < SECTION_EDGE; localY++) {
            this.acceptColumnState(this.section.getBlockState(localX, localY, localZ), columnIndex, localY, 1 << localY);
        }
        this.finishColumnFlags(columnIndex);
        this.refreshAggregates();
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
        this.paletteFlags = 0;
        this.blockClassFlags = 0;
        this.minFilledY = Integer.MAX_VALUE;
        this.maxFilledY = Integer.MIN_VALUE;

        int minSectionBlockY = this.sectionY << 4;
        for (int columnIndex = 0; columnIndex < COLUMN_COUNT; columnIndex++) {
            this.paletteFlags |= this.columnPaletteFlags[columnIndex];
            this.blockClassFlags |= this.columnBlockClassFlags[columnIndex];

            int minLocalY = this.columnMinFilledLocalY[columnIndex];
            if (minLocalY != EMPTY_LOCAL_Y) {
                int blockY = minSectionBlockY + minLocalY;
                if (blockY < this.minFilledY) {
                    this.minFilledY = blockY;
                }
            }

            int maxLocalY = this.columnMaxFilledLocalY[columnIndex];
            if (maxLocalY != EMPTY_LOCAL_Y) {
                int blockY = minSectionBlockY + maxLocalY;
                if (blockY > this.maxFilledY) {
                    this.maxFilledY = blockY;
                }
            }
        }

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
        return (masks[columnIndex(localX, localZ)] & verticalMask(fromLocalYInclusive, toLocalYInclusive)) != 0;
    }

    private boolean columnMaskContainsBlockPos(int[] masks, int blockX, int blockY, int blockZ) {
        int localY = localY(blockY);
        if (localY < 0) {
            return false;
        }
        int index = columnIndexFromBlock(blockX, blockZ);
        return (masks[index] & (1 << localY)) != 0;
    }

    private int columnHighestMaskedBlockY(int[] masks, int localX, int localZ) {
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
}

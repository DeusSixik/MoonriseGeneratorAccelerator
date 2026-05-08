package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

public final class SectionDescriptor {

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
    }

    public boolean isEmpty() {
        return this.minFilledY == Integer.MAX_VALUE;
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
            return;
        }

        this.scanPalette(section, sectionY << 4);
    }

    private void scanPalette(LevelChunkSection section, int minSectionBlockY) {
        PalettedContainer<BlockState> states = section.states;
        PalettedContainer.Data<BlockState> data = states.data;
        Palette<BlockState> palette = data.palette();
        int paletteSize = palette.getSize();
        for (int i = 0; i < paletteSize; i++) {
            BlockState state = palette.valueFor(i);
            if (state != null) {
                this.acceptPaletteState(state, minSectionBlockY);
            }
        }
        this.finishFlags();
    }

    private void acceptPaletteState(BlockState state, int minSectionBlockY) {
        if (state.isAir()) {
            this.hasAir = true;
            this.paletteFlags |= PALETTE_AIR;
            this.hasReplaceable = true;
            this.blockClassFlags |= CLASS_REPLACEABLE;
            return;
        }

        if (this.minFilledY == Integer.MAX_VALUE) {
            this.minFilledY = minSectionBlockY;
            this.maxFilledY = minSectionBlockY + 15;
        }

        Block block = state.getBlock();
        if (block == Blocks.WATER) {
            this.hasWater = true;
            this.paletteFlags |= PALETTE_WATER;
            this.hasReplaceable = true;
            this.blockClassFlags |= CLASS_REPLACEABLE;
        } else if (block == Blocks.LAVA) {
            this.hasLava = true;
            this.paletteFlags |= PALETTE_LAVA;
        } else {
            this.paletteFlags |= PALETTE_SOLID;
        }

        if (isStoneLike(block)) {
            this.hasStoneLike = true;
            this.hasOreTarget = true;
            this.blockClassFlags |= CLASS_STONE_LIKE | CLASS_ORE_TARGET;
        } else if (isDirtLike(block)) {
            this.hasDirtLike = true;
            this.hasTreeSoil = true;
            this.blockClassFlags |= CLASS_DIRT_LIKE;
            this.blockClassFlags |= CLASS_TREE_SOIL;
        } else if (isTreeSoilLike(block)) {
            this.hasTreeSoil = true;
            this.blockClassFlags |= CLASS_TREE_SOIL;
        } else if (isLooseReplaceable(block)) {
            this.hasReplaceable = true;
            this.blockClassFlags |= CLASS_REPLACEABLE;
        }
    }

    private void finishFlags() {
        this.hasSurfaceCandidate = this.hasAir && (this.hasDirtLike || this.hasStoneLike || (this.paletteFlags & PALETTE_SOLID) != 0);
        if (this.hasSurfaceCandidate) {
            this.blockClassFlags |= CLASS_SURFACE_CANDIDATE;
        }
    }

    private static boolean isStoneLike(Block block) {
        return block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.GRANITE || block == Blocks.DIORITE
                || block == Blocks.ANDESITE || block == Blocks.TUFF || block == Blocks.CALCITE || block == Blocks.NETHERRACK
                || block == Blocks.BASALT || block == Blocks.BLACKSTONE || block == Blocks.END_STONE;
    }

    private static boolean isDirtLike(Block block) {
        return block == Blocks.DIRT || block == Blocks.GRASS_BLOCK || block == Blocks.COARSE_DIRT || block == Blocks.PODZOL
                || block == Blocks.MYCELIUM || block == Blocks.ROOTED_DIRT || block == Blocks.MUD || block == Blocks.CLAY;
    }

    private static boolean isLooseReplaceable(Block block) {
        return block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS
                || block == Blocks.FERN || block == Blocks.LARGE_FERN || block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS;
    }

    public boolean mayContainTreeVolume() {
        return this.hasAir || this.hasReplaceable;
    }

    public boolean maySupportTreeBase() {
        return this.hasTreeSoil || this.hasSurfaceCandidate;
    }

    private static boolean isTreeSoilLike(Block block) {
        return block == Blocks.MOSS_BLOCK;
    }
}

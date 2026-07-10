package dev.sixik.generator_accelerator.common.surface_compiler.snapshot;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import java.util.Arrays;

public final class SnapshotResolver {
    public SurfaceReadSnapshot resolve(SnapshotPlan plan, ChunkAccess chunk) {
        if (plan == null || plan.domain() == SnapshotPlan.SnapshotDomain.NO_SNAPSHOT) {
            return EmptySnapshot.INSTANCE;
        }
        if (plan.domain() == SnapshotPlan.SnapshotDomain.VANILLA_ONLY) {
            return UnavailableSnapshot.INSTANCE;
        }
        if (chunk == null) {
            return UnavailableSnapshot.INSTANCE;
        }
        return switch (plan.domain()) {
            case SECTION_BORROW, COLUMN_FACTS -> new ChunkSectionSnapshot(chunk, false);
            case SECTION_COPY_ON_READ, COLUMN_BAND_COPY, HALO_READ_ONLY -> new ChunkSectionSnapshot(chunk, true);
            case NO_SNAPSHOT -> EmptySnapshot.INSTANCE;
            case VANILLA_ONLY -> UnavailableSnapshot.INSTANCE;
        };
    }

    private enum EmptySnapshot implements SurfaceReadSnapshot {
        INSTANCE;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public BlockState getBlockState(int x, int y, int z) {
            return Blocks.VOID_AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(int x, int y, int z) {
            return getBlockState(x, y, z).getFluidState();
        }
    }

    private enum UnavailableSnapshot implements SurfaceReadSnapshot {
        INSTANCE;

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public BlockState getBlockState(int x, int y, int z) {
            throw new IllegalStateException("surface snapshot unavailable");
        }

        @Override
        public FluidState getFluidState(int x, int y, int z) {
            throw new IllegalStateException("surface snapshot unavailable");
        }
    }

    private static final class ChunkSectionSnapshot implements SurfaceReadSnapshot {
        private final ChunkAccess chunk;
        private final LevelChunkSection[] borrowedSections;
        private final int[][] copiedRawSections;
        private final int minBuildY;

        private ChunkSectionSnapshot(ChunkAccess chunk, boolean copy) {
            this.chunk = chunk;
            this.minBuildY = chunk.getMinBuildHeight();
            LevelChunkSection[] sections = chunk.getSections();
            this.borrowedSections = sections == null ? new LevelChunkSection[0] : Arrays.copyOf(sections, sections.length);
            this.copiedRawSections = copy ? copyRaw(this.borrowedSections) : null;
        }

        @Override
        public boolean available() {
            return this.borrowedSections.length > 0;
        }

        @Override
        public BlockState getBlockState(int x, int y, int z) {
            int sectionIndex = this.chunk.getSectionIndex(y);
            if (sectionIndex < 0 || sectionIndex >= this.borrowedSections.length) {
                sectionIndex = (y - this.minBuildY) >> 4;
            }
            if (sectionIndex < 0 || sectionIndex >= this.borrowedSections.length) {
                return Blocks.VOID_AIR.defaultBlockState();
            }
            int localX = x & 15;
            int localY = y & 15;
            int localZ = z & 15;
            if (this.copiedRawSections != null) {
                int[] raw = this.copiedRawSections[sectionIndex];
                if (raw != null) {
                    return Block.stateById(raw[(localY << 8) | (localZ << 4) | localX]);
                }
            }
            LevelChunkSection section = this.borrowedSections[sectionIndex];
            return section == null ? Blocks.VOID_AIR.defaultBlockState() : section.getBlockState(localX, localY, localZ);
        }

        @Override
        public FluidState getFluidState(int x, int y, int z) {
            return getBlockState(x, y, z).getFluidState();
        }

        private static int[][] copyRaw(LevelChunkSection[] sections) {
            int[][] copies = new int[sections.length][];
            for (int i = 0; i < sections.length; i++) {
                int[] raw = sections[i] == null ? null : LevelChunkSection$FlatBlockArray.rawData(sections[i]);
                copies[i] = raw == null ? null : Arrays.copyOf(raw, raw.length);
            }
            return copies;
        }
    }
}

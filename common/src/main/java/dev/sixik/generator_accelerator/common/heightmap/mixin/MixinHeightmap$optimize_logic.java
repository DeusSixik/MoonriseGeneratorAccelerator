package dev.sixik.generator_accelerator.common.heightmap.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;
import java.util.function.Predicate;

@Mixin(Heightmap.class)
public abstract class MixinHeightmap$optimize_logic {

    @Shadow
    @Final private ChunkAccess chunk;
    @Shadow @Final
    private Predicate<BlockState> isOpaque;
    @Shadow @Final private BitStorage data;

    @Shadow protected abstract void setHeight(int x, int z, int y);
    @Shadow protected abstract int getFirstAvailable(int index);
    @Shadow private static int getIndex(int x, int z) { return 0; }

    /**
     * @author Sixik
     * @reason Optimize "Hole Punching" by skipping empty sections during downward scan.
     */
    @Overwrite
    public boolean update(int x, int y, int z, BlockState state) {
        int index = getIndex(x, z);
        int currentTop = this.getFirstAvailable(index);

        if (y <= currentTop - 2) return false;

        if (this.isOpaque.test(state)) {
            if (y >= currentTop) {
                this.setHeight(x, z, y + 1);
                return true;
            }
            return false;
        }

        if (currentTop - 1 != y) return false;

        final int minY = this.chunk.getMinBuildHeight();

        for (int searchY = y - 1; searchY >= minY; searchY--) {
            int sectionIndex = this.chunk.getSectionIndex(searchY);
            LevelChunkSection section = this.chunk.getSection(sectionIndex);

            if (section.hasOnlyAir()) {
                searchY = this.chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                continue;
            }

            int sectionBottom = this.chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            for (; searchY >= sectionBottom; searchY--) {
                BlockState checkState = section.getBlockState(x, searchY & 15, z);
                if (this.isOpaque.test(checkState)) {
                    this.setHeight(x, z, searchY + 1);
                    return true;
                }
            }
        }

        this.setHeight(x, z, minY);
        return true;
    }

    /**
     * @author Sixik
     * @reason Optimized priming: Uses bitmasks instead of Lists, skips empty sections, zero allocation in loop.
     */
    @Overwrite
    public static void primeHeightmaps(ChunkAccess chunk, Set<Heightmap.Types> types) {
        int count = types.size();
        if (count == 0) return;

        Heightmap[] maps = new Heightmap[count];
        Predicate<BlockState>[] predicates = new Predicate[count];

        int idx = 0;
        for (Heightmap.Types t : types) {
            maps[idx] = chunk.getOrCreateHeightmapUnprimed(t);
            predicates[idx] = t.isOpaque();
            idx++;
        }

        final int highestY = chunk.getHighestSectionPosition() + 16;
        final int minY = chunk.getMinBuildHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int remaining = (1 << count) - 1;

                for (int y = highestY - 1; y >= minY; y--) {
                    int sectionIndex = chunk.getSectionIndex(y);
                    LevelChunkSection section = chunk.getSection(sectionIndex);

                    if (section.hasOnlyAir()) {
                        y = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                        continue;
                    }

                    BlockState state = section.getBlockState(x, y & 15, z);

                    if (!state.isAir()) {
                        for (int i = 0; i < count; i++) {
                            if ((remaining & (1 << i)) != 0 && predicates[i].test(state)) {
                                maps[i].setHeight(x, z, y + 1);
                                remaining &= ~(1 << i);
                            }
                        }
                        if (remaining == 0) break;
                    }
                }

                if (remaining != 0) {
                    for (int i = 0; i < count; i++) {
                        if ((remaining & (1 << i)) != 0) {
                            maps[i].setHeight(x, z, minY);
                        }
                    }
                }
            }
        }
    }
}

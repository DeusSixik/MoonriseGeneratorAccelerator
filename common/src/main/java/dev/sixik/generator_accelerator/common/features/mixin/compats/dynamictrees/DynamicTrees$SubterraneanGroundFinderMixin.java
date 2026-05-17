package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.worldgen.SubterraneanGroundFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Mixin(value = SubterraneanGroundFinder.class, remap = false)
public abstract class DynamicTrees$SubterraneanGroundFinderMixin {
    @Unique
    private static final List<BlockPos> GA$NO_LAYERS = Collections.singletonList(BlockPos.ZERO);

    @Unique
    private static final ThreadLocal<ArrayList<BlockPos>> GA$POSITIONS =
            ThreadLocal.withInitial(() -> new ArrayList<>(8));

    @Unique
    private static final TagKey<Biome> GA$UNDERGROUND_BIOMES =
            TagKey.create(Registries.BIOME, ResourceLocation.parse("c:is_underground"));

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SCAN_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<int[]> GA$LAYERS =
            ThreadLocal.withInitial(() -> new int[16]);

    /**
     * @author Sixik
     * @reason Collapse multiple block-state reads and avoid temporary BlockPos
     * allocation in Dynamic Trees underground layer scan.
     */
    @Overwrite(remap = false)
    protected boolean isReplaceable(LevelAccessor level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return (state.isAir() || state.is(BlockTags.REPLACEABLE_BY_TREES)) && state.getFluidState().isEmpty();
    }

    /**
     * @author Sixik
     * @reason Use a thread-local primitive layer buffer instead of allocating an
     * ArrayList<Integer> and boxed integers for every Dynamic Trees disc.
     */
    @Overwrite(remap = false)
    public List<BlockPos> findGround(LevelAccessor level, BlockPos start, @Nullable Heightmap.Types heightmap) {
        int layerCount = ga$findLayerHeights(level, start);
        if (layerCount == 0) {
            return GA$NO_LAYERS;
        }

        int[] layers = GA$LAYERS.get();
        int x = start.getX();
        int z = start.getZ();
        boolean hasCeiling = level.dimensionType().hasCeiling();
        BlockPos.MutableBlockPos pos = GA$SCAN_POS.get();

        ArrayList<BlockPos> positions = GA$POSITIONS.get();
        positions.clear();
        positions.ensureCapacity(layerCount);

        if (hasCeiling) {
            for (int i = 0; i < layerCount; i++) {
                positions.add(new BlockPos(x, layers[i], z));
            }
        } else {
            for (int i = 0; i < layerCount; i++) {
                int y = layers[i];
                pos.set(x, y, z);
                if (level.getBiome(pos).is(GA$UNDERGROUND_BIOMES)) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        return positions.isEmpty() ? GA$NO_LAYERS : positions;
    }

    @Unique
    private int ga$findLayerHeights(LevelAccessor level, BlockPos start) {
        int x = start.getX();
        int z = start.getZ();
        int maxY = level.getChunk(start).getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = GA$SCAN_POS.get().set(x, minY, z);
        int[] layers = GA$LAYERS.get();
        int count = 0;

        while (CoordRange.inRange(pos.getY(), minY, maxY)) {
            while (!this.isReplaceable(level, pos) && CoordRange.inRange(pos.getY(), minY, maxY)) {
                pos.move(Direction.UP, 4);
            }
            while (this.isReplaceable(level, pos) && CoordRange.inRange(pos.getY(), minY, maxY)) {
                pos.move(Direction.DOWN);
            }

            int y = pos.getY();
            pos.setY(y + 6);
            boolean hasAirAbove = this.isReplaceable(level, pos);
            pos.setY(y);
            if (hasAirAbove) {
                if (count == layers.length) {
                    int[] grown = new int[layers.length << 1];
                    System.arraycopy(layers, 0, grown, 0, layers.length);
                    GA$LAYERS.set(grown);
                    layers = grown;
                }
                layers[count++] = y;
            }

            pos.move(Direction.UP, 8);
            while (this.isReplaceable(level, pos) && CoordRange.inRange(pos.getY(), minY, maxY)) {
                pos.move(Direction.UP, 4);
            }
        }

        return count == 0 ? 0 : count - 1;
    }

    @Unique
    private static final class CoordRange {
        private static boolean inRange(int y, int minY, int maxY) {
            return y >= minY && y <= maxY;
        }
    }
}

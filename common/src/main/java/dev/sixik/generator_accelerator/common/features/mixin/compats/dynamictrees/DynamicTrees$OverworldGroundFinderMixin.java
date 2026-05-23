package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.api.worldgen.GroundFinder;
import com.dtteam.dynamictrees.utility.CoordUtils;
import com.dtteam.dynamictrees.worldgen.OverworldGroundFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Mixin(value = OverworldGroundFinder.class, remap = false)
public abstract class DynamicTrees$OverworldGroundFinderMixin {
    @Unique
    private static final TagKey<Biome> GA$UNDERGROUND_BIOMES =
            TagKey.create(Registries.BIOME, ResourceLocation.parse("c:is_underground"));

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SCAN_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * @author Sixik
     * @reason Avoid per-disc LinkedList, ResourceLocation and TagKey allocations
     * while scanning Dynamic Trees cave/surface ground candidates.
     */
    @Overwrite(remap = false)
    public List<BlockPos> findGround(LevelAccessor level, BlockPos start, @Nullable Heightmap.Types heightmap) {
        BlockPos surface = heightmap == null
                ? CoordUtils.findWorldSurface(level, start, true)
                : CoordUtils.findWorldSurface(level, start, heightmap);

        BlockPos.MutableBlockPos pos = GA$SCAN_POS.get();
        int x = start.getX();
        int z = start.getZ();
        int minY = level.getMinBuildHeight();
        int maxY = surface.getY();
        boolean caveBiomeFound = false;
        for (int y = 0; y >= minY && y <= maxY; y -= 10) {
            pos.set(x, y, z);
            if (level.getBiome(pos).is(GA$UNDERGROUND_BIOMES)) {
                caveBiomeFound = true;
                break;
            }
        }

        if (!caveBiomeFound) {
            return Collections.singletonList(surface);
        }

        List<BlockPos> subterranean = GroundFinder.SUBTERRANEAN.findGround(level, start, heightmap);
        ArrayList<BlockPos> result = new ArrayList<>(1 + subterranean.size());
        result.add(surface);
        result.addAll(subterranean);
        return result;
    }
}

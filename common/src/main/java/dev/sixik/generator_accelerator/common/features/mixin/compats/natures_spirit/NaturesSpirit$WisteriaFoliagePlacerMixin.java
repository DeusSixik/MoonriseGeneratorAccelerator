package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import net.hibiscus.naturespirit.world.foliage_placer.WisteriaFoliagePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = WisteriaFoliagePlacer.class, remap = false)
public abstract class NaturesSpirit$WisteriaFoliagePlacerMixin extends FoliagePlacer {
    protected NaturesSpirit$WisteriaFoliagePlacerMixin(IntProvider radius, IntProvider offset) {
        super(radius, offset);
    }

    /**
     * @author Sixik
     * @reason Wisteria foliage performs 240 chained relative(DOWN) allocations per tree.
     * Reuse one mutable position and feed leaves directly to GA's packed foliage setter.
     */
    @Overwrite(remap = false)
    protected void createFoliage(
            LevelSimulatedReader world,
            FoliageSetter placer,
            RandomSource random,
            TreeConfiguration config,
            int trunkHeight,
            FoliageAttachment treeNode,
            int foliageHeight,
            int radius,
            int offset
    ) {
        BlockPos root = treeNode.pos();
        BlockPos.MutableBlockPos center = new BlockPos.MutableBlockPos(root.getX(), root.getY() + offset, root.getZ());
        BlockPos.MutableBlockPos leaf = new BlockPos.MutableBlockPos();

        this.placeLeavesRow(world, placer, random, config, center, radius, -1, true);
        this.placeLeavesRow(world, placer, random, config, center, radius + 1, 0, true);
        this.placeLeavesRow(world, placer, random, config, center, radius, 1, true);

        int baseX = center.getX();
        int baseY = center.getY();
        int baseZ = center.getZ();
        for (int i = 0; i < 60; ++i) {
            int x = baseX + random.nextInt(radius) - random.nextInt(radius);
            int z = baseZ + random.nextInt(radius) - random.nextInt(radius);
            ga$tryLeaf(world, placer, random, config, leaf, x, baseY - 2, z);
            ga$tryLeaf(world, placer, random, config, leaf, x, baseY - 3, z);
            ga$tryLeaf(world, placer, random, config, leaf, x, baseY - 4, z);
        }

        int wideRadius = radius + 2;
        for (int i = 0; i < 10; ++i) {
            int x = baseX + random.nextInt(wideRadius) - random.nextInt(wideRadius);
            int z = baseZ + random.nextInt(wideRadius) - random.nextInt(wideRadius);
            ga$tryLeaf(world, placer, random, config, leaf, x, baseY, z);
            ga$tryLeaf(world, placer, random, config, leaf, x, baseY - 1, z);
            ga$tryLeaf(world, placer, random, config, leaf, x, baseY - 2, z);
        }

        for (int i = 0; i < 10; ++i) {
            int x = baseX + random.nextInt(wideRadius) - random.nextInt(wideRadius);
            int z = baseZ + random.nextInt(wideRadius) - random.nextInt(wideRadius);
            ga$tryLeaf(world, placer, random, config, leaf, x, baseY, z);
            ga$tryLeaf(world, placer, random, config, leaf, x, baseY - 1, z);
        }

        for (int i = 0; i < 80; ++i) {
            ga$tryLeaf(
                    world,
                    placer,
                    random,
                    config,
                    leaf,
                    baseX + random.nextInt(wideRadius) - random.nextInt(wideRadius),
                    baseY,
                    baseZ + random.nextInt(wideRadius) - random.nextInt(wideRadius)
            );
        }
    }

    @Unique
    private static void ga$tryLeaf(
            LevelSimulatedReader world,
            FoliageSetter placer,
            RandomSource random,
            TreeConfiguration config,
            BlockPos.MutableBlockPos pos,
            int x,
            int y,
            int z
    ) {
        pos.set(x, y, z);
        tryPlaceLeaf(world, placer, random, config, pos);
    }
}

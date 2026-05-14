package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import net.hibiscus.naturespirit.world.foliage_placer.GroundedBushFoliagePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = GroundedBushFoliagePlacer.class, remap = false)
public abstract class NaturesSpirit$GroundedBushFoliagePlacerMixin extends FoliagePlacer {
    @Shadow
    @Final
    private int leafPlacementAttempts;

    protected NaturesSpirit$GroundedBushFoliagePlacerMixin(IntProvider radius, IntProvider offset) {
        super(radius, offset);
    }

    /**
     * @author Sixik
     * @reason Avoid MutableBlockPos.below() allocation in every grounded-bush leaf attempt.
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
        int baseX = root.getX();
        int baseY = root.getY() - 1;
        int baseZ = root.getZ();
        BlockPos.MutableBlockPos leaf = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();

        for (int yOffset = 0; yOffset <= foliageHeight; yOffset++) {
            int attempts = this.leafPlacementAttempts / (yOffset + 1);
            for (int attempt = 0; attempt < attempts; ++attempt) {
                int x = baseX + random.nextInt(radius) - random.nextInt(radius);
                int y = baseY + yOffset;
                int z = baseZ + random.nextInt(radius) - random.nextInt(radius);
                below.set(x, y - 1, z);
                if (!world.isStateAtPosition(below, state -> state == Blocks.AIR.defaultBlockState())) {
                    leaf.set(x, y, z);
                    tryPlaceLeaf(world, placer, random, config, leaf);
                }
            }
        }
    }
}

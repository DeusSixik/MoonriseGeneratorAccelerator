package dev.sixik.generator_accelerator.common.features.mixin.compats.natures_spirit;

import dev.sixik.generator_accelerator.common.features.compat.natures_spirit.NaturesSpiritBlocks;
import net.hibiscus.naturespirit.world.tree_decorator.MapleGroundTreeDecorator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(value = MapleGroundTreeDecorator.class, remap = false)
public abstract class NaturesSpirit$MapleGroundTreeDecoratorMixin {
    @Shadow
    @Final
    private BlockStateProvider provider;
    @Shadow
    @Final
    private BlockStateProvider provider2;

    /**
     * @author Sixik
     * @reason Remove an ArrayList copy, stream traversal and BlockPos.offset/above allocations.
     */
    @Overwrite(remap = false)
    public void place(TreeDecorator.Context context) {
        List<BlockPos> logs = context.logs();
        if (logs.isEmpty()) {
            return;
        }

        int baseY = logs.get(0).getY();
        BlockPos.MutableBlockPos origin = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
        for (int index = 0; index < logs.size(); ++index) {
            BlockPos log = logs.get(index);
            if (log.getY() == baseY) {
                ga$setArea(context, log.getX(), log.getY(), log.getZ(), origin, scan);
            }
        }
    }

    @Unique
    private void ga$setArea(TreeDecorator.Context context, int baseX, int baseY, int baseZ, BlockPos.MutableBlockPos origin, BlockPos.MutableBlockPos scan) {
        for (int dx = -2; dx <= 2; ++dx) {
            for (int dz = -2; dz <= 2; ++dz) {
                if (Math.abs(dx) == 2 && Math.abs(dz) == 2) {
                    continue;
                }
                origin.set(baseX + dx, baseY, baseZ + dz);
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                    ga$setColumn(context, origin, scan, this.provider2, true);
                } else {
                    ga$setColumn(context, origin, scan, this.provider, false);
                }
            }
        }
    }

    @Unique
    private void ga$setColumn(
            TreeDecorator.Context context,
            BlockPos origin,
            BlockPos.MutableBlockPos scan,
            BlockStateProvider stateProvider,
            boolean outer
    ) {
        RandomSource random = context.random();
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        for (int dy = 2; dy >= -3; --dy) {
            scan.set(x, y + dy, z);
            if (Feature.isGrassOrDirt(context.level(), scan)) {
                context.setBlock(scan, stateProvider.getState(random, origin));
                if (outer) {
                    scan.set(x, y + dy + 1, z);
                    if (context.isAir(scan) && random.nextInt(50) == 0) {
                        context.setBlock(scan, NaturesSpiritBlocks.shiitakeMushroom().defaultBlockState());
                    }
                }
                return;
            }
            if (!context.isAir(scan) && dy < 0) {
                return;
            }
        }
    }
}




package dev.sixik.generator_accelerator.common.features.mixin.place.tree_decorator;

import dev.sixik.generator_accelerator_native_raw.structures.NativeBlockPosBuffer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.AlterGroundDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AlterGroundDecorator.class)
public class MixinAlterGroundDecorator {
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$READ_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Shadow
    @Final
    private BlockStateProvider provider;

    /**
     * @author Sixik
     * @reason Remove heap-side intermediate candidate iteration and process the selected base-Y roots/logs from an off-heap buffer.
     */
    @Overwrite
    public void place(TreeDecorator.Context context) {
        final ObjectArrayList<BlockPos> roots = context.roots();
        final ObjectArrayList<BlockPos> logs = context.logs();

        if (roots.isEmpty() && logs.isEmpty()) {
            return;
        }

        boolean processLogs = false;
        boolean processRoots = false;
        int baseY;

        if (roots.isEmpty()) {
            processLogs = true;
            baseY = logs.get(0).getY();
        } else if (!logs.isEmpty() && roots.get(0).getY() == logs.get(0).getY()) {
            processLogs = true;
            processRoots = true;
            baseY = logs.get(0).getY();
        } else {
            processRoots = true;
            baseY = roots.get(0).getY();
        }

        final BlockPos.MutableBlockPos mutPos = BTS$MUTABLE_POS.get();
        int expectedCapacity = (processLogs ? logs.size() : 0) + (processRoots ? roots.size() : 0);

        try (NativeBlockPosBuffer positions = new NativeBlockPosBuffer(Math.max(1, expectedCapacity))) {
            if (processLogs) {
                bts$collectBaseYPositions(logs, baseY, positions);
            }
            if (processRoots) {
                bts$collectBaseYPositions(roots, baseY, positions);
            }
            bts$processBuffer(context, positions, baseY, mutPos);
        }
    }

    @Unique
    private void bts$collectBaseYPositions(ObjectArrayList<BlockPos> list, int baseY, NativeBlockPosBuffer positions) {
        final Object[] elements = list.elements();
        final int size = list.size();
        for (int i = 0; i < size; i++) {
            final BlockPos pos = (BlockPos) elements[i];
            if (pos.getY() == baseY) {
                positions.add(pos);
            }
        }
    }

    @Unique
    private void bts$processBuffer(TreeDecorator.Context context, NativeBlockPosBuffer positions, int baseY, BlockPos.MutableBlockPos mutPos) {
        final RandomSource random = context.random();
        final BlockPos.MutableBlockPos readPos = BTS$READ_POS.get();
        final int size = positions.size();

        for (int i = 0; i < size; i++) {
            positions.get(i, readPos);
            final int px = readPos.getX();
            final int pz = readPos.getZ();

            bts$placeCircle(context, px - 1, baseY, pz - 1, mutPos);
            bts$placeCircle(context, px + 2, baseY, pz - 1, mutPos);
            bts$placeCircle(context, px - 1, baseY, pz + 2, mutPos);
            bts$placeCircle(context, px + 2, baseY, pz + 2, mutPos);

            for (int j = 0; j < 5; ++j) {
                final int rand = random.nextInt(64);
                final int k = rand % 8;
                final int l = rand / 8;
                if (k == 0 || k == 7 || l == 0 || l == 7) {
                    bts$placeCircle(context, px - 3 + k, baseY, pz - 3 + l, mutPos);
                }
            }
        }
    }

    @Unique
    private void bts$placeCircle(TreeDecorator.Context context, int centerX, int y, int centerZ, BlockPos.MutableBlockPos mutPos) {
        for (int i = -2; i <= 2; ++i) {
            for (int j = -2; j <= 2; ++j) {
                if (Math.abs(i) != 2 || Math.abs(j) != 2) {
                    bts$placeBlockAt(context, centerX + i, y, centerZ + j, mutPos);
                }
            }
        }
    }

    @Unique
    private void bts$placeBlockAt(TreeDecorator.Context context, int x, int y, int z, BlockPos.MutableBlockPos mutPos) {
        for (int i = 2; i >= -3; --i) {
            mutPos.set(x, y + i, z);

            if (Feature.isGrassOrDirt(context.level(), mutPos)) {
                context.setBlock(mutPos, this.provider.getState(context.random(), mutPos));
                break;
            }

            if (!context.isAir(mutPos) && i < 0) {
                break;
            }
        }
    }
}

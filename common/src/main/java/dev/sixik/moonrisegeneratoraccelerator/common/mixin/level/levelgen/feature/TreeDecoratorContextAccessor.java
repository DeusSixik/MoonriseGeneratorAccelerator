package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.feature;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.BiConsumer;

@Deprecated
@Mixin(TreeDecorator.Context.class)
public interface TreeDecoratorContextAccessor {

    @Accessor
    void setLevel(LevelSimulatedReader worldGenLevel);

    @Accessor
    void setDecorationSetter(BiConsumer<BlockPos, BlockState> consumer);

    @Accessor
    void setRandom(RandomSource random);

    @Accessor
    void setLogs(ObjectArrayList<BlockPos> logs);

    @Accessor
    void setLeaves(ObjectArrayList<BlockPos> leaves);

    @Accessor
    void setRoots(ObjectArrayList<BlockPos> roots);
}

package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.feature;


import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.BiConsumer;

@Deprecated
@Mixin(value = TreeDecorator.Context.class, priority = 1500)
public class MixinTreeDecoratorContext {
    @Mutable
    @Shadow
    @Final
    private LevelSimulatedReader level;
    @Mutable
    @Shadow
    @Final
    private BiConsumer<BlockPos, BlockState> decorationSetter;
    @Mutable
    @Shadow
    @Final
    private RandomSource random;
    @Mutable
    @Shadow
    @Final
    private ObjectArrayList<BlockPos> logs;
    @Mutable
    @Shadow
    @Final
    private ObjectArrayList<BlockPos> leaves;
    @Mutable
    @Shadow
    @Final
    private ObjectArrayList<BlockPos> roots;
}

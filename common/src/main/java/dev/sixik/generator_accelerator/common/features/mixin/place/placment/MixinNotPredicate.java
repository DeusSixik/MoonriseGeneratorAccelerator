package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$BlockPredicateExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.NotPredicate")
public abstract class MixinNotPredicate implements GA$BlockPredicateExtension {
    @Shadow
    @Final
    private BlockPredicate predicate;

    @Override
    public boolean ga$testRaw(WorldGenLevel level, int x, int y, int z, BlockPos.MutableBlockPos scratch) {
        return !GA$BlockPredicateExtension.testRaw(this.predicate, level, x, y, z, scratch);
    }

    /**
     * @author Sixik
     * @reason Keep nested block predicates on the raw no-allocation path.
     */
    @Overwrite
    public boolean test(WorldGenLevel level, BlockPos pos) {
        if (pos instanceof BlockPos.MutableBlockPos mutablePos) {
            return !GA$BlockPredicateExtension.testRaw(
                    this.predicate,
                    level,
                    mutablePos.getX(),
                    mutablePos.getY(),
                    mutablePos.getZ(),
                    mutablePos
            );
        }
        return !this.predicate.test(level, pos);
    }
}

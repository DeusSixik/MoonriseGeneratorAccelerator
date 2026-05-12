package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.block.branch.BasicBranchBlock;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = BasicBranchBlock.class, remap = false)
public abstract class DynamicTrees$BasicBranchBlockMixin {
    @Unique
    private static final Direction[] GA$DIRECTIONS = Direction.values();

    @Redirect(
            method = {"checkForRot", "growSignal", "analyse"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"),
            remap = false
    )
    private Direction[] ga$cachedDirections() {
        return GA$DIRECTIONS;
    }
}

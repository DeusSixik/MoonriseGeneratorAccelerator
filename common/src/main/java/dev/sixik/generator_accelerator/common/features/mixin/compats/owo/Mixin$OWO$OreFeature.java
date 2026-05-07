package dev.sixik.generator_accelerator.common.features.mixin.compats.owo;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sixik.generator_accelerator.common.features.FastTarget;
import io.wispforest.owo.util.Maldenhagen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

@Mixin(value = OreFeature.class, priority = 1500)
public class Mixin$OWO$OreFeature {

    @Unique
    private final ThreadLocal<Map<BlockPos, BlockState>> OWO$COPING = ThreadLocal.withInitial(HashMap::new);

    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.common.features.mixin.features.MixinOreFeature",
            name = "doPlace"
    )
    @Inject(
            method = {"@MixinSquared:Handler"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/feature/OreFeature;bts$commitPlacement(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/BulkSectionAccess;Lnet/minecraft/world/level/chunk/LevelChunkSection;[ILnet/minecraft/core/BlockPos$MutableBlockPos;Ldev/sixik/generator_accelerator/common/features/FastTarget;IIIII[ZZ)V",
                    ordinal = 0
            )
    )
    private void malding(
            WorldGenLevel pLevel,
            RandomSource pRandom,
            OreConfiguration pConfig,
            double pMinX,
            double pMaxX,
            double pMinZ,
            double pMaxZ,
            double pMinY,
            double pMaxY,
            int pX,
            int pY,
            int pZ,
            int pWidth,
            int pHeight,
            CallbackInfoReturnable<Boolean> cir,
            @Local(ordinal = 0) BlockPos.MutableBlockPos mutableBlockPos,
            @Local(ordinal = 0) FastTarget target
    ) {
        BlockState state = target.placementState();
        if (Maldenhagen.isOnCopium(state.getBlock())) {
            this.OWO$COPING.get().put(new BlockPos(mutableBlockPos), state);
        }
    }

    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.common.features.mixin.features.MixinOreFeature",
            name = "doPlace"
    )
    @Inject(
            method = {"@MixinSquared:Handler"},
            at = @At("TAIL")
    )
    private void coping(
            WorldGenLevel pLevel,
            RandomSource pRandom,
            OreConfiguration pConfig,
            double pMinX,
            double pMaxX,
            double pMinZ,
            double pMaxZ,
            double pMinY,
            double pMaxY,
            int pX,
            int pY,
            int pZ,
            int pWidth,
            int pHeight,
            CallbackInfoReturnable<Boolean> cir
    ) {
        Map<BlockPos, BlockState> map = this.OWO$COPING.get();
        map.forEach((blockPos, state) -> pLevel.setBlock(blockPos, state, 3));
        map.clear();
    }
}

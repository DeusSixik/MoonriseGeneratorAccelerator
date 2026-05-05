package dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.owo;

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

    @Inject(
            method = "doPlace",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
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
            @Local(ordinal = 0) FastTarget target,
            @Local(ordinal = 0) int currX,
            @Local(ordinal = 1) int currY,
            @Local(ordinal = 2) int currZ
    ){
        final BlockState state = target.placementState();
        if (Maldenhagen.isOnCopium(state.getBlock())) {
            this.OWO$COPING.get().put(new BlockPos(currX, currY, currZ), state);
        }
    }

    @Inject(
            method = "doPlace",
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
    ){
        final Map<BlockPos, BlockState> map = this.OWO$COPING.get();
        if (!map.isEmpty()) {
            for (Map.Entry<BlockPos, BlockState> entry : map.entrySet()) {
                BlockPos blockPos = entry.getKey();
                BlockState state = entry.getValue();
                pLevel.setBlock(blockPos, state, 3);
            }
            map.clear();
        }
    }
}
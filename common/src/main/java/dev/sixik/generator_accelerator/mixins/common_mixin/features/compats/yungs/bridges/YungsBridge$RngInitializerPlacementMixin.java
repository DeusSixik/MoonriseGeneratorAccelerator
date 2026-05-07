package dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.yungs.bridges;

import com.yungnickyoung.minecraft.yungsbridges.world.placement.RngInitializerPlacement;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RngInitializerPlacement.class, remap = false)
public abstract class YungsBridge$RngInitializerPlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        long a = random.nextLong() | 1L;
        long b = random.nextLong() | 1L;
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        random.setSeed(((long) x * a * 951873395712L + 12132586L) * ((long) z * b * 132899567841L + 9789717L) ^ 313281234L);
        output.add(packedPos);
    }
}

package dev.sixik.generator_accelerator.common.features.mixin.compats.yungs.extras;

import com.yungnickyoung.minecraft.yungsextras.world.placement.RngInitializerPlacement;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RngInitializerPlacement.class, remap = false)
public abstract class YungsExtras$RngInitializerPlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        long a = random.nextLong() | 1L;
        long b = random.nextLong() | 1L;

        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);

        random.setSeed(((long) x * a * 341873128712L + 12412146L) * ((long) z * b * 132897987541L + 5813717L) ^ 423487234L);

        output.add(packedPos);
    }
}

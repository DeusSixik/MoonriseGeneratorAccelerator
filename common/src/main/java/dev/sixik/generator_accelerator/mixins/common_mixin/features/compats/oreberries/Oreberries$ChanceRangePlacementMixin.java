package dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.oreberries;

import com.mrbysco.oreberriesreplanted.worldgen.placement.ChanceRangePlacement;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ChanceRangePlacement.class, remap = false)
public abstract class Oreberries$ChanceRangePlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Shadow
    @Final
    public int rarity;

    @Shadow
    @Final
    public int maximum;

    @Shadow
    @Final
    public int topOffset;

    @Shadow
    @Final
    public int bottomOffset;

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        if (random.nextInt(this.rarity) == 0) {
            int i = BlockPos.getX(packedPos);
            int j = BlockPos.getZ(packedPos);
            int k = random.nextInt(this.maximum - this.topOffset) + this.bottomOffset;
            output.add(BlockPos.asLong(i, k, j));
        }
    }
}

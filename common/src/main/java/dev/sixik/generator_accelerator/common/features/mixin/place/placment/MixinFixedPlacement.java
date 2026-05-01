package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.FixedPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(FixedPlacement.class)
public abstract class MixinFixedPlacement extends PlacementModifier implements GA$PlacementModifierExtension {

    @Mutable
    @Shadow
    @Final
    private List<BlockPos> positions;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bts$init(List<BlockPos> list, CallbackInfo ci) {
        positions = new ObjectArrayList<>(list);
    }

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        int chunkX = SectionPos.blockToSectionCoord(BlockPos.getX(packedPos));
        int chunkZ = SectionPos.blockToSectionCoord(BlockPos.getZ(packedPos));

        final ObjectArrayList<BlockPos> list = (ObjectArrayList<BlockPos>) this.positions;
        final Object[] array = list.elements();

        for (int i = 0; i < list.size(); i++) {
            BlockPos pos = (BlockPos) array[i];

            if (chunkX == SectionPos.blockToSectionCoord(pos.getX()) &&
                    chunkZ == SectionPos.blockToSectionCoord(pos.getZ())) {
                output.add(pos.asLong());
            }
        }
    }
}

package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$FixedPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
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
public abstract class MixinFixedPlacement extends PlacementModifier implements GA$PlacementModifierExtension, GA$FixedPlacementAccess {

    @Mutable
    @Shadow
    @Final
    private List<BlockPos> positions;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bts$init(List<BlockPos> list, CallbackInfo ci) {
        ObjectArrayList<BlockPos> copied = new ObjectArrayList<>(list);
        this.positions = copied;
        GeneratorAccelerator.LOGGER_DEBUG.info("Create FixedPlacement size: {}", copied.size());
    }

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public List<BlockPos> ga$fixedPositions() {
        return this.positions;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        int chunkX = SectionPos.blockToSectionCoord(BlockPos.getX(packedPos));
        int chunkZ = SectionPos.blockToSectionCoord(BlockPos.getZ(packedPos));

        if (this.positions instanceof ObjectArrayList<BlockPos> objectList) {
            Object[] array = objectList.elements();
            for (int i = 0; i < objectList.size(); i++) {
                BlockPos pos = (BlockPos) array[i];
                if (chunkX == SectionPos.blockToSectionCoord(pos.getX())
                        && chunkZ == SectionPos.blockToSectionCoord(pos.getZ())) {
                    output.add(pos.asLong());
                }
            }
            return;
        }

        for (int i = 0, size = this.positions.size(); i < size; i++) {
            BlockPos pos = this.positions.get(i);
            if (chunkX == SectionPos.blockToSectionCoord(pos.getX())
                    && chunkZ == SectionPos.blockToSectionCoord(pos.getZ())) {
                output.add(pos.asLong());
            }
        }
    }
}

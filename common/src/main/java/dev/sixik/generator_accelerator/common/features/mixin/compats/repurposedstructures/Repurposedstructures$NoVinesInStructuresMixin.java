package dev.sixik.generator_accelerator.common.features.mixin.compats.repurposedstructures;

import com.llamalad7.mixinextras.sugar.Local;
import com.telepathicgrunt.repurposedstructures.modinit.RSTags;
import com.telepathicgrunt.repurposedstructures.utils.GeneralUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.VinesFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = VinesFeature.class, priority = 1500)
public class Repurposedstructures$NoVinesInStructuresMixin {

    @Inject(
            method = "place",
            at = @At(value = "INVOKE", target = "Ljava/lang/ThreadLocal;get()Ljava/lang/Object;", shift = At.Shift.BEFORE),
            cancellable = true
    )
    public void bts$repurposedstructures_noVinesInStructures(
            FeaturePlaceContext<NoneFeatureConfiguration> context,
            CallbackInfoReturnable<Boolean> cir
    ) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();

        if (!(level instanceof WorldGenRegion region)) return;

        Registry<Structure> structureRegistry = region.registryAccess().registry(Registries.STRUCTURE).get();

        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();

        for (Direction face : Direction.Plane.HORIZONTAL) {
            neighborPos.setWithOffset(origin, face);

            List<StructureStart> structureStarts = GeneralUtils.inboundsValidStartsForAllStructure(
                    region,
                    neighborPos,
                    (struct) -> structureRegistry.getHolderOrThrow(structureRegistry.getResourceKey(struct).get()).is(RSTags.NO_JUNGLE_VINES)
            );

            if (!structureStarts.isEmpty()) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}

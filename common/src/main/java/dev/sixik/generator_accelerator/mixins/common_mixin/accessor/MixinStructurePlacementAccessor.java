package dev.sixik.generator_accelerator.mixins.common_mixin.accessor;

import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StructurePlacement.class)
public interface MixinStructurePlacementAccessor {
    @Accessor("salt")
    int ga$getSalt();
}

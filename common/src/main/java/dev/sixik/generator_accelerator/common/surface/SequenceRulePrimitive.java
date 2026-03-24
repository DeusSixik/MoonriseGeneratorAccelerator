package dev.sixik.generator_accelerator.common.surface;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.NotNull;

public interface SequenceRulePrimitive {

    void bts$setArray(final SurfaceRules.SurfaceRule[] array);

    @NotNull SurfaceRules.SurfaceRule[] bts$getArray();
}
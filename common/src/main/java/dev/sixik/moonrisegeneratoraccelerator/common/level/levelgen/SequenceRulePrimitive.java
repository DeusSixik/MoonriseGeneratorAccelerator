package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.NotNull;

public interface SequenceRulePrimitive {

    void bts$setArray(final SurfaceRules.SurfaceRule[] array);

    @NotNull SurfaceRules.SurfaceRule[] bts$getArray();
}

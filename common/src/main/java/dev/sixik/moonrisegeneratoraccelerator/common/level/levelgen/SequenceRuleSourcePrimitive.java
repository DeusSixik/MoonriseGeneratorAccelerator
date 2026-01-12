package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.NotNull;

public interface SequenceRuleSourcePrimitive {

    void bts$setArray(final SurfaceRules.RuleSource[] array);

    @NotNull SurfaceRules.RuleSource[] bts$getArray();

}

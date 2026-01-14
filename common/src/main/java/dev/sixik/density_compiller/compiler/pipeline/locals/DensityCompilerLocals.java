package dev.sixik.density_compiller.compiler.pipeline.locals;

import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class DensityCompilerLocals {

    public final List<DensityFunction> leaves = new ArrayList<>();
    public final Map<DensityFunction, Integer> leafToId = new IdentityHashMap<>();
}

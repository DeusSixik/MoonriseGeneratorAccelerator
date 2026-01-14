package dev.sixik.density_compiller.compiler.pipeline.locals;

import java.util.*;

public class DensityCompilerLocals {

    public final List<Object> leaves = new ArrayList<>(); // Был List<DensityFunction>
    public final Map<Object, Integer> leafToId = new IdentityHashMap<>(); // Был Map<DensityFunction, Integer>
    public final Map<Integer, String> leafTypes = new HashMap<>(); // Новая карта: ID -> Descriptor
}

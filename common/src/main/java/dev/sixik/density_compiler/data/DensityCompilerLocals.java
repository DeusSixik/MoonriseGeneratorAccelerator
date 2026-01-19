package dev.sixik.density_compiler.data;

import java.util.*;

public class DensityCompilerLocals {

    public final List<Object> leaves = new ArrayList<>();
    public final Map<Object, Integer> leafToId = new IdentityHashMap<>();
    public final Map<Integer, String> leafTypes = new HashMap<>();

    public void clear() {
        leaves.clear();
        leafToId.clear();
        leafTypes.clear();
    }
}

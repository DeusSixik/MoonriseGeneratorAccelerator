package dev.sixik.density_compiler.utils;

import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class UsageAnalyzer implements DensityFunction.Visitor {
    private final Map<DensityFunction, Integer> counts = new HashMap<>();

    @Override
    public DensityFunction apply(DensityFunction df) {
        // Если мы уже видели эту ноду, просто увеличиваем счетчик
        // и НЕ идем вглубь через mapAll, чтобы избежать StackOverflow
        if (counts.containsKey(df)) {
            counts.put(df, counts.get(df) + 1);
            return df;
        }

        // Если видим впервые — сохраняем и идем рекурсивно в детей
        counts.put(df, 1);
        return df.mapAll(this);
    }

    public Set<DensityFunction> getSharedNodes() {
        Set<DensityFunction> shared = new HashSet<>();
        counts.forEach((node, count) -> {
            if (count > 1 && !DensityCompilerUtils.isConst(node)) {
                shared.add(node);
            }
        });
        return shared;
    }
}

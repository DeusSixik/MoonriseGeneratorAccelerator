package dev.sixik.generator_accelerator.common.features;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

public class PrimitivePlacementPool {
    private final List<LongArrayList> pool = new ObjectArrayList<>();
    private int depth = 0;

    public LongArrayList acquire() {
        if (depth >= pool.size()) {
            pool.add(new LongArrayList(64)); // Выделяется только при первом запуске
        }
        LongArrayList list = pool.get(depth++);
        list.clear();
        return list;
    }

    public void release() {
        depth--;
    }
}
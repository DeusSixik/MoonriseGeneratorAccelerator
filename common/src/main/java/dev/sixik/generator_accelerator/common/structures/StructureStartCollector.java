package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.function.Consumer;

public final class StructureStartCollector implements Consumer<StructureStart> {
    private ObjectArrayList<StructureStart> target;

    public void bind(ObjectArrayList<StructureStart> target) {
        this.target = target;
    }

    public void clear() {
        this.target = null;
    }

    @Override
    public void accept(StructureStart structureStart) {
        this.target.add(structureStart);
    }
}

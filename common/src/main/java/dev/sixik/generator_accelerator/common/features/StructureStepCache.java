package dev.sixik.generator_accelerator.common.features;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.Registry;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

public final class StructureStepCache {
    private final Registry<Structure> registry;
    private final ObjectArrayList<Structure>[] byStep;

    @SuppressWarnings("unchecked")
    public StructureStepCache(Registry<Structure> registry, int minStepCount) {
        this.registry = registry;
        int requiredSteps = minStepCount;
        for (Structure structure : registry) {
            requiredSteps = Math.max(requiredSteps, structure.step().ordinal() + 1);
        }

        this.byStep = (ObjectArrayList<Structure>[]) new ObjectArrayList[requiredSteps];
        for (Structure structure : registry) {
            int stepId = structure.step().ordinal();
            ObjectArrayList<Structure> structures = this.byStep[stepId];
            if (structures == null) {
                structures = new ObjectArrayList<>();
                this.byStep[stepId] = structures;
            }
            structures.add(structure);
        }
    }

    public Registry<Structure> registry() {
        return this.registry;
    }

    @Nullable
    public ObjectArrayList<Structure> structuresAt(int step) {
        return step >= 0 && step < this.byStep.length ? this.byStep[step] : null;
    }

    public int stepCount() {
        return this.byStep.length;
    }
}

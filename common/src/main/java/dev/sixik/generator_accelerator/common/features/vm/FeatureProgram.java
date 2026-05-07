package dev.sixik.generator_accelerator.common.features.vm;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

public final class FeatureProgram {
    private final int[] opcodes;
    private final PlacementModifier[] modifiers;
    private final Holder<ConfiguredFeature<?, ?>> feature;
    private final ConfiguredFeature<?, ?> configuredFeature;
    private final int fastOpCount;
    private final int fallbackOpCount;
    private final boolean linearFastOnly;
    private final int specializedExecutor;

    FeatureProgram(int[] opcodes, PlacementModifier[] modifiers, Holder<ConfiguredFeature<?, ?>> feature, int fastOpCount, int fallbackOpCount, boolean linearFastOnly, int specializedExecutor) {
        this.opcodes = opcodes;
        this.modifiers = modifiers;
        this.feature = feature;
        this.configuredFeature = feature.value();
        this.fastOpCount = fastOpCount;
        this.fallbackOpCount = fallbackOpCount;
        this.linearFastOnly = linearFastOnly;
        this.specializedExecutor = specializedExecutor;
    }

    int[] opcodes() {
        return this.opcodes;
    }

    PlacementModifier[] modifiers() {
        return this.modifiers;
    }

    public int opCount() {
        return this.opcodes.length;
    }

    public int opcode(int index) {
        return this.opcodes[index];
    }

    public PlacementModifier modifier(int index) {
        return this.modifiers[index];
    }

    public Holder<ConfiguredFeature<?, ?>> feature() {
        return this.feature;
    }

    public ConfiguredFeature<?, ?> configuredFeature() {
        return this.configuredFeature;
    }

    public boolean hasFallback() {
        return this.fallbackOpCount != 0;
    }

    public int fastOpCount() {
        return this.fastOpCount;
    }

    public int fallbackOpCount() {
        return this.fallbackOpCount;
    }

    public boolean linearFastOnly() {
        return this.linearFastOnly;
    }

    public int specializedExecutor() {
        return this.specializedExecutor;
    }
}

package dev.sixik.generator_accelerator.common.features.pipeline;

public final class DecorationPlan {
    private static final DecorationStepPlan[] EMPTY_STEPS = new DecorationStepPlan[0];

    private final DecorationStepPlan[] steps;
    private final int stepCount;

    public DecorationPlan(DecorationStepPlan[] steps) {
        this.steps = steps == null ? EMPTY_STEPS : steps;
        this.stepCount = this.steps.length;
    }

    public static DecorationPlan empty() {
        return new DecorationPlan(EMPTY_STEPS);
    }

    public DecorationStepPlan step(int step) {
        if (step < 0 || step >= this.stepCount) {
            return null;
        }
        return this.steps[step];
    }

    public DecorationStepPlan[] steps() {
        return this.steps;
    }

    public int stepCount() {
        return this.stepCount;
    }
}

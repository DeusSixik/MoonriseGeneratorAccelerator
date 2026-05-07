package dev.sixik.generator_accelerator.common.carver;

import net.minecraft.world.level.levelgen.LegacyRandomSource;

public final class CaveTunnelBatch {
    public long[] seeds = new long[8];
    public double[] xs = new double[8];
    public double[] ys = new double[8];
    public double[] zs = new double[8];
    public double[] horizontalRadiusMultipliers = new double[8];
    public double[] verticalRadiusMultipliers = new double[8];
    public float[] thicknesses = new float[8];
    public float[] yaws = new float[8];
    public float[] pitches = new float[8];
    public int[] startSteps = new int[8];
    public int[] endSteps = new int[8];
    public double[] yScales = new double[8];
    private LegacyRandomSource[] randoms = new LegacyRandomSource[8];
    private int size;

    public void clear() {
        this.size = 0;
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    public int pop() {
        return --this.size;
    }

    public void push(
            long seed,
            double x,
            double y,
            double z,
            double horizontalRadiusMultiplier,
            double verticalRadiusMultiplier,
            float thickness,
            float yaw,
            float pitch,
            int startStep,
            int endStep,
            double yScale
    ) {
        int index = this.size++;
        if (index == this.seeds.length) {
            this.grow();
        }

        this.seeds[index] = seed;
        this.xs[index] = x;
        this.ys[index] = y;
        this.zs[index] = z;
        this.horizontalRadiusMultipliers[index] = horizontalRadiusMultiplier;
        this.verticalRadiusMultipliers[index] = verticalRadiusMultiplier;
        this.thicknesses[index] = thickness;
        this.yaws[index] = yaw;
        this.pitches[index] = pitch;
        this.startSteps[index] = startStep;
        this.endSteps[index] = endStep;
        this.yScales[index] = yScale;
    }

    public LegacyRandomSource random(int index) {
        LegacyRandomSource random = this.randoms[index];
        if (random == null) {
            random = new LegacyRandomSource(0L);
            this.randoms[index] = random;
        }
        return random;
    }

    private void grow() {
        int newLength = this.seeds.length << 1;
        this.seeds = this.copyOf(this.seeds, newLength);
        this.xs = this.copyOf(this.xs, newLength);
        this.ys = this.copyOf(this.ys, newLength);
        this.zs = this.copyOf(this.zs, newLength);
        this.horizontalRadiusMultipliers = this.copyOf(this.horizontalRadiusMultipliers, newLength);
        this.verticalRadiusMultipliers = this.copyOf(this.verticalRadiusMultipliers, newLength);
        this.thicknesses = this.copyOf(this.thicknesses, newLength);
        this.yaws = this.copyOf(this.yaws, newLength);
        this.pitches = this.copyOf(this.pitches, newLength);
        this.startSteps = this.copyOf(this.startSteps, newLength);
        this.endSteps = this.copyOf(this.endSteps, newLength);
        this.yScales = this.copyOf(this.yScales, newLength);

        LegacyRandomSource[] newRandoms = new LegacyRandomSource[newLength];
        System.arraycopy(this.randoms, 0, newRandoms, 0, this.randoms.length);
        this.randoms = newRandoms;
    }

    private long[] copyOf(long[] source, int newLength) {
        long[] copy = new long[newLength];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private double[] copyOf(double[] source, int newLength) {
        double[] copy = new double[newLength];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private float[] copyOf(float[] source, int newLength) {
        float[] copy = new float[newLength];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private int[] copyOf(int[] source, int newLength) {
        int[] copy = new int[newLength];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }
}

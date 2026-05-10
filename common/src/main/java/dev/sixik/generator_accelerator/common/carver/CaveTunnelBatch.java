package dev.sixik.generator_accelerator.common.carver;

import net.minecraft.world.level.levelgen.LegacyRandomSource;

public final class CaveTunnelBatch {
    private static final int INITIAL_CAPACITY = 8;
    private static final int MAX_RETAINED_CAPACITY = 256;
    private static final int MAX_EXCESSIVE_CAPACITY = 1_024;
    public long[] seeds = new long[INITIAL_CAPACITY];
    public double[] xs = new double[INITIAL_CAPACITY];
    public double[] ys = new double[INITIAL_CAPACITY];
    public double[] zs = new double[INITIAL_CAPACITY];
    public double[] horizontalRadiusMultipliers = new double[INITIAL_CAPACITY];
    public double[] verticalRadiusMultipliers = new double[INITIAL_CAPACITY];
    public float[] thicknesses = new float[INITIAL_CAPACITY];
    public float[] yaws = new float[INITIAL_CAPACITY];
    public float[] pitches = new float[INITIAL_CAPACITY];
    public int[] startSteps = new int[INITIAL_CAPACITY];
    public int[] endSteps = new int[INITIAL_CAPACITY];
    public double[] yScales = new double[INITIAL_CAPACITY];
    private LegacyRandomSource[] randoms = new LegacyRandomSource[INITIAL_CAPACITY];
    private int size;

    public void clear() {
        this.size = 0;
        if (this.seeds.length > MAX_EXCESSIVE_CAPACITY) {
            this.resize(MAX_RETAINED_CAPACITY);
        }
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
        this.resize(newLength);
    }

    private void resize(int newLength) {
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
        System.arraycopy(this.randoms, 0, newRandoms, 0, Math.min(this.randoms.length, newLength));
        this.randoms = newRandoms;
    }

    private long[] copyOf(long[] source, int newLength) {
        long[] copy = new long[newLength];
        System.arraycopy(source, 0, copy, 0, Math.min(source.length, newLength));
        return copy;
    }

    private double[] copyOf(double[] source, int newLength) {
        double[] copy = new double[newLength];
        System.arraycopy(source, 0, copy, 0, Math.min(source.length, newLength));
        return copy;
    }

    private float[] copyOf(float[] source, int newLength) {
        float[] copy = new float[newLength];
        System.arraycopy(source, 0, copy, 0, Math.min(source.length, newLength));
        return copy;
    }

    private int[] copyOf(int[] source, int newLength) {
        int[] copy = new int[newLength];
        System.arraycopy(source, 0, copy, 0, Math.min(source.length, newLength));
        return copy;
    }
}

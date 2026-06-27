package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpecCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.*;

/**
 * Per-compilation collector that hands out stable indices for every "extern" reference
 * the IR captures: noise samplers, opaque DensityFunctions (mod-provided, Markers,
 * Beardifier, EndIslands), spline ASTs, and double constants.
 *
 * <p>Identity-keyed: two textually identical IR references that point to the same
 * {@link NormalNoise} instance share an index, while two distinct instances stay
 * separate. This matches the runtime semantics of vanilla DensityFunctions (where
 * {@code NormalNoise} doesn't override equals).
 *
 * <p>{@link #intern(double)} doubles use value equality though — there's no point in
 * duplicating the literal {@code 0.0}.
 *
 * <p>The pool is finalised by {@link #finishConstants()}, {@link #finishNoises()} etc.,
 * which return the snapshot arrays the generated class needs in its constructor.
 */
public final class ConstantPool {

    private final List<Double> constants = new ArrayList<>();
    private final Map<Long, Integer> constantIndex = new java.util.HashMap<>();

    private final List<NormalNoise> noises = new ArrayList<>();
    private final IdentityHashMap<NormalNoise, Integer> noiseIndex = new IdentityHashMap<>();

    private final List<DensityFunction> externs = new ArrayList<>();
    private final IdentityHashMap<DensityFunction, Integer> externIndex = new IdentityHashMap<>();
    /** Extern indices whose call sites may use {@link dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath}. */
    private final BitSet cacheWrapperFastPathExtern = new BitSet();

    private final List<Object> splines = new ArrayList<>();

    /**
     * Per-noise specialization data keyed by {@link NormalNoise} identity. Each entry
     * is the {@link NoiseSpec} the codegen unrolls into per-octave bytecode. Indices
     * are independent of {@link #noiseIndex} so the un-inlined emission paths can
     * coexist with inlined ones (the first {@code N} noise references go through
     * {@code noiseIndex}, the inlined ones get their own index space here).
     */
    private final List<NoiseSpec> noiseSpecs = new ArrayList<>();
    private final IdentityHashMap<NormalNoise, Integer> noiseSpecIndex = new IdentityHashMap<>();

    private final List<BlendedNoiseSpec> blendedNoiseSpecs = new ArrayList<>();
    private final IdentityHashMap<BlendedNoise, Integer> blendedNoiseSpecIndex = new IdentityHashMap<>();

    /** Intern a double, returning its slot in the {@code constants} array. */
    public int intern(double value) {
        long bits = Double.doubleToRawLongBits(value);
        Integer idx = constantIndex.get(bits);
        if (idx != null) return idx;
        int next = constants.size();
        constants.add(value);
        constantIndex.put(bits, next);
        return next;
    }

    /** Intern a NormalNoise by identity. */
    public int internNoise(NormalNoise noise) {
        Integer idx = noiseIndex.get(noise);
        if (idx != null) return idx;
        int next = noises.size();
        noises.add(noise);
        noiseIndex.put(noise, next);
        return next;
    }

    /** Intern an opaque DensityFunction (Marker, Invoke, EndIslands, etc.) by identity. */
    public int internExtern(DensityFunction df) {
        Integer idx = externIndex.get(df);
        if (idx != null) return idx;
        int next = externs.size();
        externs.add(df);
        externIndex.put(df, next);
        return next;
    }

    /** Mark an extern index as a NoiseChunk cell-cache marker eligible for {@code dfc$tryDirectRead}. */
    public void noteCacheWrapperFastPathExtern(int externIndex) {
        cacheWrapperFastPathExtern.set(externIndex);
    }

    public boolean externHasCacheWrapperFastPath(int externIndex) {
        return cacheWrapperFastPathExtern.get(externIndex);
    }

    /** Append a spline blob (no deduplication; splines compare by structure not identity). */
    public int internSpline(Object spline) {
        int next = splines.size();
        splines.add(spline);
        return next;
    }

    public double[] finishConstants() {
        double[] out = new double[constants.size()];
        for (int i = 0; i < out.length; i++) out[i] = constants.get(i);
        return out;
    }

    public NormalNoise[] finishNoises() {
        return noises.toArray(new NormalNoise[0]);
    }

    public DensityFunction[] finishExterns() {
        return externs.toArray(new DensityFunction[0]);
    }

    public Object[] finishSplines() {
        return splines.toArray();
    }

    /**
     * Intern a {@link NoiseSpec} for {@code noise}, returning a stable index keyed
     * by {@link NormalNoise} identity. Returns {@code -1} when the spec cannot be
     * built (mixin binding failure, etc.) — callers should fall back to the legacy
     * {@link #internNoise} path in that case.
     */
    public int internNoiseSpec(NormalNoise noise) {
        Integer idx = noiseSpecIndex.get(noise);
        if (idx != null) return idx;
        NoiseSpec spec = NoiseSpecCache.specFor(noise);
        if (spec == null) return -1;
        // Defensive cap: refuse to inline noises with active-octave counts so high
        // they would exceed half the per-method bytecode budget. Callers fall back
        // to the legacy NormalNoise.getValue path for these noises.
        if (!NoiseSpecCache.shouldInline(spec)) return -1;
        int next = noiseSpecs.size();
        noiseSpecs.add(spec);
        noiseSpecIndex.put(noise, next);
        return next;
    }

    public NoiseSpec noiseSpec(int idx) { return noiseSpecs.get(idx); }
    public int noiseSpecCount() { return noiseSpecs.size(); }
    public List<NoiseSpec> noiseSpecs() { return noiseSpecs; }

    /**
     * Intern {@link BlendedNoise} for inlined {@link IRNode.InlinedBlendedNoise} emission.
     * Returns {@code -1} when the spec cannot be read (mixin failure).
     */
    public int internBlendedNoiseSpec(BlendedNoise noise) {
        Integer existing = blendedNoiseSpecIndex.get(noise);
        if (existing != null) return existing;
        BlendedNoiseSpec spec = BlendedNoiseSpecCache.specFor(noise);
        if (spec == null) return -1;
        int next = blendedNoiseSpecs.size();
        blendedNoiseSpecs.add(spec);
        blendedNoiseSpecIndex.put(noise, next);
        return next;
    }

    public BlendedNoiseSpec blendedNoiseSpec(int idx) { return blendedNoiseSpecs.get(idx); }
    public int blendedNoiseSpecCount() { return blendedNoiseSpecs.size(); }

    /** Snapshot for native handle layout (indices follow {@link #noiseSpecs()}). */
    public List<BlendedNoiseSpec> blendedNoiseSpecsList() {
        return List.copyOf(blendedNoiseSpecs);
    }

    /**
     * Sum of all {@link ImprovedNoise} payload slots from {@link #noiseSpecs} (NormalNoise
     * inlining) — the blended section starts at this offset in {@link #finishNoiseOctaves()}.
     */
    public int totalNormalNoisePayloadSlots() {
        int t = 0;
        for (NoiseSpec s : noiseSpecs) t += s.totalActiveOctaves();
        return t;
    }

    /**
     * Base index into the flat {@code noiseOctaves} constructor payload for blended spec
     * {@code blendedSpecIndex}.
     */
    public int blendedSpecPayloadBase(int blendedSpecIndex) {
        return totalNormalNoisePayloadSlots() + blendedSpecIndex * BlendedNoiseSpec.PAYLOAD_IMPROVED_NOISES;
    }

    /**
     * Index into the flat {@code noiseOctaves} array for a blended sampler field
     * ({@code section} 0 = main, 1 = min limit, 2 = max limit).
     */
    public int blendedPayloadIndex(int blendedSpecIndex, int section, int subIndex) {
        int base = blendedSpecPayloadBase(blendedSpecIndex);
        if (section == 0) return base + subIndex; // 0..7
        if (section == 1) return base + 8 + subIndex; // 0..15
        return base + 8 + 16 + subIndex; // 0..15
    }

    /**
     * Flatten every interned {@link NoiseSpec} and {@link BlendedNoiseSpec} into the
     * {@code Object[] noiseOctaves} array passed to the generated subclass's constructor.
     * <p>Layout: every {@code NoiseSpec} in order [first, second] active octaves, then
     * each {@code BlendedNoiseSpec} as
     * {@code [main0..7][min0..15][max0..15]} (nulls included when an octave is absent).
     */
    public Object[] finishNoiseOctaves() {
        int totalN = totalNormalNoisePayloadSlots();
        int totalB = blendedNoiseSpecs.size() * BlendedNoiseSpec.PAYLOAD_IMPROVED_NOISES;
        Object[] out = new Object[totalN + totalB];
        int cursor = 0;
        for (NoiseSpec s : noiseSpecs) {
            for (ImprovedNoise oct : s.first().activeOctaves()) {
                out[cursor++] = oct;
            }
            for (ImprovedNoise oct : s.second().activeOctaves()) {
                out[cursor++] = oct;
            }
        }
        for (BlendedNoiseSpec b : blendedNoiseSpecs) {
            for (int i = 0; i < BlendedNoiseSpec.MAIN_OCTAVES; i++) {
                out[cursor++] = b.mainOctaves()[i];
            }
            for (int j = 0; j < BlendedNoiseSpec.LIMIT_OCTAVES; j++) {
                out[cursor++] = b.minLimitOctaves()[j];
            }
            for (int j = 0; j < BlendedNoiseSpec.LIMIT_OCTAVES; j++) {
                out[cursor++] = b.maxLimitOctaves()[j];
            }
        }
        return out;
    }

    /**
     * Returns the {@code noiseOctaves[]} base offset for the spec at {@code specIdx}.
     * The codegen needs this so it can compute the per-octave AALOAD index when
     * emitting the constructor's PUTFIELDs into the per-octave noise fields.
     */
    public int noiseSpecOctaveBase(int specIdx) {
        int offset = 0;
        for (int i = 0; i < specIdx; i++) {
            offset += noiseSpecs.get(i).totalActiveOctaves();
        }
        return offset;
    }

    public int constantCount()  { return constants.size(); }
    public int noiseCount()     { return noises.size(); }
    public int externCount()    { return externs.size(); }
    public int splineCount()    { return splines.size(); }

    public DensityFunction extern(int idx) { return externs.get(idx); }
    public NormalNoise noise(int idx)      { return noises.get(idx); }
    public double constant(int idx)        { return constants.get(idx); }

    /** Spline object interned at {@code idx} — for fingerprinting / diagnostics. */
    public Object splineObject(int idx)    { return splines.get(idx); }
}

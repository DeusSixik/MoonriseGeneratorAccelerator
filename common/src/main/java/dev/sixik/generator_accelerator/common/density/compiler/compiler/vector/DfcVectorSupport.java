package dev.sixik.generator_accelerator.common.density.compiler.compiler.vector;

import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * JDK incubator Vector API ({@code jdk.incubator.vector}) probe + capability advertiser.
 *
 * <h2>Why probe instead of import</h2>
 *
 * <p>The Vector API ships in the {@code jdk.incubator.vector} module which is
 * <em>not</em> on the default module path; loading it requires the launcher to
 * pass {@code --add-modules jdk.incubator.vector}. If we import {@code DoubleVector}
 * directly anywhere in our class graph, the JVM tries to resolve that class at
 * link time and throws {@link NoClassDefFoundError} on a vanilla launcher — even
 * if the user never asked for SIMD. Symbolic resolution is lazy at the bytecode
 * level (the verifier only checks descriptors, not loadability) but a static
 * import in {@code .java} source becomes a hard {@code Class.forName} at link.
 *
 * <p>The fix is to <em>probe</em> the class through reflection at startup and
 * cache the answer here. The codegen then asks {@link #AVAILABLE} before emitting
 * any bytecode that would touch {@code jdk/incubator/vector/*} internal names.
 * When unavailable, the scalar {@code fillArray} path stays in effect and worldgen
 * remains correct (just non-SIMD).
 *
 * <h2>Availability</h2>
 *
 * <p>We probe {@code jdk.incubator.vector} once at class init. With a default
 * launcher (no {@code --add-modules}) the module is absent and {@link #AVAILABLE}
 * stays {@code false}. Use run config {@code clientVector} (adds the module) to
 * enable SIMD codegen in dev.
 *
 * <h2>Codegen contract</h2>
 *
 * <p>When {@link #AVAILABLE} is {@code true}, the codegen may emit bytecode that:
 *
 * <ul>
 *   <li>References {@code jdk/incubator/vector/DoubleVector} as an internal
 *       class name in {@code INVOKESTATIC} / field load / type cast instructions.</li>
 *   <li>Loads {@link #PREFERRED_LANES} as a baked LDC int — the count is
 *       fixed for the lifetime of the JVM (the species is a static singleton).</li>
 *   <li>Uses {@code DoubleVector.SPECIES_PREFERRED} via the {@link MethodHandle}
 *       returned by {@link #speciesHandle()} when materialising a species
 *       reference at runtime.</li>
 * </ul>
 *
 * <p>When {@link #AVAILABLE} is {@code false}, the codegen <strong>must not</strong>
 * emit any of the above. The bytecode would link successfully but the first
 * call site would throw {@link NoClassDefFoundError} on resolution.
 *
 * <h2>Lane count rationale</h2>
 *
 * <p>Most modern x86_64 CPUs report {@code SPECIES_PREFERRED.length() == 4}
 * (AVX2: 256-bit / 64-bit-double = 4 lanes); AVX-512 boxes report 8.
 * NoiseChunk's {@code cellWidth^2} XZ slab is 16 doubles for {@code cellWidth=4},
 * so 4-lane gives us 4 vector ops per slab (good fit) and 8-lane gives us 2.
 * The codegen uses {@link #PREFERRED_LANES} at emit time to pick the loop
 * unroll factor; the same hidden class works on every CPU because the lane
 * count is JVM-wide.
 */
public final class DfcVectorSupport {

    /**
     * Internal name of {@code jdk.incubator.vector.DoubleVector}, used by the
     * codegen when emitting SIMD bytecode. Hardcoded as a string constant so
     * referencing it does not trigger class loading on a non-vector launcher.
     */
    public static final String DOUBLE_VECTOR_INTERNAL = "jdk/incubator/vector/DoubleVector";

    /**
     * Internal name of {@code jdk.incubator.vector.VectorSpecies}, the species
     * descriptor parameterised on the lane element type.
     */
    public static final String VECTOR_SPECIES_INTERNAL = "jdk/incubator/vector/VectorSpecies";

    /**
     * {@code true} when the {@code jdk.incubator.vector} module is loadable.
     * Read once at class init; never changes for the lifetime of the JVM.
     */
    public static final boolean AVAILABLE;

    /**
     * Preferred SIMD lane count for {@code double} on this JVM, typically 4
     * (AVX2) or 8 (AVX-512). {@code 0} when {@link #AVAILABLE} is {@code false} —
     * any caller that reads this without first checking {@link #AVAILABLE} is
     * a bug.
     */
    public static final int PREFERRED_LANES;

    /**
     * MethodHandle bound to {@code DoubleVector.SPECIES_PREFERRED}'s {@code length()}
     * method, kept as a sample handle the codegen can use to verify capability
     * during emission. {@code null} when {@link #AVAILABLE} is {@code false}.
     */
    private static final MethodHandle SPECIES_HANDLE;

    static {
        boolean available = false;
        int lanes = 0;
        MethodHandle species = null;

        try {
            Class<?> doubleVector = Class.forName(
                    "jdk.incubator.vector.DoubleVector", false,
                    DfcVectorSupport.class.getClassLoader());
            Class<?> vectorSpecies = Class.forName(
                    "jdk.incubator.vector.VectorSpecies", false,
                    DfcVectorSupport.class.getClassLoader());

            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            MethodHandle speciesGetter = lookup.findStaticGetter(
                    doubleVector, "SPECIES_PREFERRED", vectorSpecies);
            Object speciesInstance = speciesGetter.invoke();

            MethodHandle lengthMH = lookup.findVirtual(
                    vectorSpecies, "length", MethodType.methodType(int.class));
            lanes = (int) lengthMH.invoke(speciesInstance);

            if (lanes > 0) {
                available = true;
                species = speciesGetter;
            }
        } catch (ClassNotFoundException ignored) {
            // Default JVM: incubator vector not on module path — stay scalar, no warning.
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.warn(
                    "DFC vector: probe failed unexpectedly ({}); falling back to scalar.",
                    t.toString());
        }

        AVAILABLE = available;
        PREFERRED_LANES = lanes;
        SPECIES_HANDLE = species;
    }

    private DfcVectorSupport() {}

    /**
     * Emit a one-line server-startup banner reporting the probed state.
     * Called from {@link DensityFunctionCompiler} mod init.
     */
    public static void logStatusOnce() {
        if (AVAILABLE) {
            DensityFunctionCompiler.LOGGER.info(
                    "DFC vector: enabled (preferred {} lanes per double-vector op)",
                    PREFERRED_LANES);
        } else {
            DensityFunctionCompiler.LOGGER.info(
                    "DFC vector: disabled (jdk.incubator.vector not on module path; use --add-modules jdk.incubator.vector to enable)");
        }
    }

    /**
     * MethodHandle of type {@code () -> VectorSpecies} that returns
     * {@code DoubleVector.SPECIES_PREFERRED}. {@code null} when
     * {@link #AVAILABLE} is {@code false}; intended for diagnostic / test use
     * (the codegen builds its own handles per generated class through
     * {@link MethodHandles.Lookup#findStaticGetter} on the local class loader).
     */
    public static MethodHandle speciesHandle() {
        return SPECIES_HANDLE;
    }
}

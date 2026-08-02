package dev.sixik.generator_accelerator.common.density.compiler.compiler.vector;

import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;

/**
 * JDK incubator Vector API probe + capability advertiser.
 *
 * <p>The Vector API is optional and must not be linked from normal launchers.
 * This class probes it reflectively only when explicitly requested through
 * {@code dfc.codegen.vectorApi=auto|force}. The default {@code off} mode keeps
 * generated DFC bytecode scalar and avoids accidental module linkage.
 */
public final class DfcVectorSupport {
    public static final String DOUBLE_VECTOR_INTERNAL = "jdk/incubator/vector/DoubleVector";
    public static final String VECTOR_SPECIES_INTERNAL = "jdk/incubator/vector/VectorSpecies";

    /** {@code off}, {@code auto}, or {@code force}. */
    public static final String MODE;

    /** True only when vector mode is not off and the module is loadable. */
    public static final boolean AVAILABLE;

    /** Preferred double lane count; 0 when unavailable. */
    public static final int PREFERRED_LANES;

    private static final MethodHandle SPECIES_HANDLE;

    static {
        MODE = parseMode(System.getProperty("dfc.codegen.vectorApi", "off"));

        boolean available = false;
        int lanes = 0;
        MethodHandle species = null;

        if (!"off".equals(MODE)) {
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
                // Default launcher without --add-modules jdk.incubator.vector.
            } catch (Throwable t) {
                DensityFunctionCompiler.LOGGER.warn(
                        "DFC vector: probe failed unexpectedly ({}); falling back to scalar.",
                        t.toString());
            }
        }

        if ("force".equals(MODE) && !available) {
            DensityFunctionCompiler.LOGGER.warn(
                    "DFC vector: mode=force requested, but jdk.incubator.vector is unavailable; falling back to scalar.");
        }

        AVAILABLE = available;
        PREFERRED_LANES = lanes;
        SPECIES_HANDLE = species;
    }

    private DfcVectorSupport() {
    }

    private static String parseMode(String raw) {
        String mode = raw == null ? "off" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "off", "auto", "force" -> mode;
            default -> {
                DensityFunctionCompiler.LOGGER.warn(
                        "DFC vector: unknown dfc.codegen.vectorApi={}; using off.", raw);
                yield "off";
            }
        };
    }

    public static void logStatusOnce() {
        if (AVAILABLE) {
            DensityFunctionCompiler.LOGGER.info(
                    "DFC vector: mode={}, enabled (preferred {} lanes per double-vector op)",
                    MODE, PREFERRED_LANES);
        } else {
            DensityFunctionCompiler.LOGGER.info(
                    "DFC vector: mode={}, disabled (set dfc.codegen.vectorApi=auto|force and add --add-modules jdk.incubator.vector to enable)",
                    MODE);
        }
    }

    public static MethodHandle speciesHandle() {
        return SPECIES_HANDLE;
    }
}

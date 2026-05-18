package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;

/**
 * Runtime switches for the experimental DFC OpenCL backend.
 *
 * <p>The backend stays opt-in: if it is disabled we do not touch LWJGL OpenCL at all,
 * keeping the current CPU/JNI DFC path as the default production path.
 */
public final class DfcOpenClConfig {
    private DfcOpenClConfig() {
    }

    public static boolean enabled() {
        GAConfig config = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
        return boolProperty("dfc.opencl.enabled", config.enableDensityCompilerOpenCL);
    }

    public static boolean probeOnInit() {
        return boolProperty("dfc.opencl.probeOnInit", enabled());
    }

    public static boolean allowCpuDevices() {
        return boolProperty("dfc.opencl.allowCpuDevices", false);
    }

    public static boolean allowGpuDevices() {
        return boolProperty("dfc.opencl.allowGpuDevices", true);
    }

    public static boolean allowAcceleratorDevices() {
        return boolProperty("dfc.opencl.allowAcceleratorDevices", true);
    }

    public static boolean requireFp64() {
        return boolProperty("dfc.opencl.requireFp64", true);
    }

    public static boolean compileSmokeTestOnProbe() {
        return boolProperty("dfc.opencl.compileSmokeTestOnProbe", true);
    }

    public static String deviceFilter() {
        return System.getProperty("dfc.opencl.deviceFilter", "").trim();
    }

    public static int maxLoggedDevices() {
        return intProperty("dfc.opencl.maxLoggedDevices", 4, 0, 32);
    }

    private static boolean boolProperty(String property, boolean fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int intProperty(String property, int fallback, int min, int max) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min) {
                return min;
            }
            if (parsed > max) {
                return max;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

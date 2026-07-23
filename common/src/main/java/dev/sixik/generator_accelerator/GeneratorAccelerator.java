package dev.sixik.generator_accelerator;

import dev.sixik.generator_accelerator.api.config.GAConfigHolder;
import dev.sixik.generator_accelerator.common.density.compiler.DfcConfigBridge;
import net.sixik.ga_utils.javatogpu.api.GPU;
import net.sixik.ga_utils.javatogpu.api.GpuScope;
import net.sixik.ga_utils.javatogpu.api.JavaToGpu;
import net.sixik.ga_utils.javatogpu.api.annotations.GPUGlobal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;

public final class GeneratorAccelerator {
    private static final String LOGGER_NAME = "Generator Accelerator";
    public static final String MOD_ID = "generator_accelerator";
    public static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    private static Platform platform = null;
    private static Path gameFolder;
    private static boolean devMode;
    private static final boolean useProfiler = false;

    public static void init(Platform platform, boolean isDev, Path gameFolder) {
        GeneratorAccelerator.platform = platform;
        GeneratorAccelerator.gameFolder = gameFolder;
        GeneratorAccelerator.devMode = isDev;
        DfcConfigBridge.applySystemPropertiesFromConfig();

        float[] a = new float[] {1.0f, 2.0f, 3.0f, 4.0f};
        float[] b = new float[] {10.0f, 20.0f, 30.0f, 40.0f};
        float[] out = new float[a.length];

        try (GpuScope ignored = JavaToGpu.useOpenClSharedCache()) {
            TestGpu.add(a, b, out);
        } finally {
            JavaToGpu.shutdownOpenClSharedCache();
        }

        LOGGER.info("Invoke GPU CODE: {}", Arrays.toString(out));
    }

    public static boolean isUseProfiler() {
        return useProfiler;
    }

    public static Path getGameFolder() {
        return gameFolder;
    }

    public static Platform getPlatform() {
        return platform;
    }

    public static boolean isDevMode() {
        return devMode;
    }

    public enum Platform {
        FABRIC,
        FORGE,
        NEOFORGE
    }

    private static class TestGpu {

        @net.sixik.ga_utils.javatogpu.api.annotations.GPU
        public static void add(
                @GPUGlobal float[] a,
                @GPUGlobal float[] b,
                @GPUGlobal float[] out
        ) {
            int id = GPU.get_global_id(0);
            out[id] = a[id] + b[id];
        }
    }
}

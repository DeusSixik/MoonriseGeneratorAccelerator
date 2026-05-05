package dev.sixik.generator_accelerator.common.density.compiler;

import com.mojang.logging.LogUtils;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RegistryWarmer;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.vector.DfcVectorSupport;
import dev.sixik.generator_accelerator.common.density.compiler.natives.DfcNativeBridge;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.lang.reflect.Method;

public final class DensityFunctionCompiler {
    public static final String MODID = "generator_accelerator";
    public static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Debug switch: when enabled, every generated DensityFunction class is written
     * under {@code .densitycompiler/} in the current game directory.
     *
     * <p>Can be toggled directly at runtime, or initialized with
     * {@code -Ddfc.dump_classes=true}.
     */
    public static volatile boolean dumpCompiledClasses = Boolean.getBoolean("dfc.dump_classes");

    private static volatile boolean initialized;

    private DensityFunctionCompiler() {}

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        LOGGER.info("DensityFunctionCompiler initialising - runtime DF JIT pipeline enabling.");
        if (dumpCompiledClasses) {
            LOGGER.info("DFC class dump enabled; generated classes will be written to .densitycompiler");
        }
        DfcVectorSupport.logStatusOnce();
        LOGGER.info("DFC native noise: libraryLoaded={}, avx2={}",
                DfcNativeBridge.isAvailable(), DfcNativeBridge.hasAvx2());
        if (!DfcNativeBridge.isAvailable()) {
            Throwable err = DfcNativeBridge.nativeLoadError();
            if (err != null) {
                LOGGER.warn("DFC native noise: not loaded ({})", err.getMessage());
            } else {
                LOGGER.warn("DFC native noise: not loaded (unknown reason). Put natives/dfc/prebuilts/<platform>/... "
                        + "or set env DFC_NATIVE_LIBRARY to the absolute path of dfc_native.dll / .so / .dylib.");
            }
        }
    }

    public static void onServerStarting(MinecraftServer server) {
        RegistryWarmer.warmAll(server);
    }

    public static void onServerStarted(MinecraftServer server) {
    }

    public static void onDatapackReload(MinecraftServer server) {
        RegistryWarmer.warmAll(server);
    }

    public static boolean isModLoaded(String modId) {
        try {
            Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = fabricLoader.getMethod("getInstance").invoke(null);
            return (boolean) fabricLoader.getMethod("isModLoaded", String.class).invoke(instance, modId);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> modList = Class.forName("net.neoforged.fml.ModList");
            Object instance = modList.getMethod("get").invoke(null);
            Method isLoaded = modList.getMethod("isLoaded", String.class);
            return (boolean) isLoaded.invoke(instance, modId);
        } catch (Throwable ignored) {
            return false;
        }
    }
}

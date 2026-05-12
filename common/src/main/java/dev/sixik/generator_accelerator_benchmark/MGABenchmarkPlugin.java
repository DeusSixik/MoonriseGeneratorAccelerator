package dev.sixik.generator_accelerator_benchmark;

import com.mojang.logging.LogUtils;
import dev.sixik.generator_accelerator.GeneratorAccelerator;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class MGABenchmarkPlugin implements IMixinConfigPlugin {

    public static final Logger LOGGER = LogUtils.getLogger();

    private boolean isDev;
    private boolean isServer;

    @Override
    public void onLoad(String mixinPackage) {
        this.isDev = Boolean.getBoolean("fabric.development") || Boolean.getBoolean("fml.deobfuscatedEnvironment");
        this.isServer = checkIsServer();

        LOGGER.info("MGABenchmarkPlugin | Is Developer Environment: {}", isDev);
        LOGGER.info("MGABenchmarkPlugin | Is Server Environment: {}", isServer);

        if (this.isServer) {
            try {
                GeneratorAccelerator.tryLoadNatives();
            } catch (RuntimeException failure) {
                LOGGER.warn("MGABenchmarkPlugin | Optional C3 natives unavailable; benchmark mixins stay enabled.", failure);
            }
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return isDev && isServer;
    }

    private boolean checkIsServer() {
        try {
            Class<?> fabricLoaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loaderInstance = fabricLoaderClass.getMethod("getInstance").invoke(null);
            Object envType = loaderInstance.getClass().getMethod("getEnvironmentType").invoke(loaderInstance);
            return "SERVER".equals(envType.toString());
        } catch (Throwable ignored) {}

        try {
            Class<?> fmlEnvClass = Class.forName("net.minecraftforge.fml.loading.FMLEnvironment");
            Object dist = fmlEnvClass.getField("dist").get(null);
            return "DEDICATED_SERVER".equals(dist.toString());
        } catch (Throwable ignored) {}

        try {
            Class<?> neoEnvClass = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
            Object dist = neoEnvClass.getField("dist").get(null);
            return "DEDICATED_SERVER".equals(dist.toString());
        } catch (Throwable ignored) {}

        String fabricSide = System.getProperty("fabric.side");
        if (fabricSide != null) {
            return "server".equalsIgnoreCase(fabricSide);
        }

        String dliEnv = System.getProperty("fabric.dli.env");
        if (dliEnv != null) {
            return "server".equalsIgnoreCase(dliEnv);
        }

        return false;
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}

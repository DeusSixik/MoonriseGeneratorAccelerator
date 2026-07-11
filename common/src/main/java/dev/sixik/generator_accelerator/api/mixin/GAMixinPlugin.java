package dev.sixik.generator_accelerator.api.mixin;

import com.mojang.logging.LogUtils;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class GAMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("GeneratorAccelerator Mixin");

    public static final List<MixinApplier> MixinAppliers = new CopyOnWriteArrayList<>();

    public void create(String modClass, MixinApplier.Param... params) {
        if (!isMixinSetEnabled()) {
            return;
        }
        MixinAppliers.add(new MixinApplier(modClass, params));
    }

    public void createAll(String[] modClasses, MixinApplier.Param... params) {
        if (!isMixinSetEnabled()) {
            return;
        }
        MixinAppliers.add(new MixinApplier(String.join(";", modClasses), params));
    }

    private boolean isMixinSetEnabled() {
        return GAConfigManager.getConfigOrLoad().map(this::isConfigEnable).orElse(false);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {

        final var config = GAConfigManager.getConfigOrLoad();
        if(config.isEmpty() || !isConfigEnable(config.get())) {
            LOGGER.info("Mixin {} is disabled by config", mixinClassName);
            return false;
        }

        for (MixinApplier mixinApplier : MixinAppliers) {
            if(mixinApplier.hasMixin(mixinClassName) && !mixinApplier.isModLoaded()) {
                LOGGER.info("Disable mixin: {}", mixinClassName);
                return false;
            }
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> set, Set<String> set1) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }

    @Override
    public void postApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }

    public abstract boolean isConfigEnable(GAConfig config);
}

package dev.sixik.generator_accelerator.common.chunks.compats.modernfix;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GAModernFixMixinPlugin extends GAMixinPlugin {
    private static final String MODERNFIX_ENTRYPOINT = "org.embeddedt.modernfix.ModernFix";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return isModernFixLoaded() && super.shouldApplyMixin(targetClassName, mixinClassName);
    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return true;
    }

    @Override
    public void onLoad(String s) {

    }

    private static boolean isModernFixLoaded() {
        try {
            Class.forName(MODERNFIX_ENTRYPOINT, false, GAModernFixMixinPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }
}

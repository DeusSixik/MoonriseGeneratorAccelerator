package dev.sixik.generator_accelerator.common.features;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GAFeaturesMixinPlugin extends GAMixinPlugin {

    @Override
    public void onLoad(String s) {

    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableFeaturesPatch;
    }

    private boolean isLoaded(String modClassPath) {
        if(modClassPath.isEmpty()) return true;

        try {
            Class.forName(modClassPath, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }
}

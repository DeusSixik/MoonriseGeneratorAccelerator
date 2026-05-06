package dev.sixik.generator_accelerator.api.mixin;


import com.bawnorton.mixinsquared.api.MixinCanceller;

import java.util.List;
import java.util.Set;

public class GAMixinCanceller implements MixinCanceller {

    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        String normalizedMixinName = normalizeMixinName(mixinClassName);

        for (MixinApplier mixinApplier : GAMixinPlugin.MixinAppliers) {
            if(mixinApplier.hasDisableMixin(normalizedMixinName) && mixinApplier.isModLoaded())
                return true;
        }

        return false;
    }

    private static String normalizeMixinName(String mixinClassName) {
        return mixinClassName.indexOf('/') >= 0 ? mixinClassName.replace('/', '.') : mixinClassName;
    }
}

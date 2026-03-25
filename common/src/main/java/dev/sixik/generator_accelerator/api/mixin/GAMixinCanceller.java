package dev.sixik.generator_accelerator.api.mixin;


import com.bawnorton.mixinsquared.api.MixinCanceller;

import java.util.List;

public class GAMixinCanceller implements MixinCanceller {

    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        for (MixinApplier mixinApplier : GAMixinPlugin.MixinAppliers) {
            if(mixinApplier.hasDisableMixin(mixinClassName) && mixinApplier.isModLoaded())
                return true;
        }

        return false;
    }
}

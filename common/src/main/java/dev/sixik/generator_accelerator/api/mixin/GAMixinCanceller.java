package dev.sixik.generator_accelerator.api.mixin;


import com.bawnorton.mixinsquared.api.MixinCanceller;

import java.util.List;
import java.util.Set;

public class GAMixinCanceller implements MixinCanceller {
    private static final String MODERNFIX_ENTRYPOINT = "org.embeddedt.modernfix.ModernFix";
    private static final Set<String> MODERNFIX_MIXINS_TO_CANCEL = Set.of(
            "bugfix.chunk_deadlock.ChunkMapLoadMixin",
            "perf.release_protochunks.ChunkMapMixin",
            "perf.release_protochunks.ChunkHolderMixin"
    );

    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        String normalizedMixinName = normalizeMixinName(mixinClassName);

        if (shouldCancelModernFixMixin(normalizedMixinName)) {
            return true;
        }

        for (MixinApplier mixinApplier : GAMixinPlugin.MixinAppliers) {
            if(mixinApplier.hasDisableMixin(normalizedMixinName) && mixinApplier.isModLoaded())
                return true;
        }

        return false;
    }

    private static boolean shouldCancelModernFixMixin(String mixinClassName) {
        return isModernFixMixinToCancel(mixinClassName) && isModernFixLoaded();
    }

    private static boolean isModernFixMixinToCancel(String mixinClassName) {
        for (String mixinToCancel : MODERNFIX_MIXINS_TO_CANCEL) {
            if (mixinClassName.equals(mixinToCancel)
                    || mixinClassName.endsWith("." + mixinToCancel)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isModernFixLoaded() {
        try {
            Class.forName(MODERNFIX_ENTRYPOINT, false, GAMixinCanceller.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }

    private static String normalizeMixinName(String mixinClassName) {
        return mixinClassName.indexOf('/') >= 0 ? mixinClassName.replace('/', '.') : mixinClassName;
    }
}

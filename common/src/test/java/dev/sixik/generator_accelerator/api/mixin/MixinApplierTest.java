package dev.sixik.generator_accelerator.api.mixin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinApplierTest {

    @Test
    void combinedModRequirementRequiresEveryClass() {
        assertTrue(new MixinApplier(
                "java.lang.String;java.lang.Integer",
                new MixinApplier.Param[0]
        ).isModLoaded());

        assertFalse(new MixinApplier(
                "java.lang.String;dev.sixik.generator_accelerator.DoesNotExist",
                new MixinApplier.Param[0]
        ).isModLoaded());
    }

    @Test
    void disableMixinMatchesExactName() {
        MixinApplier applier = new MixinApplier(
                "",
                new MixinApplier.Param[]{
                        new MixinApplier.Param("", "external.ProblemMixin")
                }
        );

        assertTrue(applier.hasDisableMixin("external.ProblemMixin"));
        assertFalse(applier.hasDisableMixin("external.OtherMixin"));
    }

    @Test
    void disableMixinMatchesPackagePrefix() {
        MixinApplier applier = new MixinApplier(
                "",
                new MixinApplier.Param[]{
                        new MixinApplier.Param("", "external.chunk_system.mixin.*")
                }
        );

        assertTrue(applier.hasDisableMixin("external.chunk_system.mixin.MixinChunkMap"));
        assertTrue(applier.hasDisableMixin("external.chunk_system.mixin.async.MixinSerializer"));
        assertFalse(applier.hasDisableMixin("external.chunk_system.other.MixinChunkMap"));
    }

    @Test
    void linkageFailureDoesNotEnableCompatibilityMixins() {
        ClassLoader brokenLoader = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("broken.OptionalMod")) {
                    throw new NoClassDefFoundError("broken dependency");
                }
                return super.loadClass(name, resolve);
            }
        };

        assertFalse(MixinApplier.isClassLoaded("broken.OptionalMod", brokenLoader));
    }

    @Test
    void disabledPluginDoesNotCancelExternalMixins() {
        GAMixinPlugin.MixinAppliers.clear();
        try {
            GAMixinPlugin plugin = plugin(false);
            plugin.create("java.lang.String", new MixinApplier.Param("", "external.ProblemMixin"));

            assertTrue(GAMixinPlugin.MixinAppliers.isEmpty());
        } finally {
            GAMixinPlugin.MixinAppliers.clear();
        }
    }

    @Test
    void enabledPluginCanCancelExternalMixins() {
        GAMixinPlugin.MixinAppliers.clear();
        try {
            GAMixinPlugin plugin = plugin(true);
            plugin.create("java.lang.String", new MixinApplier.Param("", "external.ProblemMixin"));

            assertTrue(GAMixinPlugin.MixinAppliers.stream()
                    .anyMatch(applier -> applier.hasDisableMixin("external.ProblemMixin")));
        } finally {
            GAMixinPlugin.MixinAppliers.clear();
        }
    }

    private static GAMixinPlugin plugin(boolean enabled) {
        return new GAMixinPlugin() {
            @Override
            public void onLoad(String mixinPackage) {
            }

            @Override
            public boolean isConfigEnable(dev.sixik.generator_accelerator.config.GAConfig config) {
                return enabled;
            }
        };
    }
}

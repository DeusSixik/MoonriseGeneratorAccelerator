package dev.sixik.generator_accelerator.mixins;

import dev.sixik.generator_accelerator.config.GAConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GACoreMixinPluginTest {

    @AfterEach
    void clearOverrides() {
        System.clearProperty("ga.chunkGraph.enabled");
    }

    @Test
    void gaOwnsChunkSystemWhenCustomGraphIsEnabled() {
        GAConfig config = new GAConfig();
        assertTrue(GACoreMixinPlugin.shouldOwnC2meChunkSystem(config));

        config.enableCustomChunkGraphScheduler = false;
        assertFalse(GACoreMixinPlugin.shouldOwnC2meChunkSystem(config));

        config.enableCustomChunkGraphScheduler = true;
        System.setProperty("ga.chunkGraph.enabled", "false");
        assertFalse(GACoreMixinPlugin.shouldOwnC2meChunkSystem(config));
    }

    @Test
    void completeC2meChunkSystemPackageIsCancelled() {
        assertTrue(GACoreMixinPlugin.C2ME_CHUNK_SYSTEM_MIXIN_PATTERN.endsWith(".*"));
        assertTrue("com.ishland.c2me.rewrites.chunksystem.mixin.MixinThreadedAnvilChunkStorage"
                .startsWith(GACoreMixinPlugin.C2ME_CHUNK_SYSTEM_MIXIN_PATTERN.substring(
                        0,
                        GACoreMixinPlugin.C2ME_CHUNK_SYSTEM_MIXIN_PATTERN.length() - 1
                )));
    }
}

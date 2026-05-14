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
}

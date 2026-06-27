package dev.sixik.generator_accelerator.api.config;

import ca.spottedleaf.yamlconfig.annotation.Adaptable;
import ca.spottedleaf.yamlconfig.annotation.Serializable;

@Adaptable
public final class GAConfig {

    @Serializable(
            comment = """
                    Do not change, used internally.
                    """
    )
    public int version = 1;

    // @generatedMixinConfigFields:start

    @Serializable(
            comment = """
                    Example toggle generated from @MixinOnConfig.
                    Enable it to allow ExampleGeneratedToggleMixin to load.
                    """
    )
    public boolean enableBasicExampleMixinTest = false;

    @Serializable(
            comment = """
                    Enables the Density Function Compiler mixin module.
                    Also disables conflicting C2ME DFC mixins when their compat classes are present.
                    """
    )
    public boolean enableDensityCompilerPatch = true;

    @Serializable(
            comment = """
                    Generated mixin toggle for dev.sixik.generator_accelerator.common.basic.mixin.ExampleCompatMixin.
                    """
    )
    public boolean enableExampleCompatMixin = true;

    // @generatedMixinConfigFields:end
}

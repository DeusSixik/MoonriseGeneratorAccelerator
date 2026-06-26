package dev.sixik.generator_accelerator.api.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin} class for build-time discovery.
 *
 * <p>The Gradle generator uses this annotation to locate the plugin class that should be written into
 * the matching {@code *.mixins.json} file. When {@link #value()} is empty, the token is inferred from
 * the plugin package, for example {@code dev.example.common.basic.BasicGAMixinPlugin -> basic}.</p>
 *
 * <pre>{@code
 * @AutoMixinPlugin
 * public final class BasicGAMixinPlugin extends GAMixinPlugin {
 *     @Override
 *     public boolean isConfigEnable(GAConfig config) {
 *         return config.enableBasicPatches;
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface AutoMixinPlugin {

    /**
     * Optional explicit token used to match a plugin to a mixin package/resource group.
     */
    String value() default "";
}

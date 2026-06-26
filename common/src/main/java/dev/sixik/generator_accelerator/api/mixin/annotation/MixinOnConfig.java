package dev.sixik.generator_accelerator.api.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a GA config toggle for a mixin class and automatically wires it into the owning plugin.
 *
 * <p>If the named field does not already exist in {@code GAConfig}, the build generates it inside the
 * managed config section. The generated bootstrap then calls
 * {@code addMixinToConfig("full.mixin.Class", config -> config.fieldName)} automatically.</p>
 *
 * <pre>{@code
 * @MixinOnConfig(
 *         name = "enableExampleCompatMixin",
 *         defaultValue = true,
 *         comment = "Enables the example compat mixin."
 * )
 * public abstract class ExampleCompatMixin {
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface MixinOnConfig {

    /**
     * Java field name to generate or bind inside {@code GAConfig}.
     */
    String name();

    /**
     * Default value used when the field is generated.
     */
    boolean defaultValue() default true;

    /**
     * Optional YAML comment written above the generated config field.
     */
    String comment() default "";
}

package dev.sixik.generator_accelerator.api.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a module package for automatic mixin config generation.
 *
 * <p>When this annotation is placed on a {@code package-info.java} file, the Gradle generator
 * creates a {@code <token>.generator_accelerator.mixins.json} file for that package. The token is
 * inferred from the package name by default, for example
 * {@code dev.example.common.basic -> basic}.</p>
 *
 * <p>The generated config scans the sibling {@code mixin} package recursively and registers every
 * class annotated with {@code @Mixin}, except those marked with
 * {@link DissableMixinRegister}.</p>
 *
 * <pre>{@code
 * @AutomaticMixin
 * package dev.example.common.basic;
 * }
 * </pre>
 */
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.SOURCE)
public @interface AutomaticMixin {

    /**
     * Optional explicit token used for the generated file name and plugin lookup.
     */
    String value() default "";
}

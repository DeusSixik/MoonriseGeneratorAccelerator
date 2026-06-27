package dev.sixik.generator_accelerator.api.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Convenience annotation that adds multiple foreign mixins to a compat disable list.
 *
 * <pre>{@code
 * @DisableMixins({
 *         "com.example.mixin.One",
 *         "com.example.mixin.Two"
 * )
 *
 * @DisableMixins(valueByClass = {
 *         SomeForeignMixin.class,
 *         AnotherForeignMixin.class
 * )
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface DisableMixins {

    /**
     * Fully qualified foreign mixin class names to cancel.
     */
    String[] value() default {};

    /**
     * Foreign mixin classes on the compile classpath to cancel.
     */
    Class<?>[] valueByClass() default {};
}

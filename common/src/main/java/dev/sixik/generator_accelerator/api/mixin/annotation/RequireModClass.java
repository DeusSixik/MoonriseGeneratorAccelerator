package dev.sixik.generator_accelerator.api.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Convenience annotation that declares one required class for a compat mixin.
 *
 * <p>This is a shorthand alternative to {@link CompatMixin#mod()}.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(RequireModClasses.class)
public @interface RequireModClass {

    /**
     * Required class on the compile classpath.
     */
    Class<?> value();
}

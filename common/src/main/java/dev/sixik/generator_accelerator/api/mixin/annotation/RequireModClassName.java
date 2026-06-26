package dev.sixik.generator_accelerator.api.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Convenience annotation that declares one required class name for a compat mixin.
 *
 * <p>Use this when the target mod is not on the compile classpath and only a string literal can be
 * referenced.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(RequireModClassNames.class)
public @interface RequireModClassName {

    /**
     * Fully qualified required class name.
     */
    String value();
}

package dev.sixik.generator_accelerator.api.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation used by {@link java.lang.annotation.Repeatable} for {@link RequireModClassName}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface RequireModClassNames {

    /**
     * Repeated {@link RequireModClassName} annotations.
     */
    RequireModClassName[] value();
}

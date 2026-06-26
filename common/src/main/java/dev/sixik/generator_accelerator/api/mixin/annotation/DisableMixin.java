package dev.sixik.generator_accelerator.api.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Convenience annotation that adds a single foreign mixin to a compat disable list.
 *
 * <p>This is functionally equivalent to using {@link DisableMixins} with one entry.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(DisableMixinsList.class)
public @interface DisableMixin {

    /**
     * Fully qualified foreign mixin class name to cancel while the owning compat mixin is active.
     */
    String value();
}

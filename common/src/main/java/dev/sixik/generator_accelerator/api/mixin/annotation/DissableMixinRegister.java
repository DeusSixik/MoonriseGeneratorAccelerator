package dev.sixik.generator_accelerator.api.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes a mixin class from {@link AutomaticMixin} config generation.
 *
 * <p>Use this on mixin classes that live inside an automatically scanned {@code .mixin} package
 * but should not be written into the generated {@code *.generator_accelerator.mixins.json} file.</p>
 *
 * <pre>{@code
 * @Mixin(SomeTarget.class)
 * @DissableMixinRegister
 * public abstract class DebugOnlyMixin {
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface DissableMixinRegister {
}

package dev.sixik.generator_accelerator.api.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a compat mixin that becomes active only when one or more mod classes are present.
 *
 * <p>When the compat mixin is active, any mixins listed in {@link #disable()} are cancelled through
 * the generated {@code MixinCanceller}. When the compat condition is not satisfied, the compat mixin
 * itself is disabled and the {@code disable()} list is left untouched.</p>
 *
 * <pre>{@code
 * @Mixin(SomeTarget.class)
 * @CompatMixin(
 *         mod = SomeModMainClass.class,
 *         disable = {
 *                 "com.example.foreign.mixin.TargetMixin"
 *         }
 * )
 * @MixinOnConfig(name = "enableSomeCompat")
 * public abstract class SomeCompatMixin {
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface CompatMixin {

    /**
     * Controls how multiple required classes are evaluated.
     */
    MatchMode mode() default MatchMode.ALL;

    /**
     * Single required class on the compile classpath.
     */
    Class<?> mod() default void.class;

    /**
     * Single required class name when the target mod is not on the compile classpath.
     */
    String modClassName() default "";

    /**
     * Multiple required classes on the compile classpath.
     */
    Class<?>[] mods() default {};

    /**
     * Multiple required class names when target mods are not on the compile classpath.
     */
    String[] modClassNames() default {};

    /**
     * Foreign mixin class names that should be cancelled while this compat mixin is active.
     */
    String[] disable() default {};

    /**
     * Match mode for {@link #mods()} and {@link #modClassNames()}.
     */
    enum MatchMode {
        /** Every listed class must be available. */
        ALL,
        /** At least one listed class must be available. */
        ANY
    }
}

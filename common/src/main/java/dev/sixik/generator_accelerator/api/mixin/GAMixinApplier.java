package dev.sixik.generator_accelerator.api.mixin;

/**
 * Describes a runtime condition for one or more GA mixins.
 *
 * <p>An applier binds one or more GA mixin classes to a required mod class path.
 * If the class path cannot be resolved at runtime, the GA mixin is not applied.
 * The same rule can also advertise foreign mixins that should be cancelled when
 * the GA compat mixin is active.</p>
 *
 * <p>Instances are usually created through {@link GAMixinPlugin#create(String, Param...)},
 * {@link GAMixinPlugin#createAll(String[], Param...)}, or
 * {@link GAMixinPlugin#createAny(String[], Param...)} rather than instantiated manually.</p>
 */
public record GAMixinApplier(String modClassPath, MatchMode matchMode, Param[] mixins) {

    /**
     * Creates an applier that requires a single class path or an {@link MatchMode#ALL} match
     * for a semicolon-separated class list.
     *
     * @param modClassPath fully qualified class name, or multiple class names separated by {@code ;}
     * @param mixins mixin rules associated with that class requirement
     */
    public GAMixinApplier(String modClassPath, Param[] mixins) {
        this(modClassPath, MatchMode.ALL, mixins);
    }

    /**
     * Returns {@code true} if this rule lists the supplied foreign mixin inside any
     * {@link Param#mixinDisable()} entry.
     *
     * @param mixin fully qualified foreign mixin class name
     * @return {@code true} when the foreign mixin is present in the disable list
     */
    public boolean hasDisableMixin(String mixin) {
        for(Param param : this.mixins) {
            for(String s : param.mixinDisable) {
                if (s.equals(mixin)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Finds the compat mixin rule that disables the supplied foreign mixin.
     *
     * @param mixin fully qualified foreign mixin class name
     * @return the owning GA compat rule, or {@code null} if no rule disables the mixin
     */
    public Param findDisableMixin(String mixin) {
        for (Param param : this.mixins) {
            for (String s : param.mixinDisable) {
                if (s.equals(mixin)) {
                    return param;
                }
            }
        }

        return null;
    }

    /**
     * Returns {@code true} if this applier directly owns the supplied GA mixin class.
     *
     * @param mixin fully qualified GA mixin class name
     * @return {@code true} when the mixin belongs to this applier
     */
    public boolean hasMixin(String mixin) {
        for (Param param : mixins) {
            if(param.mixinClass.equals(mixin))
                return true;
        }

        return false;
    }

    /**
     * Evaluates whether this applier's required class path is currently available.
     *
     * <p>For a single class path the check is direct. For multiple class paths separated by
     * {@code ;}, the result depends on {@link #matchMode()}:</p>
     *
     * <ul>
     *   <li>{@link MatchMode#ALL}: every class must be present</li>
     *   <li>{@link MatchMode#ANY}: at least one class must be present</li>
     * </ul>
     *
     * @return {@code true} if the required class condition is satisfied
     */
    public boolean isModLoaded() {
        if(modClassPath.isEmpty()) return true;

        if (modClassPath.indexOf(';') >= 0) {
            if (matchMode == MatchMode.ANY) {
                for (String modClass : modClassPath.split(";")) {
                    if (isClassLoaded(modClass)) {
                        return true;
                    }
                }
                return false;
            }

            for (String modClass : modClassPath.split(";")) {
                if (!isClassLoaded(modClass)) {
                    return false;
                }
            }
            return true;
        }

        return isClassLoaded(modClassPath);
    }

    /**
     * Tries to resolve a class without forcing full initialization.
     */
    private static boolean isClassLoaded(String modClassPath) {
        if (modClassPath.isEmpty()) return true;

        try {
            Class.forName(modClassPath, false, GAMixinApplier.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }

    public enum MatchMode {
        /** Require every class path listed in {@link #modClassPath()}. */
        ALL,
        /** Require at least one class path listed in {@link #modClassPath()}. */
        ANY
    }

    /**
     * Describes a single GA mixin and the foreign mixins it may cancel.
     *
     * @param mixinClass fully qualified GA mixin class name
     * @param mixinDisable foreign mixin class names that should be cancelled when this GA mixin is active
     */
    public record Param(String mixinClass, String... mixinDisable) {}
}

package dev.sixik.generator_accelerator.api.mixin;

public record MixinApplier(String modClassPath, Param[] mixins) {

    public boolean hasDisableMixin(String mixin) {
        for(Param param : this.mixins) {
            for(String s : param.mixinDisable) {
                if (s.equals(mixin) || matchesPackagePrefix(s, mixin)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean matchesPackagePrefix(String pattern, String mixin) {
        return pattern.endsWith(".*")
                && mixin.startsWith(pattern.substring(0, pattern.length() - 1));
    }

    public boolean hasMixin(String mixin) {
        for (Param param : mixins) {
            if(param.mixinClass.equals(mixin))
                return true;
        }

        return false;
    }

    public boolean isModLoaded() {
        if(modClassPath.isEmpty()) return true;

        if (modClassPath.indexOf(';') >= 0) {
            for (String modClass : modClassPath.split(";")) {
                if (!isClassLoaded(modClass)) {
                    return false;
                }
            }
            return true;
        }

        return isClassLoaded(modClassPath);
    }

    private static boolean isClassLoaded(String modClassPath) {
        return isClassLoaded(modClassPath, MixinApplier.class.getClassLoader());
    }

    static boolean isClassLoaded(String modClassPath, ClassLoader classLoader) {
        if (modClassPath.isEmpty()) return true;

        try {
            Class.forName(modClassPath, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public record Param(String mixinClass, String... mixinDisable) {}
}

package dev.sixik.generator_accelerator.api.mixin;

public record MixinApplier(String modClassPath, Param[] mixins) {

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
        if (modClassPath.isEmpty()) return true;

        try {
            Class.forName(modClassPath, false, MixinApplier.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }

    public record Param(String mixinClass, String... mixinDisable) {}
}

package dev.sixik.generator_accelerator.common.surface_compiler.runtime.mc121;

import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceRuntime;

public final class Mc121SurfaceRuntime {
    private Mc121SurfaceRuntime() {
    }

    public static String bindingVersion() {
        return SurfaceRuntime.RUNTIME_BINDING_VERSION + ":mc121";
    }
}

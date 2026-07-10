package dev.sixik.generator_accelerator.common.surface_compiler.runtime.mc122;

import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceRuntime;

public final class Mc122SurfaceRuntime {
    private Mc122SurfaceRuntime() {
    }

    public static String bindingVersion() {
        return SurfaceRuntime.RUNTIME_BINDING_VERSION + ":mc122";
    }
}

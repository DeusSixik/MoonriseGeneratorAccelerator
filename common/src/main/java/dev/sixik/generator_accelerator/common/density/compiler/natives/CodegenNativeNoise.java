package dev.sixik.generator_accelerator.common.density.compiler.natives;

/** Java-only density compiler gate. Native/JNI density fast paths stay disabled. */
public final class CodegenNativeNoise {

    public static boolean enabled() {
        return false;
    }

    public static boolean emitNativeOps() {
        return false;
    }

    private CodegenNativeNoise() {}
}

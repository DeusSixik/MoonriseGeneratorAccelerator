package dev.sixik.generator_accelerator.common.surface.compiler.mask;

public final class MaskOps {
    private MaskOps() {
    }

    public static void copyAnd(Mask4096 target, Mask4096 left, Mask4096 right) {
        target.copyFrom(left);
        target.and(right);
    }

    public static void copyAndNot(Mask4096 target, Mask4096 left, Mask4096 right) {
        target.copyFrom(left);
        target.andNot(right);
    }
}

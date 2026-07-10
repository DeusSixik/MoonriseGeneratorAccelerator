package dev.sixik.generator_accelerator.common.surface_compiler.ir;

public record SurfaceOp(String opcode, SurfaceEffect effect, SurfaceDomain domain, SurfaceStateToken stateIn, SurfaceStateToken stateOut, String detail) {
    public SurfaceOp(String opcode, SurfaceEffect effect, SurfaceStateToken stateIn, SurfaceStateToken stateOut, String detail) {
        this(opcode, effect, SurfaceDomain.OPAQUE, stateIn, stateOut, detail);
    }

    public boolean isStateful() {
        return this.stateIn != null || this.stateOut != null;
    }

    public boolean preservesTokenOrder(SurfaceOp next) {
        if (!isStateful() || !next.isStateful()) {
            return true;
        }
        return this.stateOut != null && this.stateOut.equals(next.stateIn);
    }
}

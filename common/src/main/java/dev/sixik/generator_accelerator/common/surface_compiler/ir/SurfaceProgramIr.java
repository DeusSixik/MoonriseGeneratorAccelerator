package dev.sixik.generator_accelerator.common.surface_compiler.ir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SurfaceProgramIr {
    private final String rootClassName;
    private final SurfaceNode root;
    private final List<SurfaceOp> ops = new ArrayList<>();

    public SurfaceProgramIr(String rootClassName) {
        this(rootClassName, SurfaceNode.opaque(rootClassName, "legacy-root"));
    }

    public SurfaceProgramIr(String rootClassName, SurfaceNode root) {
        this.rootClassName = rootClassName;
        this.root = root;
    }

    public void add(SurfaceOp op) {
        this.ops.add(op);
    }

    public SurfaceProgramIr copyWithOps(String rootClassName, java.util.List<SurfaceOp> newOps) {
        SurfaceProgramIr copy = new SurfaceProgramIr(rootClassName, this.root);
        for (SurfaceOp op : newOps) {
            copy.add(op);
        }
        return copy;
    }

    public SurfaceProgramIr copyWithOps(java.util.List<SurfaceOp> newOps) {
        return copyWithOps(this.rootClassName, newOps);
    }

    public String rootClassName() {
        return this.rootClassName;
    }

    public SurfaceNode root() {
        return this.root;
    }

    public List<SurfaceOp> ops() {
        return Collections.unmodifiableList(this.ops);
    }

    public boolean hasUnsafeOrMutatingOp() {
        for (SurfaceOp op : this.ops) {
            if (op.effect() == SurfaceEffect.UNSAFE || op.effect() == SurfaceEffect.MUTATING || op.effect() == SurfaceEffect.OPAQUE_CALLOUT) {
                return true;
            }
        }
        return false;
    }

    public int nodeCount() {
        return this.root == null ? this.ops.size() : this.root.nodeCount();
    }

    public boolean tokenChainIsLinear() {
        SurfaceStateToken expected = null;
        for (SurfaceOp op : this.ops) {
            if (!op.isStateful()) {
                continue;
            }
            if (expected != null && !expected.equals(op.stateIn())) {
                return false;
            }
            expected = op.stateOut();
        }
        return true;
    }
}

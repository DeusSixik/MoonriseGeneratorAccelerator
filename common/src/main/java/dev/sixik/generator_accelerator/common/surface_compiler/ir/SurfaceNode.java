package dev.sixik.generator_accelerator.common.surface_compiler.ir;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SurfaceNode {
    private final Kind kind;
    private final SurfaceEffect effect;
    private final SurfaceDomain domain;
    private final String sourceClassName;
    private final String opcode;
    private final List<SurfaceNode> children;
    private final BlockState blockState;
    private final String detail;

    private SurfaceNode(Kind kind, SurfaceEffect effect, SurfaceDomain domain, String sourceClassName, String opcode,
                        List<SurfaceNode> children, BlockState blockState, String detail) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.sourceClassName = sourceClassName == null ? "unknown" : sourceClassName;
        this.opcode = opcode == null ? kind.name() : opcode;
        this.children = List.copyOf(children == null ? List.of() : children);
        this.blockState = blockState;
        this.detail = detail == null ? "" : detail;
    }

    public static SurfaceNode state(BlockState state, String sourceClassName) {
        return new SurfaceNode(Kind.STATE, SurfaceEffect.PURE, SurfaceDomain.CONSTANT, sourceClassName, "STATE", List.of(), state, String.valueOf(state));
    }

    public static SurfaceNode sequence(List<SurfaceNode> children, String sourceClassName) {
        return new SurfaceNode(Kind.SEQUENCE, SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.CONSTANT, sourceClassName, "SEQUENCE", children, null, "children=" + children.size());
    }

    public static SurfaceNode test(SurfaceNode condition, SurfaceNode thenRun, String sourceClassName) {
        return new SurfaceNode(Kind.TEST, SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.OPAQUE, sourceClassName, "IF_TRUE", List.of(condition, thenRun), null, sourceClassName);
    }

    public static SurfaceNode condition(Kind kind, SurfaceEffect effect, SurfaceDomain domain, String sourceClassName, String detail, List<SurfaceNode> children) {
        return new SurfaceNode(kind, effect, domain, sourceClassName, kind.name(), children, null, detail);
    }

    public static SurfaceNode vanillaCallout(Kind kind, SurfaceEffect effect, SurfaceDomain domain, String sourceClassName, String detail) {
        return new SurfaceNode(kind, effect, domain, sourceClassName, kind.name(), List.of(), null, detail);
    }

    public static SurfaceNode opaque(String sourceClassName, String detail) {
        return new SurfaceNode(Kind.OPAQUE, SurfaceEffect.OPAQUE_CALLOUT, SurfaceDomain.OPAQUE, sourceClassName, "OPAQUE", List.of(), null, detail);
    }

    public static SurfaceNode adapterCallout(String sourceClassName, SurfaceEffect effect, SurfaceDomain domain, String detail) {
        return new SurfaceNode(Kind.ADAPTER_CALLOUT, effect, domain, sourceClassName, "ADAPTER_CALLOUT", List.of(), null, detail);
    }

    public Kind kind() {
        return this.kind;
    }

    public SurfaceEffect effect() {
        return this.effect;
    }

    public SurfaceDomain domain() {
        return this.domain;
    }

    public String sourceClassName() {
        return this.sourceClassName;
    }

    public String opcode() {
        return this.opcode;
    }

    public List<SurfaceNode> children() {
        return Collections.unmodifiableList(this.children);
    }

    public BlockState blockState() {
        return this.blockState;
    }

    public String detail() {
        return this.detail;
    }

    public int nodeCount() {
        int count = 1;
        for (SurfaceNode child : this.children) {
            count += child.nodeCount();
        }
        return count;
    }

    public boolean containsEffect(SurfaceEffect target) {
        if (this.effect == target) {
            return true;
        }
        for (SurfaceNode child : this.children) {
            if (child.containsEffect(target)) {
                return true;
            }
        }
        return false;
    }

    public List<SurfaceNode> flattenPreOrder() {
        List<SurfaceNode> out = new ArrayList<>();
        flattenInto(out);
        return out;
    }

    private void flattenInto(List<SurfaceNode> out) {
        out.add(this);
        for (SurfaceNode child : this.children) {
            child.flattenInto(out);
        }
    }

    public enum Kind {
        STATE,
        SEQUENCE,
        TEST,
        NOT,
        WATER_CHECK,
        Y_CHECK,
        STONE_DEPTH,
        NOISE,
        BIOME,
        BANDLANDS,
        VANILLA_CONSTANT,
        ADAPTER_CALLOUT,
        OPAQUE
    }
}

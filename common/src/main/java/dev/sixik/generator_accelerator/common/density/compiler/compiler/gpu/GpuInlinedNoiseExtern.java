package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.runtime.Runtime;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** CPU bridge used by the first fillSlice GPU prototype until InlinedNoise is native in the GPU kernel. */
final class GpuInlinedNoiseExtern implements DensityFunction {
    private final IRNode.InlinedNoise node;
    private final NoiseSpec spec;

    GpuInlinedNoiseExtern(IRNode.InlinedNoise node, ConstantPool pool) {
        this.node = node;
        this.spec = pool.noiseSpec(node.specPoolIndex());
    }

    @Override
    public double compute(FunctionContext context) {
        double x = eval(node.coordX(), context);
        double y = eval(node.coordY(), context);
        double z = eval(node.coordZ(), context);
        return (branch(spec.first(), x, y, z) + branch(spec.second(), x, y, z)) * spec.valueFactor();
    }

    @Override
    public void fillArray(double[] array, ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(array, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return this;
    }

    @Override
    public double minValue() {
        return -node.maxValue();
    }

    @Override
    public double maxValue() {
        return node.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        throw new UnsupportedOperationException("GPU InlinedNoise extern is runtime-only");
    }

    private static double branch(NoiseSpec.PerlinSpec branch, double x, double y, double z) {
        double sum = 0.0D;
        double coordScale = branch.inputCoordScale();
        var octaves = branch.activeOctaves();
        double[] inputFactors = branch.inputFactors();
        double[] ampValueFactors = branch.ampValueFactors();
        for (int i = 0; i < octaves.length; i++) {
            double inputFactor = inputFactors[i];
            double nx = Runtime.wrapAxis(x * coordScale * inputFactor);
            double ny = Runtime.wrapAxis(y * coordScale * inputFactor);
            double nz = Runtime.wrapAxis(z * coordScale * inputFactor);
            sum += octaves[i].noise(nx, ny, nz) * ampValueFactors[i];
        }
        return sum;
    }

    private static double eval(IRNode node, FunctionContext context) {
        return switch (node) {
            case IRNode.Const c -> c.value();
            case IRNode.BlockX ignored -> context.blockX();
            case IRNode.BlockY ignored -> context.blockY();
            case IRNode.BlockZ ignored -> context.blockZ();
            case IRNode.Bin b -> bin(b.op(), eval(b.left(), context), eval(b.right(), context));
            case IRNode.Unary u -> unary(u.op(), eval(u.input(), context));
            case IRNode.Clamp c -> Math.max(c.min(), Math.min(c.max(), eval(c.input(), context)));
            case IRNode.YClampedGradient g -> yClampedGradient(g, context.blockY());
            default -> throw new IllegalArgumentException("Unsupported InlinedNoise coordinate node: "
                    + node.getClass().getName());
        };
    }

    private static double bin(IRNode.BinOp op, double left, double right) {
        return switch (op) {
            case ADD -> left + right;
            case SUB -> left - right;
            case MUL -> left * right;
            case DIV -> left / right;
            case MIN -> Math.min(left, right);
            case MAX -> Math.max(left, right);
        };
    }

    private static double unary(IRNode.UnaryOp op, double value) {
        return switch (op) {
            case ABS -> Math.abs(value);
            case NEG -> -value;
            case SQUARE -> value * value;
            case CUBE -> value * value * value;
            case HALF_NEGATIVE -> value > 0.0D ? value : value * 0.5D;
            case QUARTER_NEGATIVE -> value > 0.0D ? value : value * 0.25D;
            case SQUEEZE -> Runtime.squeeze(value);
        };
    }

    private static double yClampedGradient(IRNode.YClampedGradient g, int blockY) {
        int fromY = g.fromY();
        int toY = g.toY();
        double fromValue = g.fromValue();
        double toValue = g.toValue();
        if (fromY < toY) {
            if (blockY <= fromY) {
                return fromValue;
            }
            if (blockY >= toY) {
                return toValue;
            }
        } else if (fromY > toY) {
            if (blockY >= fromY) {
                return fromValue;
            }
            if (blockY <= toY) {
                return toValue;
            }
        }
        return ((double) (blockY - fromY)) * ((toValue - fromValue) / (double) (toY - fromY)) + fromValue;
    }
}

package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.asm.VariablesManipulator;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerConstantTask extends DensityCompilerTask<DensityFunctions.Constant> {

    @Override
    protected void compileCompute(MethodVisitor visitor, DensityFunctions.Constant node, PipelineAsmContext ctx) {
        var i = ctx.createIntVar();
        var j = ctx.createIntVar();

        ctx.readIntVar(i);
        ctx.pushInt(2);
        ctx.div(VariablesManipulator.VariableType.INT);
        var k = ctx.createIntVarFromStack(); // int k = i / 2;

        ctx.readIntVar(j);
        ctx.pushInt(2);
        ctx.div(VariablesManipulator.VariableType.INT);
        var l = ctx.createIntVarFromStack(); // int l = j / 2;

        ctx.readIntVar(i);
        ctx.pushInt(2);
        ctx.rem(VariablesManipulator.VariableType.INT);
        var m = ctx.createIntVarFromStack(); // int m = i % 2;

        ctx.readIntVar(j);
        ctx.pushInt(2);
        ctx.rem(VariablesManipulator.VariableType.INT);
        var n = ctx.createIntVarFromStack(); // int n = j % 2;

        ctx.readIntVar(i);
        ctx.readIntVar(i);
        ctx.mul(VariablesManipulator.VariableType.INT);
        var ii = ctx.createIntVarFromStack(); // int ii = i * i;

        ctx.readIntVar(j);
        ctx.readIntVar(j);
        ctx.mul(VariablesManipulator.VariableType.INT);
        var jj = ctx.createIntVarFromStack(); // int jj = j * j;

        ctx.mv().visitLdcInsn(100.0f); // f = 100.0

        ctx.readIntVar(ii);
        ctx.readIntVar(jj);
        ctx.add(VariablesManipulator.VariableType.INT); // ii + jj
        ctx.mv().visitInsn(I2F); // (float)(ii + jj)

        ctx.invokeMethodStatic(
                Mth.class,
                "sqrt",
                "(F)F"
        );

        ctx.mv().visitLdcInsn(8.0f);
        ctx.mul(VariablesManipulator.VariableType.FLOAT); // Mth.sqrt(ii + jj) * 8.0f

        ctx.sub(VariablesManipulator.VariableType.FLOAT); // 100.0f - Mth.sqrt(ii + jj) * 8.0f

        ctx.mv().visitInsn(F2D);
        var f = ctx.createDoubleVarFromStack(); // double f = 100.0f - Mth.sqrt(ii + jj) * 8.0f;

        ctx.readDoubleVar(f);
        ctx.mv().visitLdcInsn(-100.0D);
        ctx.ifElse(ctx.doubleLt(), () -> {
            ctx.writeDoubleVar(f, -100.0D);
        }, () -> {

            ctx.readDoubleVar(f);
            ctx.mv().visitLdcInsn(80.0D);

            ctx.ifElse(ctx.doubleGt(), () -> {
                ctx.writeDoubleVar(f, 80.0D);
            }, null);

        });

        var threshold = ctx.createDoubleVar(-0.9D);

        ctx.forRange(-12, 13, (o) -> {

            ctx.readIntVar(k);
            ctx.readIntVar(o);
            ctx.add(VariablesManipulator.VariableType.INT);
            ctx.mv().visitInsn(I2L);
            int q = ctx.createLongVarFromStack(); // q = k + o

            ctx.readLongVar(q);
            ctx.readLongVar(q);
            ctx.mul(VariablesManipulator.VariableType.LONG);
            int q2 = ctx.createLongVarFromStack(); // q2 = q * q

            ctx.readLongVar(q);

            ctx.readLongVar(q);
            ctx.pushInt(63);
            ctx.shiftRight(VariablesManipulator.VariableType.LONG);  // q >> 63

            ctx.bitwiseXor(VariablesManipulator.VariableType.LONG); // q ^ (q >> 63)

            ctx.readLongVar(q);
            ctx.pushInt(63);
            ctx.shiftRight(VariablesManipulator.VariableType.LONG); // q >> 63

            ctx.sub(VariablesManipulator.VariableType.LONG); // (xor) - (q >> 63)

            ctx.mv().visitInsn(L2D);
            var absQf = ctx.createDoubleVarFromStack(); // absQf = (q ^ q >> 63) - (q >> 63)

            ctx.readIntVar(m);

            ctx.readIntVarUnSafe(o);
            ctx.pushInt(2);
            ctx.mul(VariablesManipulator.VariableType.INT); // o * 2
            ctx.sub(VariablesManipulator.VariableType.INT); // m - o * 2

            ctx.mv().visitInsn(I2D);
            var h = ctx.createDoubleVarFromStack(); // h = m - o * 2

            ctx.readDoubleVar(h);
            ctx.readDoubleVar(h);
            ctx.mul(VariablesManipulator.VariableType.DOUBLE);
            var h2 = ctx.createDoubleVarFromStack(); // h2 = h * h


            ctx.forRange(-12, 13, p -> {

                ctx.readIntVar(l);
                ctx.readIntVarUnSafe(p);
                ctx.add(VariablesManipulator.VariableType.INT);
                ctx.mv().visitInsn(I2L);
                var r = ctx.createLongVarFromStack(); // r = (long) l + p

                ctx.readLongVar(r);
                ctx.readLongVar(r);
                ctx.mul(VariablesManipulator.VariableType.LONG);
                var r2 = ctx.createLongVarFromStack(); // r2 = r * r;


                ctx.readLongVar(q2);
                ctx.readLongVar(r2);
                ctx.add(VariablesManipulator.VariableType.LONG);
                ctx.mv().visitLdcInsn(4096L);

                // if (q2 + r2 <= 4096) continue
                ctx.ifThen(ctx.longLe(), () -> {
                    ctx.loopContinue();
                });

                ctx.readLongVar(q);
                ctx.mv().visitInsn(L2D);
                ctx.invokeMethodStatic(
                        Mth.class,
                        "sqrt",
                        DescriptorBuilder.builder().d().buildMethod(double.class)
                );
                ctx.readDoubleVar(threshold);
                ctx.ifThen(ctx.doubleGe(), () -> {
                    ctx.loopContinue();
                });

                ctx.readLongVar(r);

                ctx.readLongVar(r);
                ctx.pushInt(63);
                ctx.shiftRight(VariablesManipulator.VariableType.LONG);  // r >> 63

                ctx.bitwiseXor(VariablesManipulator.VariableType.LONG); // r ^ (r >> 63)

                ctx.readLongVar(r);
                ctx.pushInt(63);
                ctx.shiftRight(VariablesManipulator.VariableType.LONG); // r >> 63

                ctx.sub(VariablesManipulator.VariableType.LONG); // (xor) - (r >> 63)

                ctx.mv().visitInsn(L2D);
                var absRf = ctx.createDoubleVarFromStack(); // absRf = (r ^ r >> 63) - (r >> 63)

                ctx.readDoubleVar(absQf);
                ctx.mv().visitLdcInsn(3439.0D);
                ctx.mul(VariablesManipulator.VariableType.DOUBLE); // (absQf * 3439.0f)

                ctx.readDoubleVar(absRf);
                ctx.mv().visitLdcInsn(147.0D);
                ctx.mul(VariablesManipulator.VariableType.DOUBLE); // (absRf * 147.0f)

                ctx.add(VariablesManipulator.VariableType.DOUBLE); // (Q*3439) + (R*147)

                ctx.mv().visitLdcInsn(13.0D);
                ctx.rem(VariablesManipulator.VariableType.DOUBLE); // % 13.0f

                ctx.mv().visitLdcInsn(9.0D);
                ctx.add(VariablesManipulator.VariableType.DOUBLE); // + 9.0f

                int g = ctx.createDoubleVarFromStack(); // g = (absQf * 3439.0f + absRf * 147.0f) % 13.0f + 9.0f

                ctx.readIntVar(n);

                ctx.readIntVar(p);
                ctx.pushInt(2);
                ctx.mul(VariablesManipulator.VariableType.INT);
                ctx.sub(VariablesManipulator.VariableType.INT);

                ctx.mv().visitInsn(I2D);

                var s = ctx.createDoubleVarFromStack();

                ctx.mv().visitLdcInsn(100.0D);

                ctx.readDoubleVar(h2);

                ctx.readDoubleVar(s);
                ctx.readDoubleVar(s);
                ctx.mul(VariablesManipulator.VariableType.DOUBLE);
                ctx.add(VariablesManipulator.VariableType.DOUBLE);

                ctx.invokeMethodStatic(
                        Mth.class,
                        "sqrt",
                        DescriptorBuilder.builder().d().buildMethod(double.class)
                );

                ctx.readDoubleVar(g);
                ctx.mul(VariablesManipulator.VariableType.DOUBLE);
                ctx.sub(VariablesManipulator.VariableType.DOUBLE);

                var t = ctx.createDoubleVarFromStack();

                ctx.readDoubleVar(t);
                ctx.mv().visitLdcInsn(-100.0D);
                ctx.ifElse(ctx.doubleLt(), () -> {
                    ctx.writeDoubleVar(t, -100.0D);
                }, () -> {

                    ctx.readDoubleVar(t);
                    ctx.mv().visitLdcInsn(80.0D);

                    ctx.ifElse(ctx.doubleGt(), () -> {
                        ctx.writeDoubleVar(t, 80.0D);
                    }, null);

                });

                ctx.readDoubleVar(t);
                ctx.readDoubleVar(f);
                ctx.ifThen(ctx.doubleGt(), () -> {
                    ctx.readDoubleVar(t);
                    ctx.writeDoubleVar(f);
                });
            });
        });

        ctx.readDoubleVar(f);
    }
}

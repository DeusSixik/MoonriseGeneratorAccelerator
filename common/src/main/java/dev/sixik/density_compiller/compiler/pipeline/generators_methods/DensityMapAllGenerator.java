package dev.sixik.density_compiller.compiler.pipeline.generators_methods;

import dev.sixik.density_compiller.compiler.DensityCompilerParams;
import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.Map;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;

public class DensityMapAllGenerator implements DensityCompilerPipelineGenerator {
    @Override
    public void applyMethod(DensityCompilerPipeline pipeline, PipelineAsmContext ctx, DensityFunction root, String className, String classSimpleName, int id) {
        final var mv = ctx.mv();
        final Map<DensityFunction, Integer> leavesMap = pipeline.locals.leafToId;

        String densityType = "net/minecraft/world/level/levelgen/DensityFunction";
        String visitorType = "net/minecraft/world/level/levelgen/DensityFunction$Visitor";
        String descDensity = "L" + densityType + ";";

        if (leavesMap.isEmpty()) {
            /*
                If there are no leaves, simply: return visitor.apply(this);
             */
            mv.visitVarInsn(ALOAD, 1); // visitor
            ctx.loadThis();            // this
            mv.visitMethodInsn(INVOKEINTERFACE, visitorType, "apply", "(" + descDensity + ")" + descDensity, true);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(2, 2);
        } else {
            /*
                Logic:
                1. Create a new array
                2. Fill it with the results of leaf.mapAll(visitor)
                3. Create a new OptimizedDensity(array)
                4. return visitor.apply(newInstance)
             */

            /*
                Preparing the stack for the final call to visitor.apply(...)
             */
            mv.visitVarInsn(ALOAD, 1); // visitor

            /*
                Starting the creation of the object: NEW OptimizedDensity
             */
            mv.visitTypeInsn(NEW, className);
            mv.visitInsn(DUP); // [visitor, newObj, newObj]

            /*
                Creating an array for the constructor
             */
            ctx.iconst(leavesMap.size());
            mv.visitTypeInsn(ANEWARRAY, densityType); // [visitor, newObj, newObj, arrayRef]

            /*
                Filling in the array
            */
            for (var entry : leavesMap.entrySet()) {
                int index = entry.getValue();

                mv.visitInsn(DUP); // Copy arrayRef
                ctx.iconst(index); // Array index

                /*
                    Getting the current field: this.leaf_N
                 */
                ctx.loadThis();
                ctx.getField(PipelineAsmContext.DEFAULT_LEAF_FUNCTION_NAME + "_" + index, descDensity);

                /*
                    Loading visitor
                 */
                mv.visitVarInsn(ALOAD, 1);

                /*
                    Calling leaf.mapAll(visitor)
                 */
                mv.visitMethodInsn(INVOKEINTERFACE, densityType, "mapAll", "(L" + visitorType + ";)" + descDensity, true);

                /*
                    Saving it to an array
                 */
                mv.visitInsn(AASTORE);
            }

            // Stack: [visitor, newObj, newObj, filledArray]

            /*
                Calling the constructor
             */
            mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", "([L" + densityType + ";)V", false);
            // Stack: [visitor, newInstance]

            /*
                The final call is visitor.apply(newInstance)
             */
            mv.visitMethodInsn(INVOKEINTERFACE, visitorType, "apply", "(" + descDensity + ")" + descDensity, true);

            mv.visitInsn(ARETURN);
            mv.visitMaxs(7, 2);
        }
    }

    @Override
    public MethodVisitor generateMethod(DensityCompilerPipeline pipeline, ClassWriter cw, DensityFunction root) {
        return cw.visitMethod(ACC_PUBLIC,
                "mapAll",
                "(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;",
                null,
                null);
    }
}

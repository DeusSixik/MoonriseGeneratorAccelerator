import dev.sixik.density_compiller.compiler.DensityCompiler;
import dev.sixik.density_compiller.compiler.DensityCompilerParams;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.*;

public class TestCompiler extends DensityCompiler {

    private Consumer<DensityCompilerContext> consumer;

    public TestCompiler(Consumer<DensityCompilerContext> consumer) {
        this.consumer = consumer;
    }

    @Override
    protected void generateCompute(ClassWriter cw, String className, DensityFunction root) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC,
                "compute",
                CONTEXT_DESC,
                null,
                null);

        if(DensityCompilerParams.useCheckMethodAdapter)
            mv = new org.objectweb.asm.util.CheckMethodAdapter(mv);

        mv.visitCode();

        L_LINK.get().clear();

        try {
            DensityCompilerContext context = new DensityCompilerContext(this, mv, className, root);

            /*
                Recursively generate calculation instructions
             */

            mv.visitLdcInsn(0.0);

//            consumer.accept(context);

            mv.visitInsn(DRETURN);                // Return result (double)
            mv.visitMaxs(0, 0); // ASM will calculate the stacks itself
            mv.visitEnd();
        } catch (Exception e) {
            printTrace("Error while end compile", L_LINK.get());
            throw e;
        }
    }

    public void generateFill(ClassWriter cw, String className, DensityFunction root) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC,
                "fillArray",
                "([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V",
                null,
                null);

        if(DensityCompilerParams.useCheckMethodAdapter)
            mv = new org.objectweb.asm.util.CheckMethodAdapter(mv);

        mv.visitCode();

        L_LINK.get().clear();

        try {
            DensityCompilerContext context = new DensityCompilerContext(this, mv, className, root);

            consumer.accept(context);

            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        } catch (Exception e) {
            printTrace("Error while generating fillArray", L_LINK.get());
            throw e;
        }
    }
}

package dev.sixik.density_compiller.compiler;

import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompiler {

    public static final AtomicInteger ID_GEN = new AtomicInteger();
    public static final String INTERFACE_NAME = Type.getInternalName(DensityFunction.class);
    public static final String CONTEXT_DESC = "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D";
    public static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";

    /**
     *  Storage for "Leaves" (complex functions) that we cannot inline
     */
    public final List<DensityFunction> leaves = new ArrayList<>();
    public final Map<DensityFunction, Integer> leafToId = new ConcurrentHashMap<>();

    static {
        DensityCompilerData.boot();
    }


    public void compileAndDump(DensityFunction root, String filename) {
        final int id = ID_GEN.incrementAndGet();
        final String className = "dev/sixik/generated/OptimizedDensity_" + id;

        /*
            Create class
         */
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, className, null, "java/lang/Object", new String[]{INTERFACE_NAME});

        /*
            Field for storing an array of leaves: private final DensityFunction[] leaves;
         */
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "leaves", "[L" + INTERFACE_NAME + ";", null, null).visitEnd();

        /*
            Constructor
         */
        generateConstructor(cw, className);

        /*
            Method compute(FunctionContext ctx)
         */
        generateCompute(cw, className, root);

        /*
            Stubs for required methods (minValue, maxValue, codec)
         */
        generateDelegates(cw, className, root);

        cw.visitEnd();
        final byte[] bytes = cw.toByteArray();

        CompilerInfrastructure.debugWriteClass(filename + id + ".class", bytes);
    }

    public DensityFunction compile(DensityFunction root) {
        final int id = ID_GEN.incrementAndGet();
        final String className = "dev/sixik/generated/OptimizedDensity_" + id;

        /*
            Create class
         */
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, className, null, "java/lang/Object", new String[]{INTERFACE_NAME});

        /*
            Field for storing an array of leaves: private final DensityFunction[] leaves;
         */
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "leaves", "[L" + INTERFACE_NAME + ";", null, null).visitEnd();

        /*
            Constructor
         */
        generateConstructor(cw, className);

        /*
            Method compute(FunctionContext ctx)
         */
        generateCompute(cw, className, root);

        /*
            Stubs for required methods (minValue, maxValue, codec)
         */
        generateDelegates(cw, className, root);

        cw.visitEnd();

        final byte[] bytes = cw.toByteArray();
        if(DensityCompilerParams.dumpGenerated) {
            CompilerInfrastructure.debugWriteClass("OptimizedDensity_" + id + ".class", bytes);
        }

        /*
            Instantiate
         */
        return CompilerInfrastructure.defineAndInstantiate(className, bytes, leaves);
    }

    private void generateConstructor(ClassWriter cw, String className) {
        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC,
                "<init>",
                "([L" + INTERFACE_NAME + ";)V",
                null,
                null);
        mv.visitCode();

        // super()
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false);

        // this.leaves = leavesArg;
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD,
                className,
                "leaves",
                "[L" + INTERFACE_NAME + ";");

        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    public static final ThreadLocal<ArrayDeque<String>> L_LINK = ThreadLocal.withInitial(ArrayDeque::new);

    private void generateCompute(ClassWriter cw, String className, DensityFunction root) {
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
            context.compileNodeCompute(root);

            mv.visitInsn(DRETURN);                // Return result (double)
            mv.visitMaxs(0, 0); // ASM will calculate the stacks itself
            mv.visitEnd();
        } catch (Exception e) {
            printTrace("Error while end compile", L_LINK.get());
            throw e;
        }
    }

    private void printTrace(String message, ArrayDeque<String> compilationStack) {
        System.err.println("[DensityCompiler Trace] " + message);
        System.err.println("Compilation Path (top is current):");
        int depth = 0;
        for (String s : compilationStack) {
            System.err.println("  " + (depth++) + ": " + s);
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
            // Инициализируем контекст.
            // Важно: он должен знать, что переменные 0, 1, 2 заняты.
            // Свободные слоты начинаются с 3.
            DensityCompilerContext context = new DensityCompilerContext(this, mv, className, root);

            // --- УДАЛЕНО: Создание tempBufferA и tempBufferB ---
            // Мы НЕ создаем массивы здесь. Если они понадобятся (например для Add),
            // нода сама вызовет context.allocateTempBuffer().

            // Запускаем рекурсию. Пишем результат сразу в аргумент 1 (основной массив).
            context.compileNodeFill(root, 1);

            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        } catch (Exception e) {
            printTrace("Error while generating fillArray", L_LINK.get());
            throw e;
        }
    }

    private void generateDelegates(ClassWriter cw, String className, DensityFunction root) {
        /*
            Local variables:
            0: this
            1: ds (double[])
            2: provider
            3: i (int)
            4: length (int)
         */


        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC,
                "mapAll",
                "(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;",
                null,
                null);
        mv.visitCode();

        if (DensityCompilerParams.useThisMapper) {
            mv.visitVarInsn(ALOAD, 0);      // put 'this' to stack
            mv.visitInsn(ARETURN);                  // return he
            mv.visitMaxs(1, 2);   // Stack: 1 (this), Locals: 2 (this + visitor)
        } else {
            mv.visitVarInsn(ALOAD, 1);      // Load 'visitor'
            mv.visitVarInsn(ALOAD, 0);      // Load 'this'
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "net/minecraft/world/level/levelgen/DensityFunction$Visitor",
                    "apply",
                    "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;",
                    true);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(2, 2);   // Stack: 2, Locals: 2
        }
        mv.visitEnd();

        generateFill(cw, className, root) ;

//        mv = cw.visitMethod(ACC_PUBLIC,
//                "fillArray",
//                "([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V",
//                null,
//                null);
//        mv.visitCode();

        // int length = ds.length;
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitVarInsn(ISTORE, 4); // We keep the length so that we don't have to pull the field every time

        // int i = 0;
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 3);

        final Label loopStart = new Label();
        final Label loopEnd = new Label();

        mv.visitLabel(loopStart);
        // if (i >= length) break;
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitJumpInsn(IF_ICMPGE, loopEnd);

        // ds[i] = ...
        mv.visitVarInsn(ALOAD, 1); // Load array
        mv.visitVarInsn(ILOAD, 3); // Load array

        // ... this.compute(provider.forIndex(i))
        mv.visitVarInsn(ALOAD, 0); // this

        mv.visitVarInsn(ALOAD, 2); // provider
        mv.visitVarInsn(ILOAD, 3); // i
        mv.visitMethodInsn(INVOKEINTERFACE,
                "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider",
                "forIndex",
                "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;",
                true);

        mv.visitMethodInsn(INVOKEVIRTUAL, className, "compute", CONTEXT_DESC, false);
        mv.visitInsn(DASTORE);

        mv.visitIincInsn(3, 1);
        mv.visitJumpInsn(GOTO, loopStart);

        mv.visitLabel(loopEnd);
        mv.visitInsn(RETURN);
        mv.visitMaxs(5, 4);
        mv.visitEnd();

        // minValue
        mv = cw.visitMethod(ACC_PUBLIC, "minValue", "()D", null, null);
        mv.visitCode();

        // We just take the value from the original root and push it into the bytecode.
        mv.visitLdcInsn(root.minValue());
        mv.visitInsn(DRETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        // maxValue()
        mv = cw.visitMethod(ACC_PUBLIC, "maxValue", "()D", null, null);
        mv.visitCode();

        // Similarly, for the maximum as in the minimum
        mv.visitLdcInsn(root.maxValue());
        mv.visitInsn(DRETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        /*
            codec - you can return null or throw an exception, as the compiled object is not serializable
            You usually don't need a codec for runtime generation.
            Of course, it is possible that some mod will suddenly want to serialize noise data, but this is unlikely
         */
        mv = cw.visitMethod(ACC_PUBLIC,
                "codec",
                "()Lnet/minecraft/util/KeyDispatchDataCodec;",
                null,
                null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL); // Возвращаем null (осторожно, может крашнуть дебаггеры)
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }
}

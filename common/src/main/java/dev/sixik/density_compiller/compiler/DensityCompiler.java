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
    public static final String OBJECT_NAME = Type.getInternalName(Object.class);
//    public static final String INTERFACE_NAME = OBJECT_NAME;
    public static final String INTERFACE_NAME = Type.getInternalName(DensityFunction.class);
    public static final String CONTEXT_DESC = "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D";
    public static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";

    /**
     *  Storage for "Leaves" (complex functions) that we cannot inline
     */
    public final List<Object> leaves = new ArrayList<>(); // Было DensityFunction
    public final Map<Object, Integer> leafToId = new ConcurrentHashMap<>();

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
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "leaves", "[L" + OBJECT_NAME + ";", null, null).visitEnd();

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

        // ОБЯЗАТЕЛЬНО: Очищаем состояние перед каждой компиляцией
        this.leaves.clear();
        this.leafToId.clear();

        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, className, null, "java/lang/Object", new String[]{INTERFACE_NAME});

        // 1. СНАЧАЛА генерируем compute, чтобы наполнить список leaves
        generateCompute(cw, className, root);

        // 2. ТЕПЕРЬ генерируем поля, когда мы точно знаем, сколько их и какие типы
        for (int i = 0; i < leaves.size(); i++) {
            Object leaf = leaves.get(i);
            String descriptor = Type.getDescriptor(leaf.getClass());
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "leaf_" + i, descriptor, null, null).visitEnd();
        }

        // 3. Генерируем конструктор
        generateConstructor(cw, className);

        // 4. Остальные методы
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
                "([Ljava/lang/Object;)V",
                null,
                null);
        mv.visitCode();

        // super()
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        // !!! Распаковка массива в поля
        for (int i = 0; i < leaves.size(); i++) {
            Object leaf = leaves.get(i);
            String internalName = Type.getInternalName(leaf.getClass());
            String descriptor = Type.getDescriptor(leaf.getClass());

            mv.visitVarInsn(ALOAD, 0);       // this
            mv.visitVarInsn(ALOAD, 1);       // args array (Object[])

            // Оптимизация загрузки индекса
            if (i <= 5) mv.visitInsn(ICONST_0 + i);
            else if (i <= 127) mv.visitIntInsn(BIPUSH, i);
            else mv.visitIntInsn(SIPUSH, i);

            mv.visitInsn(AALOAD);            // Берем Object из массива

            // !!! Обязательный каст к конкретному типу поля
            mv.visitTypeInsn(CHECKCAST, internalName);

            // Записываем в поле leaf_N
            mv.visitFieldInsn(PUTFIELD, className, "leaf_" + i, descriptor);
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0); // COMPUTE_MAXS сам посчитает
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
            context.compileNode(root);

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
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(1, 2);
        } else {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "net/minecraft/world/level/levelgen/DensityFunction$Visitor",
                    "apply",
                    "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;",
                    true);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(2, 2);
        }
        mv.visitEnd();

        // fillArray
        mv = cw.visitMethod(ACC_PUBLIC,
                "fillArray",
                "([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V",
                null,
                null);
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitVarInsn(ISTORE, 4);

        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 3);

        final Label loopStart = new Label();
        final Label loopEnd = new Label();

        mv.visitLabel(loopStart);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitJumpInsn(IF_ICMPGE, loopEnd);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 3);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
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
        mv.visitLdcInsn(root.minValue());
        mv.visitInsn(DRETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        // maxValue
        mv = cw.visitMethod(ACC_PUBLIC, "maxValue", "()D", null, null);
        mv.visitCode();
        mv.visitLdcInsn(root.maxValue());
        mv.visitInsn(DRETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        // codec
        mv = cw.visitMethod(ACC_PUBLIC, "codec", "()Lnet/minecraft/util/KeyDispatchDataCodec;", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }
}

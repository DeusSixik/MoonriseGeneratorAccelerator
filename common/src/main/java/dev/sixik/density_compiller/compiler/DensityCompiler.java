package dev.sixik.density_compiller.compiler;

import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompiler {

    public static final AtomicInteger ID_GEN = new AtomicInteger();
    public static final String INTERFACE_NAME = Type.getInternalName(DensityFunction.class);
    public static final String CONTEXT_DESC = "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D";
    public static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";

    // Хранилище для "Листьев" (сложных функций), которые мы не можем инлайнить
    public final List<DensityFunction> leaves = new ArrayList<>();
    public final Map<DensityFunction, Integer> leafToId = new ConcurrentHashMap<>();

    static {
        DensityCompilerData.boot();
    }



    public void compileAndDump(DensityFunction root, String filename) {
        int id = ID_GEN.incrementAndGet(); // Фиктивный ID для теста
        String className = "dev/sixik/generated/OptimizedDensity_" + id;

        // 1. Создаем класс
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, className, null, "java/lang/Object", new String[]{INTERFACE_NAME});

        // 2. Поле для хранения массива листьев: private final DensityFunction[] leaves;
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "leaves", "[L" + INTERFACE_NAME + ";", null, null).visitEnd();

        // 3. Конструктор
        generateConstructor(cw, className);

        // 4. Метод compute(FunctionContext ctx)
        generateCompute(cw, className, root);

        // 5. Заглушки для обязательных методов (minValue, maxValue, codec)
        generateDelegates(cw, className, root);

        cw.visitEnd();

        byte[] bytes = cw.toByteArray();

        // ВОТ ЭТО ГЛАВНОЕ:
        CompilerInfrastructure.debugWriteClass(filename + id + ".class", bytes);
    }

    public DensityFunction compile(DensityFunction root) {
        int id = ID_GEN.incrementAndGet();
        String className = "dev/sixik/generated/OptimizedDensity_" + id;

        // 1. Создаем класс
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, className, null, "java/lang/Object", new String[]{INTERFACE_NAME});

        // 2. Поле для хранения массива листьев: private final DensityFunction[] leaves;
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "leaves", "[L" + INTERFACE_NAME + ";", null, null).visitEnd();

        // 3. Конструктор
        generateConstructor(cw, className);

        // 4. Метод compute(FunctionContext ctx)
        generateCompute(cw, className, root);

        // 5. Заглушки для обязательных методов (minValue, maxValue, codec)
        generateDelegates(cw, className, root);

        cw.visitEnd();

        // 6. Инстанцирование
        return CompilerInfrastructure.defineAndInstantiate(className, cw.toByteArray(), leaves);
    }

    private void generateConstructor(ClassWriter cw, String className) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "([L" + INTERFACE_NAME + ";)V", null, null);
        mv.visitCode();
        // super()
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        // this.leaves = leavesArg;
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, className, "leaves", "[L" + INTERFACE_NAME + ";");
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    public static final ThreadLocal<ArrayDeque<String>> L_LINK = ThreadLocal.withInitial(ArrayDeque::new);

    private void generateCompute(ClassWriter cw, String className, DensityFunction root) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "compute", CONTEXT_DESC, null, null);
        mv = new org.objectweb.asm.util.CheckMethodAdapter(mv); // Добавь это!
        mv.visitCode();

        L_LINK.get().clear();

        try {
            DensityCompilerContext context = new DensityCompilerContext(this, mv, className, root);
            // Рекурсивно генерируем инструкции вычислений
            context.compileNode(root);

            mv.visitInsn(DRETURN); // Возвращаем результат (double)
            mv.visitMaxs(0, 0); // ASM сам посчитает стеки
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

    // Заглушки для методов интерфейса
    private void generateDelegates(ClassWriter cw, String className, DensityFunction root) {
        // 1. mapAll(Visitor visitor)
        // Мы просто возвращаем this, так как оптимизированный код не должен меняться
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "mapAll", "(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC, "fillArray", "([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V", null, null);
        mv.visitCode();

        // Локальные переменные:
        // 0: this
        // 1: ds (double[])
        // 2: provider
        // 3: i (int)
        // 4: length (int)

        // int length = ds.length;
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitVarInsn(ISTORE, 4); // Сохраняем длину, чтобы не дергать поле каждый раз

        // int i = 0;
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 3);

        Label loopStart = new Label();
        Label loopEnd = new Label();

        mv.visitLabel(loopStart);
        // if (i >= length) break;
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitJumpInsn(IF_ICMPGE, loopEnd);

        // ds[i] = ...
        mv.visitVarInsn(ALOAD, 1); // Грузим массив
        mv.visitVarInsn(ILOAD, 3); // Грузим индекс

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
        mv.visitLdcInsn(0.0);
        mv.visitInsn(DRETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        // maxValue
        mv = cw.visitMethod(ACC_PUBLIC, "maxValue", "()D", null, null);
        mv.visitCode();
        mv.visitLdcInsn(0.0);
        mv.visitInsn(DRETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        // codec - можно вернуть null или кинуть exception, так как скомпилированный объект не сериализуем
        // Но лучше вернуть кодек оригинала, если возможно, или заглушку.
        // Для рантайма генерации кодек обычно не нужен.
        mv = cw.visitMethod(ACC_PUBLIC, "codec", "()Lnet/minecraft/util/KeyDispatchDataCodec;", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL); // Возвращаем null (осторожно, может крашнуть дебаггеры)
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // fillArray - просто делегируем в дефолтную реализацию (цикл по compute)
        // Т.к. мы наследуем Object, нам нужно реализовать fillArray.
        // Но DensityFunction.SimpleFunction имеет дефолтную реализацию.
        // Здесь мы реализуем интерфейс напрямую, поэтому можно просто скопипастить цикл,
        // Либо (проще) сделать класс abstract и extends Object implements DensityFunction
        // Но проще реализовать fillArray через вызов compute в цикле (как в SimpleFunction).
        // (Опустим для краткости, это не горячий путь для NoiseRouter в 1.20)
    }
}

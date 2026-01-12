package dev.sixik.density_compiller.compiler;

import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompiler {

    public static final AtomicInteger ID_GEN = new AtomicInteger();
    public static final String INTERFACE_NAME = Type.getInternalName(DensityFunction.class);
    public static final String CONTEXT_DESC = "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D";

    // Хранилище для "Листьев" (сложных функций), которые мы не можем инлайнить
    public final List<DensityFunction> leaves = new ArrayList<>();
    public final Map<DensityFunction, Integer> leafToId = new HashMap<>();

    static {
        DensityCompilerData.boot();
    }

    public void compileAndDump(DensityFunction root, String filename) {
        int id = 999; // Фиктивный ID для теста
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
        CompilerInfrastructure.debugWriteClass(filename, bytes);
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

    private void generateCompute(ClassWriter cw, String className, DensityFunction root) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "compute", CONTEXT_DESC, null, null);
        mv.visitCode();

        DensityCompilerContext context = new DensityCompilerContext(this, mv, className, root);
        // Рекурсивно генерируем инструкции вычислений
        context.compileNode(root);

        mv.visitInsn(DRETURN); // Возвращаем результат (double)
        mv.visitMaxs(0, 0); // ASM сам посчитает стеки
        mv.visitEnd();
    }

    private void compileNode(MethodVisitor mv, String className, DensityFunction node) {
        // --- 1. FastAdd (Твой класс) ---
        if (node instanceof DensitySpecializations.FastAdd op) {
            compileNode(mv, className, op.a());
            compileNode(mv, className, op.b());
            mv.visitInsn(DADD);
            return;
        }

        // --- 2. FastMul ---
        if (node instanceof DensitySpecializations.FastMul op) {
            compileNode(mv, className, op.a());
            compileNode(mv, className, op.b());
            mv.visitInsn(DMUL);
            return;
        }

        // --- 3. FastMin ---
        if (node instanceof DensitySpecializations.FastMin op) {
            compileNode(mv, className, op.a());
            compileNode(mv, className, op.b());
            // Вызов Math.min(double, double)
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            return;
        }

        // --- 4. FastMax ---
        if (node instanceof DensitySpecializations.FastMax op) {
            compileNode(mv, className, op.a());
            compileNode(mv, className, op.b());
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
            return;
        }

        // --- 5. Constant ---
        if (node instanceof DensityFunctions.Constant c) {
            mv.visitLdcInsn(c.value());
            return;
        }

        // --- 6. Стандартная Арифметика (Если вдруг попался Ap2) ---
        if (node instanceof DensityFunctions.TwoArgumentSimpleFunction ap2) {
            compileNode(mv, className, ap2.argument1());
            compileNode(mv, className, ap2.argument2());
            switch (ap2.type()) {
                case ADD -> mv.visitInsn(DADD);
                case MUL -> mv.visitInsn(DMUL);
                case MIN -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
                case MAX -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
            }
            return;
        }

        // --- DEFAULT: Лист (Noise, Blender, ShiftedNoise, etc.) ---
        // Мы не умеем это компилировать, поэтому вызываем как внешний объект
        emitLeafCall(mv, className, node);
    }

    private void emitLeafCall(MethodVisitor mv, String className, DensityFunction leaf) {
        // Регистрируем лист, если его нет
        int idx = leafToId.computeIfAbsent(leaf, k -> {
            leaves.add(k);
            return leaves.size() - 1;
        });

        // Генерируем вызов: leaves[idx].compute(ctx)

        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitFieldInsn(GETFIELD, className, "leaves", "[L" + INTERFACE_NAME + ";");

        // Загружаем индекс
        if (idx <= 5) mv.visitInsn(ICONST_0 + idx);
        else if (idx <= 127) mv.visitIntInsn(BIPUSH, idx);
        else mv.visitIntInsn(SIPUSH, idx);

        mv.visitInsn(AALOAD); // Получили объект DensityFunction со стека

        mv.visitVarInsn(ALOAD, 1); // Загружаем Context (аргумент метода)

        // Вызываем compute
        mv.visitMethodInsn(INVOKEINTERFACE, INTERFACE_NAME, "compute", CONTEXT_DESC, true);
    }

    // Заглушки для методов интерфейса
    private void generateDelegates(ClassWriter cw, String className, DensityFunction root) {
        // minValue
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "minValue", "()D", null, null);
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

package dev.sixik.density_compiller.compiler.pipeline.context;

import dev.sixik.asm.AsmCtx;
import dev.sixik.density_compiller.compiler.DensityCompiler;
import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.locals.DensityCompilerLocals;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.objectweb.asm.Opcodes.*;

public class PipelineAsmContext extends AsmCtx {

    public static final String DEFAULT_LEAF_FUNCTION_NAME = "leaf_function";

    protected final DensityCompilerPipeline pipeline;
    protected final ContextCache cache = new ContextCache();

    public PipelineAsmContext(
            DensityCompilerPipeline pipeline,
            MethodVisitor mv,
            String ownerInternalName,
            int firstFreeLocal,
            int currentContextVar
    ) {
        super(mv, ownerInternalName, firstFreeLocal, currentContextVar);
        this.pipeline = pipeline;
    }

    public void putField(int iVar) {
        mv.visitFieldInsn(PUTFIELD,
                pipeline.configurator.className(),
                DEFAULT_LEAF_FUNCTION_NAME + "_" + iVar,
                DescriptorBuilder.builder().type(DensityFunction.class).build());
    }

    public int getOrCreateLeafIndex(DensityFunction leaf) {
        final DensityCompilerLocals locals = pipeline.locals;
        return locals.leafToId.computeIfAbsent(leaf, (k) -> {
            locals.leaves.add(k);
            return locals.leaves.size() - 1;
        });
    }

    public void startLoop() { this.cache.loopContextVar = -1; }

    /**
     * Возвращает контекст для текущей итерации.
     * Если он еще не создан в этом цикле — создает и сохраняет в переменную.
     */
    public int getOrAllocateLoopContext(int iVar) {
        if (this.cache.loopContextVar == -1) {
            // Выделяем новый слот REF
            int var = newLocalRef();

            aload(2); // Provider
            iload(iVar);
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider", "forIndex", "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;", true);

            astore(var);
            this.cache.loopContextVar = var;
        }
        return this.cache.loopContextVar;
    }

    public int getOrComputeLength(int destArrayVar) {
        final ContextCache cache = this.cache;
        int len = cache.cachedLengthVar;

        if (len == -1) {
            len = newLocalInt();
            aload(destArrayVar);
            mv.visitInsn(ARRAYLENGTH);
            istore(len);
            cache.cachedLengthVar = len;
        }
        return len;
    }

    public void visitLeafReference(DensityFunction node) {
        visitLeaf(node);
    }

    public void visitLeaf(DensityFunction leaf) {
        final int variable = getOrCreateLeafIndex(leaf);

        String fieldName = DEFAULT_LEAF_FUNCTION_NAME + "_" + variable;
        String fieldDescriptor = DescriptorBuilder.builder().type(DensityFunction.class).build();

        loadThis();
        getField(fieldName, fieldDescriptor);
    }

    public void visitNodeCompute(DensityFunction node) {
        try {
            final Class<? extends DensityFunction> clz = node.getClass();
            final Supplier<DensityCompilerTask<?>> taskSupplier = DensityCompilerData.getTask(clz);

            if (taskSupplier != null) {
                final DensityCompilerTask<?> task = taskSupplier.get();
                if((task.buildBits() & DensityCompilerTask.COMPUTE) != 0) {
                    taskSupplier.get().compileComputeImpl(mv, node, this);
                    return;
                }
            }

            visitLeafCall(node);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Регистрирует объект как лист и возвращает его индекс и имя поля.
     * @param leaf Объект (DensityFunction, NoiseHolder, Spline и т.д.)
     * @param descriptor Дескриптор типа поля (например "Lnet/minecraft/.../NoiseHolder;")
     */
    public void visitCustomLeaf(Object leaf, String descriptor) {
        final DensityCompilerLocals locals = pipeline.locals;

        // Используем identity map или обычную, зависит от equals
        int index = locals.leafToId.computeIfAbsent(leaf, (k) -> {
            locals.leaves.add(k); // Добавляем в общий список для конструктора
            return locals.leaves.size() - 1;
        });

        // ВАЖНО: Нам нужно сообщить DensityConstructorGenerator, какой тип у этого поля!
        // Пока что у тебя все поля генерируются как DensityFunction.
        // Чтобы это исправить, нужно хранить Map<Integer, String> fieldDescriptors в Locals.
        locals.leafTypes.put(index, descriptor);

        String fieldName = DEFAULT_LEAF_FUNCTION_NAME + "_" + index;

        loadThis();
        getField(fieldName, descriptor);
    }

    public void visitLeafCall(DensityFunction node) {
        visitLeaf(node);
        visitContext();
        invokeInterface(DensityCompiler.INTERFACE_NAME, "compute", DensityCompiler.CONTEXT_DESC);
    }

    public void visitContext() {
        mv.visitVarInsn(ALOAD, currentContextVar);

    }

    public void invokeProviderForIndex() {
        invokeProviderInterface(
                "forIndex",
                DescriptorBuilder.builder().i().buildMethod(DensityFunction.FunctionContext.class)
        );
    }

    public void invokeProviderInterface(String name, String desc) {
        invokeInterface(DescriptorBuilder.builder().type(DensityFunction.ContextProvider.class).build(), name, desc);
    }

    public void invokeContextInterface(String name, String desc) {
        invokeInterface(DescriptorBuilder.builder().type(DensityFunction.FunctionContext.class).build(), name, desc);
    }


    public void arrayForFill(
            int destArrayVar,
            double value
    ) {
        arrayForI(destArrayVar, (i) -> {
            final MethodVisitor mv = mv();
            mv.visitVarInsn(ALOAD, destArrayVar);   // Array
            mv.visitVarInsn(ILOAD, i);              // Index
            mv.visitLdcInsn(value);                 // Value
            mv.visitInsn(DASTORE);                  // Store double
        });
    }

    public void arrayForI(
            int destArrayVar,
            Consumer<Integer> iteration
    ) {
        /*
            We get the same length variable for the entire method.
         */
        int lenVar = getOrComputeLength(destArrayVar);

        /*
            The i counter must still be unique for each loop
            so that there are no nesting conflicts, but the JIT often collapses them on its own.
         */
        int iVar = newLocalInt();

        final MethodVisitor mv = mv();

        // int i = 0;
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, iVar);

        Label startLoop = new Label();
        Label endLoop = new Label();

        mv.visitLabel(startLoop);

        // if (i >= len) break
        mv.visitVarInsn(ILOAD, iVar);
        mv.visitVarInsn(ILOAD, lenVar); // Using the general lenVar
        mv.visitJumpInsn(IF_ICMPGE, endLoop);

        iteration.accept(iVar);

        mv.visitIincInsn(iVar, 1);
        mv.visitJumpInsn(GOTO, startLoop);

        mv.visitLabel(endLoop);
    }

    public void emitLeafFill(DensityFunction leaf, int destArrayVar) {

        /*
            Loading the function object itself (from the leaves array)
         */
        visitLeaf(leaf);

        /*
            Loading the destination array
         */
        aload(destArrayVar);

        /*
            Loading the ContextProvider (it is always in slot 2 in the fillArray method)
         */
        aload(2);

        /*
            Calling fillArray
         */
        mv.visitMethodInsn(INVOKEINTERFACE,
                DensityCompiler.INTERFACE_NAME,
                "fillArray",
                DescriptorBuilder.builder()
                        .array(double.class)
                        .type(DensityFunction.ContextProvider.class)
                        .buildMethodVoid(),
                true);
    }

    public void visitNodeFill(DensityFunction node, int destArrayVar) {
        try {
            final Class<? extends DensityFunction> clz = node.getClass();
            final Supplier<DensityCompilerTask<?>> taskSupplier = DensityCompilerData.getTask(clz);

            if (taskSupplier != null) {

                /*
                    Calling a specific optimization for fill
                    We need to attach the Task to the raw type or wildcards to call the method.
                 */
                final DensityCompilerTask task = taskSupplier.get();
                if((task.buildBits() & DensityCompilerTask.FILL) != 0) {
                    task.compileFill(mv, node, this, destArrayVar);
                    return;
                }
            }

              /*
                If there is no task, we call it as a sheet.
             */
            emitLeafFill(node, destArrayVar);
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * Allocates a new local variable for a temporary double[] array
     * of the same length as the main array (which is in slot 1).
     * Generates an array creation code (new double[length]).
     *
     * @return index of the new variable
     */
    public int allocateTempBuffer() {
        int varIndex = newLocalInt();

        /*
            array.length (taking the length from the main array in slot 1)
         */
        aload(1);
        insn(ARRAYLENGTH);

        /*
            new double[...]
         */
        mv.visitIntInsn(NEWARRAY, T_DOUBLE);

        /*
            ASTORE varIndex
         */
        astore(varIndex);

        return varIndex;
    }

    public void compileNodeComputeForIndex(MethodVisitor mv, DensityFunction node, int iVar) {
        if (node instanceof DensityFunctions.Constant c) {
            mv.visitLdcInsn(c.value());
            return;
        }

        // FIX: Мы не должны оставлять Context на стеке перед вызовом compileNodeCompute.
        // Мы должны сохранить его в локальную переменную и передать через механизм ContextVar.

        int tempCtxVar = newLocalInt();

        // 1. Создаем контекст: provider.forIndex(i)
        mv.visitVarInsn(ALOAD, 2); // Provider (всегда 2 в fillArray)
        mv.visitVarInsn(ILOAD, iVar);
        mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider", "forIndex", "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;", true);

        // 2. Сохраняем в переменную
        mv.visitVarInsn(ASTORE, tempCtxVar);

        // 3. Компилируем ноду с этим контекстом
        compileNodeComputeWithContext(node, tempCtxVar);
    }

    public void compileNodeComputeWithContext(DensityFunction node, int contextVarIndex) {
        if (node instanceof DensityFunctions.Constant c) {
            mv.visitLdcInsn(c.value());
            return;
        }

        // FIX: Безопасное переключение контекста
        int oldCtx = this.currentContextVar;
        this.currentContextVar = contextVarIndex; // Переключаем указатель

        visitNodeCompute(node); // Компилируем (теперь таски будут брать contextVarIndex)

        this.currentContextVar = oldCtx; // Возвращаем обратно
    }
}

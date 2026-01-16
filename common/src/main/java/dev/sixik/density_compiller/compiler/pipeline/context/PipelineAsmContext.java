package dev.sixik.density_compiller.compiler.pipeline.context;

import dev.sixik.asm.AsmCtx;
import dev.sixik.density_compiller.compiler.DensityCompiler;
import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler;
import dev.sixik.density_compiller.compiler.pipeline.locals.DensityCompilerLocals;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PipelineAsmContext extends AsmCtx implements
        DensityFunctionsCacheHandler
{

    public static final String DEFAULT_LEAF_FUNCTION_NAME = "leaf_function";

    protected final DensityCompilerPipeline pipeline;
    protected final ContextCache cache = new ContextCache();

    private final Map<DensityFunction, Integer> nodeToLocal = new IdentityHashMap<>();

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

    public void startLoop() {
        this.cache.loopContextVar = -1;
//        this.cache.xVar = -1;
//        this.cache.yVar = -1;
//        this.cache.zVar = -1;
    }

    public void loadBlender() {
        if (cache.blenderVar != -1) {
            mv.visitVarInsn(ALOAD, cache.blenderVar);
        } else {
            loadContext();
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "getBlender", "()Lnet/minecraft/world/level/levelgen/blending/Blender;", true);
        }
    }

    public void cacheBlender() {
        if (cache.blenderVar == -1) {
            cache.blenderVar = newLocalRef();
            aload(2); // Provider
            iconst(0);
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider", "forIndex", "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;", true);
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "getBlender", "()Lnet/minecraft/world/level/levelgen/blending/Blender;", true);
            astore(cache.blenderVar);
        }
    }

    public void preCacheConstants() {
        // 1. Выделяем слоты
        this.cache.xVar = newLocalDouble();
        this.cache.yVar = newLocalDouble();
        this.cache.zVar = newLocalDouble();
        this.cache.blenderVar = newLocalRef();

        // 2. Получаем контекст для индекса 0 (базовый контекст для констант)
        if (this.currentContextVar == 1) {
            // Мы в методе compute. Провайдера нет, берем всё из входного контекста.
            aload(1);
        } else {
            // Мы в fillArray. Достаем контекст для индекса 0 из провайдера.
            aload(2); // Provider
            iconst(0);
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider", "forIndex", "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;", true);
        }
        // Стек: [FunctionContext]
        mv.visitInsn(DUP);

        // Кэшируем Blender
        mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "getBlender", "()Lnet/minecraft/world/level/levelgen/blending/Blender;", true);
        mv.visitVarInsn(ASTORE, cache.blenderVar);

        // Стек: [FunctionContext]
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockX", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DSTORE, cache.xVar);

        // Стек: [FunctionContext]
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockY", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DSTORE, cache.yVar);

        // Стек: [FunctionContext]
        mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockZ", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DSTORE, cache.zVar);
    }

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

    public void loadBlockX() {
        if (cache.xVar != -1) {
            mv.visitVarInsn(DLOAD, cache.xVar);
        } else {
            // Fallback для compute()
            loadContext();
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockX", "()I", true);
            mv.visitInsn(I2D);
        }
    }

    public void loadBlockY() {
        if (cache.yVar != -1) {
            mv.visitVarInsn(DLOAD, cache.yVar);
        } else {
            // Fallback для compute()
            loadContext();
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockY", "()I", true);
            mv.visitInsn(I2D);
        }
    }

    public void loadBlockZ() {
        if (cache.zVar != -1) {
            mv.visitVarInsn(DLOAD, cache.zVar);
        } else {
            loadContext();
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockZ", "()I", true);
            mv.visitInsn(I2D);
        }
    }

//    public void loadBlockY() {
//        if (cache.loopContextVar != -1) {
//            if (cache.yVar == -1) {
//                cache.yVar = newLocalDouble();
//                loadContext();
//                mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockY", "()I", true);
//                mv.visitInsn(I2D);
//                mv.visitVarInsn(DSTORE, cache.yVar);
//            }
//            mv.visitVarInsn(DLOAD, cache.yVar);
//        } else {
//            loadContext();
//            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockY", "()I", true);
//            mv.visitInsn(I2D);
//        }
//    }

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
        visitNodeCompute(node, DensityCompilerTask.COMPUTE);
    }

    public void visitNodeCompute(DensityFunction node, int bits) {
        try {
            final Class<? extends DensityFunction> clz = node.getClass();
            final Supplier<DensityCompilerTask<?>> taskSupplier = DensityCompilerData.getTask(clz);

            if (taskSupplier != null) {
                final DensityCompilerTask<?> task = taskSupplier.get();

                if((bits & DensityCompilerTask.COMPUTE) != 0)
                    task.compileComputeImpl(mv, node, this);
                if((bits & DensityCompilerTask.PREPARE_COMPUTE) != 0)
                    task.prepareComputeImpl(mv, node, this);
                if((bits & DensityCompilerTask.POST_PREPARE_COMPUTE) != 0)
                    task.postPrepareComputeImpl(mv, node, this);

                return;
            }

            visitLeafCall(node);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Регистрирует объект как лист и возвращает его индекс и имя поля.
     *
     * @param leaf       Объект (DensityFunction, NoiseHolder, Spline и т.д.)
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
//                final DensityCompilerTask task = taskSupplier.get();
//                if ((task.buildBits() & DensityCompilerTask.FILL) != 0) {
//                    task.compileFill(mv, node, this, destArrayVar);
//                    return;
//                }
            }

              /*
                If there is no task, we call it as a sheet.
             */
            emitLeafFill(node, destArrayVar);
        } catch (Exception e) {
            throw e;
        }
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


    @Override
    public ContextCache cache() {
        return cache;
    }

    @Override
    public AsmCtx ctx() {
        return this;
    }
}

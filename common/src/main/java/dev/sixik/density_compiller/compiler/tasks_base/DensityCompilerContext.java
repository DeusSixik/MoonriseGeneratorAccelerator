package dev.sixik.density_compiller.compiler.tasks_base;

import dev.sixik.asm.AsmCtx;
import dev.sixik.density_compiller.compiler.DensityCompiler;
import dev.sixik.density_compiller.compiler.DensityCompilerParams;
import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.objectweb.asm.Opcodes.*;

public final class DensityCompilerContext {
    private final DensityCompiler compiler;
    private final MethodVisitor mv;
    private final String className;
    private final DensityFunction root;
    private int nextLocalVarIndex = 5;
    private int cachedLengthVar = -1;
    private int currentContextVar = 1;
    private int loopContextVar = -1;

    private final AsmCtx ctx;

    private LinkedList<String> compiledFilesComput = new LinkedList<>();
    private LinkedList<String> compiledFilesFill = new LinkedList<>();

    public DensityCompilerContext(DensityCompiler compiler, MethodVisitor mv, String className,
                                  DensityFunction root) {
        this.compiler = compiler;
        this.mv = mv;
        this.className = className;
        this.root = root;
        this.ctx = new AsmCtx(mv, className, 0, 0);
    }

    public String CTX() {
        return DensityCompiler.CTX;
    }

    public AsmCtx getCtx() {
        return ctx;
    }

    public boolean canCompile(DensityFunction node) {
        return DensityCompilerData.getTask(node.getClass()) != null;
    }

    public void compileNodeCompute(DensityFunction node) {
        compileNodeCompute(mv, node);
    }

    public void compileNodeCompute(MethodVisitor mv, DensityFunction node) {
        String nodeName = node.getClass().getSimpleName();
        DensityCompiler.L_LINK.get().push(nodeName); // Recording the entrance to the node

        try {
            final Class<? extends DensityFunction> clz = node.getClass();
            final Supplier<DensityCompilerTask<?>> taskSupplier = DensityCompilerData.getTask(clz);

            if (taskSupplier != null) {
                final DensityCompilerTask<?> task = taskSupplier.get();
                if((task.buildBits() & DensityCompilerTask.COMPUTE) != 0) {
                    taskSupplier.get().compileComputeImpl(mv, node, null);

                    compiledFilesComput.add(clz.getName() + "_found");

                    return;
                }
            }


            if (DensityCompilerParams.crashIfUnsupportedType) {

                    /*
                        If we fall here, we'll see the path in the logs above.
                     */
                printTrace("Unsupported Type: " + node.getClass().getName());
                throw new UnsupportedOperationException("Un support for class: " + node.getClass().getName());
            }
            emitLeafCall(mv, node);
            compiledFilesComput.add(clz.getName() + "_none");
        } catch (Exception e) {

            /*
                If any error has occurred (including ASM or NPE)
             */
            printTrace("Error while compiling node");
            throw e;
        }
    }

    /**
     * Compiles the filling of the array.
     *
     * @param destArrayVar Index of the local variable containing the double[] where to write the result.
     */
    public void compileNodeFill(DensityFunction node, int destArrayVar) {
        String nodeName = node.getClass().getSimpleName();
        DensityCompiler.L_LINK.get().push(nodeName + " (Fill)");

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
//                    task.compileFill(mv, node, this, destArrayVar);

                    compiledFilesFill.add(nodeName + "_found");
                    return;
                }
            }

              /*
                If there is no task, we call it as a sheet.
             */
            emitLeafFill(mv, node, destArrayVar);
            compiledFilesFill.add(nodeName + "_none");
        } catch (Exception e) {
            printTrace("Error while compiling node fill");
            throw e;
        } finally {
            DensityCompiler.L_LINK.get().pop();
        }
    }

    public void emitLeafFill(MethodVisitor mv, DensityFunction leaf, int destArrayVar) {

        /*
            Loading the function object itself (from the leaves array)
         */
        emitLeafLoad(mv, leaf);

        /*
            Loading the destination array
         */
        ctx.aload(destArrayVar);

        /*
            Loading the ContextProvider (it is always in slot 2 in the fillArray method)
         */
        ctx.aload(2);

        /*
            Calling fillArray
         */
        mv.visitMethodInsn(INVOKEINTERFACE,
                DensityCompiler.INTERFACE_NAME,
                "fillArray",
                "([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V",
                true);
    }

    /**
     * Allocates a new local variable for a temporary double[] array
     * of the same length as the main array (which is in slot 1).
     * Generates an array creation code (new double[length]).
     *
     * @return index of the new variable
     */
    public int allocateTempBuffer() {
        int varIndex = nextLocalVarIndex++;

        /*
            array.length (taking the length from the main array in slot 1)
         */
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARRAYLENGTH);

        /*
            new double[...]
         */
        mv.visitIntInsn(NEWARRAY, T_DOUBLE);

        /*
            ASTORE varIndex
         */
        mv.visitVarInsn(ASTORE, varIndex);

        return varIndex;
    }

    public int allocateLocalVarIndex() {
        return nextLocalVarIndex++;
    }

    /**
     * An auxiliary method for loading a sheet onto the stack (so as not to duplicate the code)
     */
    private void emitLeafLoad(MethodVisitor mv, DensityFunction leaf) {
        int idx = compiler.leafToId.computeIfAbsent(leaf, k -> {
            compiler.leaves.add(k);
            return compiler.leaves.size() - 1;
        });

        ctx.loadThis(); // this
        ctx.getField("leaves", "[L" + DensityCompiler.INTERFACE_NAME + ";");
        // this.leaves

        if (idx <= 5) mv.visitInsn(ICONST_0 + idx);
        else if (idx <= 127) mv.visitIntInsn(BIPUSH, idx);
        else mv.visitIntInsn(SIPUSH, idx);

        mv.visitInsn(AALOAD);
    }

    public int getOrComputeLength(int destArrayVar) {
        if (cachedLengthVar == -1) {
            cachedLengthVar = nextLocalVarIndex++;
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitInsn(ARRAYLENGTH);
            mv.visitVarInsn(ISTORE, cachedLengthVar);
        }
        return cachedLengthVar;
    }

    /**
     * For compatibility with the old compute code
     */
    public void emitLeafCall(MethodVisitor mv, DensityFunction leaf) {
        emitLeafLoad(mv, leaf);

        loadContext(mv); // Context
        mv.visitMethodInsn(INVOKEINTERFACE, DensityCompiler.INTERFACE_NAME, "compute", DensityCompiler.CONTEXT_DESC, true);
    }

    public void emitLeafCallReference(MethodVisitor mv, DensityFunction leaf) {
        emitLeafLoad(mv, leaf);
    }

    public DensityCompiler compiler() {
        return compiler;
    }

    public MethodVisitor mv() {
        return mv;
    }

    public String className() {
        return className;
    }

    public DensityFunction root() {
        return root;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (DensityCompilerContext) obj;
        return Objects.equals(this.compiler, that.compiler) &&
                Objects.equals(this.mv, that.mv) &&
                Objects.equals(this.className, that.className) &&
                Objects.equals(this.root, that.root);
    }

    @Override
    public int hashCode() {
        return Objects.hash(compiler, mv, className, root);
    }

    @Override
    public String toString() {
        return "DensityCompilerContext[" +
                "compiler=" + compiler + ", " +
                "mv=" + mv + ", " +
                "className=" + className + ", " +
                "root=" + root + ']';
    }

    private void printTrace(String message) {
        System.err.println("[DensityCompiler Trace] " + message);
        System.err.println("Compilation Path (top is current):");
        int depth = 0;
        for (String s : DensityCompiler.L_LINK.get()) {
            System.err.println("  " + (depth++) + ": " + s);
        }
    }

    public void arrayForI(int arrayId, Consumer<Integer> consumer) {
        DensityCompilerUtils.arrayForI(this, arrayId, consumer);
    }

    public void arrayForFill(int arrayId, double value) {
        DensityCompilerUtils.arrayForFill(this, arrayId, value);
    }

    public void compileNodeComputeForIndex(MethodVisitor mv, DensityFunction node, int iVar) {
        if (node instanceof DensityFunctions.Constant c) {
            mv.visitLdcInsn(c.value());
            return;
        }

        // FIX: Мы не должны оставлять Context на стеке перед вызовом compileNodeCompute.
        // Мы должны сохранить его в локальную переменную и передать через механизм ContextVar.

        int tempCtxVar = allocateLocalVarIndex();

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

        compileNodeCompute(node); // Компилируем (теперь таски будут брать contextVarIndex)

        this.currentContextVar = oldCtx; // Возвращаем обратно
    }

    // Устанавливаем, какой слот использовать как Context
    public void loadContext(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, currentContextVar);
    }

    public void setCurrentContextVar(int varIndex) {
        this.currentContextVar = varIndex;
    }

    public int getCurrentContextVar() {
        return currentContextVar;
    }

    public void startLoop() { this.loopContextVar = -1; }

    /**
     * Возвращает контекст для текущей итерации.
     * Если он еще не создан в этом цикле — создает и сохраняет в переменную.
     */
    public int getOrAllocateLoopContext(int iVar) {
        if (loopContextVar == -1) {
            loopContextVar = allocateLocalVarIndex();
            mv.visitVarInsn(ALOAD, 2); // Provider
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider", "forIndex", "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;", true);
            mv.visitVarInsn(ASTORE, loopContextVar);
        }
        return loopContextVar;
    }

    public int allocateDoubleLocalVarIndex() {
        int idx = nextLocalVarIndex;
        nextLocalVarIndex += 2;
        return idx;
    }

    public void writeEnd(String filename) {
        if(true) return;

        try (PrintWriter fos = new PrintWriter("compiler/temp/" + filename)) {

            fos.println("-------------------------------------");
            fos.println("COMPUTE");
            fos.println("-------------------------------------");
            fos.println(" ");
            fos.println(" ");

            for (int i = 0; i < compiledFilesComput.size(); i++) {
                fos.println(compiledFilesComput.get(i));
            }

            fos.println(" ");
            fos.println(" ");
            fos.println("-------------------------------------");
            fos.println("FILL");
            fos.println("-------------------------------------");
            fos.println(" ");
            fos.println(" ");

            for (int i = 0; i < compiledFilesFill.size(); i++) {
                fos.println(compiledFilesFill.get(i));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

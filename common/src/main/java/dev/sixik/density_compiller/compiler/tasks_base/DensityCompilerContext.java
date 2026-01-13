package dev.sixik.density_compiller.compiler.tasks_base;

import dev.sixik.density_compiller.compiler.DensityCompiler;
import dev.sixik.density_compiller.compiler.DensityCompilerParams;
import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.MethodVisitor;

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

    public DensityCompilerContext(DensityCompiler compiler, MethodVisitor mv, String className,
                                  DensityFunction root) {
        this.compiler = compiler;
        this.mv = mv;
        this.className = className;
        this.root = root;
    }

    public String CTX() {
        return DensityCompiler.CTX;
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
                taskSupplier.get().compileComputeImpl(mv, node, this);
            } else {
                if (DensityCompilerParams.crashIfUnsupportedType) {

                    /*
                        If we fall here, we'll see the path in the logs above.
                     */
                    printTrace("Unsupported Type: " + node.getClass().getName());
                    throw new UnsupportedOperationException("Un support for class: " + node.getClass().getName());
                }
                emitLeafCall(mv, node);
            }
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
                DensityCompilerTask task = taskSupplier.get();
                task.compileFill(mv, node, this, destArrayVar);
            } else {

                /*
                    If there is no task, we call it as a sheet.
                 */
                emitLeafFill(mv, node, destArrayVar);
            }
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
        mv.visitVarInsn(ALOAD, destArrayVar);

        /*
            Loading the ContextProvider (it is always in slot 2 in the fillArray method)
         */
        mv.visitVarInsn(ALOAD, 2);

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

        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitFieldInsn(GETFIELD, className, "leaves", "[L" + DensityCompiler.INTERFACE_NAME + ";");

        if (idx <= 5) mv.visitInsn(ICONST_0 + idx);
        else if (idx <= 127) mv.visitIntInsn(BIPUSH, idx);
        else mv.visitIntInsn(SIPUSH, idx);

        mv.visitInsn(AALOAD);
    }

    /**
     * For compatibility with the old compute code
     */
    public void emitLeafCall(MethodVisitor mv, DensityFunction leaf) {
        emitLeafLoad(mv, leaf);
        mv.visitVarInsn(ALOAD, 1); // Context
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

}

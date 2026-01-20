package dev.sixik.density_compiler;

import dev.sixik.density_compiler.data.DensityCompilerData;
import dev.sixik.density_compiler.data.DensityCompilerLocals;
import dev.sixik.density_compiler.data.DensityComplierConfiguration;
import dev.sixik.density_compiler.instatiates.BasicDensityInstantiate;
import dev.sixik.density_compiler.loaders.DynamicClassLoader;
import dev.sixik.density_compiler.pipeline.*;
import dev.sixik.density_compiler.utils.stack.HtmlTreeStackMachine;
import dev.sixik.density_compiler.utils.stack.StackMachine;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompiler {

    public static final String DEFAULT_CLASS_PATH = "dev/sixik/generated/";

    protected static final DynamicClassLoader DynamicClassLoader = new DynamicClassLoader(DensityCompiler.class.getClassLoader());
    protected static final AtomicInteger ClassID = new AtomicInteger();

    public final DensityCompilerLocals locals = new DensityCompilerLocals();
    public final DensityComplierConfiguration configuration;
    public final StackMachine stackMachine;

    protected CompilerPipeline currentPipeline;

    protected final List<CompilerPipeline> pipelines = new LinkedList<>();
    protected final DensityFunction root;
    protected final int id;

    public static DensityCompiler from(DensityFunction densityFunction) {
        return from(densityFunction, false);
    }

    public static DensityCompiler from(DensityFunction densityFunction, boolean dump) {
        final int id = ClassID.getAndIncrement();
        final String className = DEFAULT_CLASS_PATH + "OptimizedDensityFunction_" + densityFunction.getClass().getSimpleName() + "_" + id;

        return new DensityCompiler(new DensityComplierConfiguration(
                className,
                densityFunction.getClass().getSimpleName(),
                dump,
                new BasicDensityInstantiate(),
                Type.getInternalName(DensityFunction.class)
        ), id, densityFunction, new HtmlTreeStackMachine());
    }

    public DensityCompiler(
            DensityComplierConfiguration configuration,
            int id,
            DensityFunction root,
            StackMachine stackMachine) {
        this(configuration, id, root, stackMachine,
                new DensityTreeOptimizerPipeline(),
                new DensityComputePipeline(),
                new DensityFillArrayPipeline(),
                new DensityConstructorPipeline(),
                new DensityMapAllPipeline(),
                new DensityMaxValuePipeline(),
                new DensityMinValuePipeline()
        );
    }

    public DensityCompiler(
            DensityComplierConfiguration configuration,
            int id,
            DensityFunction root,
            StackMachine stackMachine,
            CompilerPipeline... pipelines
    ) {
        DensityCompilerData.bootImpl();
        this.configuration = configuration;
        this.stackMachine = stackMachine;
        this.root = root;
        this.id = id;
        for (int i = 0; i < pipelines.length; i++) {
            this.pipelines.add(pipelines[i]);
        }
    }

    public DensityFunction compile() {
        return compile(null);
    }

    public DensityFunction compile(@Nullable DensityFunction inRoot) {
        final DensityFunction root = inRoot != null ? inRoot : this.root;
        if(root == null) throw new NullPointerException("DensityFunction can't be NULL !");

        locals.clear();

        final String className = DEFAULT_CLASS_PATH + "OptimizedDensityFunction_" + configuration.classSimpleName() + "_" + id;

        final ClassWriter cw = createClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        applyCodeGenerator(cw, root, id);
        applyFields(cw, root, id);

        cw.visitEnd();

        final byte[] bytes = cw.toByteArray();

        if (configuration.dumpsData()) {
            debugWriteClass("OptimizedDensityFunction_" + configuration.classSimpleName() + "_" + id + ".class", bytes);
        }

        final String formatedClassName = className.replace('/', '.');

        Object[] constructorArgs = locals.leaves.isEmpty() ? new Object[0] : new Object[] { locals.leaves.toArray(new Object[0]) };

        return configuration.instantiate()
                .newInstance(
                        this,
                        DynamicClassLoader,
                        className,
                        formatedClassName,
                        bytes,
                        constructorArgs
                );
    }

    protected void applyCodeGenerator(
            ClassWriter cw,
            DensityFunction root,
            int id
    ) {
        final List<CompilerPipeline> copy = this.pipelines;
        final String className = configuration.className();
        final String simpleClassName = configuration.classSimpleName();

        final StackMachine stackMachine = this.stackMachine;

        stackMachine.pushStack("ApplyCodeGenerator");


        for (int i = 0; i < copy.size(); i++) {
            root = copy.get(i).manageFunction(
                    this, root, className, simpleClassName, id);
        }

        for (int i = 0; i < copy.size(); i++) {
            final CompilerPipeline element = copy.get(i);

            if(element.ignore(this)) continue;

            this.currentPipeline = element;

            final GeneratorAdapter mv = element.generateMethod(
                    this, cw, root, className, simpleClassName, id);

            mv.visitCode();

            final var context = element.getByteCodeStructure(this)
                    .createContext(this, mv);

            stackMachine.pushStack(element.getClass().getSimpleName() + " Prepare Generate Method Body");
            element.prepareMethodBody(this, context, root, className, simpleClassName, id);
            stackMachine.popStack();

            stackMachine.pushStack(element.getClass().getSimpleName() + " Post Prepare Generate Method Body");
            element.postPrepareMethodBody(this, context, root, className, simpleClassName, id);
            stackMachine.popStack();

            stackMachine.pushStack(element.getClass().getSimpleName() + " Generate Method Body");
            element.generateMethodBody(this, context, root, className, simpleClassName, id);
            stackMachine.popStack();

            mv.endMethod();
        }

        stackMachine.popStack();
    }

    protected void applyFields(
            ClassWriter cw,
            DensityFunction root,
            int id
    ) {
        final List<CompilerPipeline> copy = pipelines;
        final String className = configuration.className();
        final String simpleClassName = configuration.classSimpleName();

        stackMachine.pushStack("Apply Fields");

        for (int i = 0; i < copy.size(); i++) {
            final CompilerPipeline element = copy.get(i);

            if(element.ignore(this)) continue;

            stackMachine.pushStack(element.getClass().getSimpleName() + " Generate Class Field");

            element.generateClassField(
                    this,
                    cw,
                    root,
                    className,
                    simpleClassName,
                    id
            );

            stackMachine.popStack();
        }

        stackMachine.popStack();
    }

    protected ClassWriter createClassWriter(final int flags) {
        ClassWriter cw = new ClassWriter(flags);
        applyClassWriterHeader(cw);
        applyClassWriterConfiguration(cw);
        return cw;
    }

    protected void applyClassWriterHeader(ClassWriter cw) {
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, configuration.className(), null, "java/lang/Object", configuration.interfaces_names());
    }

    protected void applyClassWriterConfiguration(ClassWriter cw) { }

    public static void debugWriteClass(String filename, byte[] bytes) {
        try (FileOutputStream fos = new FileOutputStream("compiler/" + filename)) {
            fos.write(bytes);
            System.out.println("Class dumped to: " + new java.io.File(filename).getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

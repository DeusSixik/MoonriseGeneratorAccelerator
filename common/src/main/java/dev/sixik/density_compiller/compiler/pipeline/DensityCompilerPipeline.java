package dev.sixik.density_compiller.compiler.pipeline;

import dev.sixik.density_compiller.compiler.CompilerInfrastructure;
import dev.sixik.density_compiller.compiler.pipeline.configuration.DensityCompilerPipelineConfigurator;
import dev.sixik.density_compiller.compiler.pipeline.generators_methods.*;
import dev.sixik.density_compiller.compiler.pipeline.instatiates.BasicDensityInstantiate;
import dev.sixik.density_compiller.compiler.pipeline.loaders.DynamicClassLoader;
import dev.sixik.density_compiller.compiler.pipeline.locals.DensityCompilerLocals;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerPipeline {

    protected static final DynamicClassLoader DYNAMIC_CLASS_LOADER = new DynamicClassLoader(DensityCompilerPipeline.class.getClassLoader());
    protected static final AtomicInteger ID_GEN = new AtomicInteger();

    private final DensityFunction root;
    private final int id;

    protected final LinkedList<DensityCompilerPipelineGenerator> generators = new LinkedList<>();

    public final DensityCompilerPipelineConfigurator configurator;
    public final DensityCompilerLocals locals = new DensityCompilerLocals();

    public static DensityCompilerPipeline from(DensityFunction densityFunction) {
        return from(densityFunction, false);
    }

    public static DensityCompilerPipeline from(DensityFunction densityFunction, boolean dumpData) {
        final int id = ID_GEN.getAndIncrement();
        final String className = "dev/sixik/generated/OptimizedDensity_" + densityFunction.getClass().getSimpleName() + "_" + id;

        return new DensityCompilerPipeline(new DensityCompilerPipelineConfigurator(
                className,
                densityFunction.getClass().getSimpleName(),
                dumpData,
                new BasicDensityInstantiate(),
                Type.getInternalName(DensityFunction.class)
        ), densityFunction, id);
    }

    public DensityCompilerPipeline(DensityCompilerPipelineConfigurator configurator, @Nullable DensityFunction densityFunction, int id) {
        this(configurator, densityFunction, id,
                new DensityComputeGenerator(),
                new DensityFillArrayGenerator(),
                new DensityMapAllGenerator(),
                new DensityMinValueGenerator(),
                new DensityMaxValueGenerator(),
                new DensityConstructorGenerator()
        );
    }

    public DensityCompilerPipeline(DensityCompilerPipelineConfigurator configurator, @Nullable DensityFunction densityFunction, int id, DensityCompilerPipelineGenerator... generators) {
        this.configurator = configurator;
        this.root = densityFunction;
        this.id = id;
        for (int i = 0; i < generators.length; i++) {
            this.generators.add(generators[i]);
        }
    }

    public DensityFunction startCompilation() {
        return startCompilation(null);
    }

    public DensityFunction startCompilation(@Nullable DensityFunction inRoot) {
        final DensityFunction root = inRoot != null ? inRoot : this.root;
        if (root == null)
            throw new NullPointerException("Root function can't be null!");
        final String className = "dev/sixik/generated/OptimizedDensity_" + configurator.classSimpleName() + "_" + id;

        final ClassWriter cw = generateWriter();
        generateByteCodeMethods(cw, root, id);
        generateByteCodeFields(cw, root, id);

        cw.visitEnd();

        final byte[] bytes = cw.toByteArray();

        if (configurator.dumpsData()) {
            CompilerInfrastructure.debugWriteClass("OptimizedDensity_" + configurator.classSimpleName() + "_" + id + ".class", bytes);
        }

        final String formatedClassName = className.replace('/', '.');

        Object[] constructorArgs = locals.leaves.isEmpty() ? new Object[0] : new Object[] { locals.leaves.toArray(new DensityFunction[0]) };

        return configurator.instantiate()
                .newInstance(
                        this,
                        DYNAMIC_CLASS_LOADER,
                        className,
                        formatedClassName,
                        bytes,
                        constructorArgs
                );
    }

    protected void generateByteCodeMethods(
            ClassWriter cw,
            DensityFunction root,
            int id
    ) {
        final LinkedList<DensityCompilerPipelineGenerator> copy = generators;
        final String className = configurator.className();
        final String simpleClassName = configurator.classSimpleName();

        for (int i = 0; i < copy.size(); i++) {
            final DensityCompilerPipelineGenerator element = copy.get(i);
            if(element.ignore(this)) continue;

            final MethodVisitor mv = applyVisitorConfiguration(element.generateMethod(this, cw, root));
            mv.visitCode();

            element.applyMethod(
                    this,
                    element.getStructure(this).createContext(this, mv, className),
                    root,
                    className,
                    simpleClassName,
                    id
            );

            mv.visitEnd();
        }
    }

    protected void generateByteCodeFields(
            ClassWriter cw,
            DensityFunction root,
            int id
    ) {
        final LinkedList<DensityCompilerPipelineGenerator> copy = generators;
        final String className = configurator.className();
        final String simpleClassName = configurator.classSimpleName();

        for (int i = 0; i < copy.size(); i++) {
            final DensityCompilerPipelineGenerator element = copy.get(i);

            if(element.ignore(this)) continue;

            element.generateClassField(
                    this,
                    cw,
                    root,
                    className,
                    simpleClassName,
                    id
            );
        }
    }

    protected ClassWriter generateWriter() {
        return generateWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    }

    protected ClassWriter generateWriter(final int flags) {
        ClassWriter cw = new ClassWriter(flags);
        applyWriterHeader(cw);
        applyWriterConfiguration(cw);
        return cw;
    }

    protected void applyWriterHeader(ClassWriter cw) {
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, configurator.className(), null, "java/lang/Object", configurator.interfaces_names());
    }

    protected void applyWriterConfiguration(ClassWriter writer) {
    }

    protected MethodVisitor applyVisitorConfiguration(MethodVisitor mv) {
        return new org.objectweb.asm.util.CheckMethodAdapter(mv);
    }
}

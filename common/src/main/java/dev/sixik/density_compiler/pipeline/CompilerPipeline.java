package dev.sixik.density_compiler.pipeline;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.DensityCompiler;
import dev.sixik.density_compiler.data.ByteCodeGeneratorStructure;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.GeneratorAdapter;

public interface CompilerPipeline {

    default boolean ignore(DensityCompiler compiler) {
        return false;
    }

    default void generateClassField(
            DensityCompiler compiler,
            ClassWriter cw,
            DensityFunction root,
            String className,
            String simpleClassName,
            int id
    ) { }

    GeneratorAdapter generateMethod(
            DensityCompiler compiler,
            ClassWriter cw,
            DensityFunction root,
            String className,
            String simpleClassName,
            int id
    );

    default void prepareMethodBody(
            DensityCompiler compiler,
            DCAsmContext ctx,
            DensityFunction root,
            String className,
            String simpleClassName,
            int id
    ) { }

    default void postPrepareMethodBody(
            DensityCompiler compiler,
            DCAsmContext ctx,
            DensityFunction root,
            String className,
            String simpleClassName,
            int id
    ) { }

    void generateMethodBody(
            DensityCompiler compiler,
            DCAsmContext ctx,
            DensityFunction root,
            String className,
            String simpleClassName,
            int id
    );

    default ByteCodeGeneratorStructure getByteCodeStructure(
            DensityCompiler compiler
    ) {
        return new ByteCodeGeneratorStructure(5, -1);
    }

    @FunctionalInterface
    interface Invoker {

        void handler(
                DensityCompiler compiler,
                DCAsmContext ctx,
                DensityFunction root,
                String className,
                String simpleClassName,
                int id
        );
    }
}

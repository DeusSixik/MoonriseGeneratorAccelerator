package dev.sixik.density_compiler.pipeline;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.DensityCompiler;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static org.objectweb.asm.Opcodes.*;

public class DensityWrapperPipeline implements CompilerPipeline {

    private static final String ROOT_FIELD = "ORIGINAL_ROOT";
    private static final Type DENSITY_FUNCTION_TYPE = Type.getType("Lnet/minecraft/world/level/levelgen/DensityFunction;");

    @Override
    public GeneratorAdapter generateMethod(DensityCompiler compiler, ClassWriter cw, DensityFunction root, String className, String simpleClassName, int id) {
        // 1. Объявляем статическое поле для хранения оригинальной функции
        cw.visitField(ACC_PUBLIC | ACC_STATIC, ROOT_FIELD, DENSITY_FUNCTION_TYPE.getDescriptor(), null, null).visitEnd();

        // 2. Объявляем метод getRootFunction()
        Method method = Method.getMethod("net.minecraft.world.level.levelgen.DensityFunction getRootFunction()");
        return new GeneratorAdapter(ACC_PUBLIC, method, cw.visitMethod(ACC_PUBLIC, method.getName(), method.getDescriptor(), null, null));
    }

    @Override
    public void generateMethodBody(DensityCompiler compiler, DCAsmContext ctx, DensityFunction root, String className, String simpleClassName, int id) {
        GeneratorAdapter ga = (GeneratorAdapter) ctx.mv();

        // 3. Тело метода: return ORIGINAL_ROOT;
        ga.getStatic(Type.getObjectType(className), ROOT_FIELD, DENSITY_FUNCTION_TYPE);
        ga.returnValue();
    }
}

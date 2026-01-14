package dev.sixik.density_compiller.compiler.pipeline.generators_methods;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityMapAllGenerator implements DensityCompilerPipelineGenerator {
    private static final String DENSITY_TYPE = "net/minecraft/world/level/levelgen/DensityFunction";
    private static final String VISITOR_TYPE = "net/minecraft/world/level/levelgen/DensityFunction$Visitor";
    private static final String NOISE_HOLDER_TYPE = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";

    @Override
    public void applyMethod(DensityCompilerPipeline pipeline, PipelineAsmContext ctx, DensityFunction root, String className, String classSimpleName, int id) {
        final var mv = ctx.mv();
        // Используем <Object, Integer>, так как теперь листья могут быть NoiseHolder
        var leavesMap = pipeline.locals.leafToId;

        String descDensity = "L" + DENSITY_TYPE + ";";
        String descVisitor = "L" + VISITOR_TYPE + ";";

        if (leavesMap.isEmpty()) {
            // ... (старый код для пустого случая верен) ...
            mv.visitVarInsn(ALOAD, 1);
            ctx.loadThis();
            mv.visitMethodInsn(INVOKEINTERFACE, VISITOR_TYPE, "apply", "(" + descDensity + ")" + descDensity, true);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(2, 2);
        } else {
            mv.visitVarInsn(ALOAD, 1); // visitor (для финального apply)
            mv.visitTypeInsn(NEW, className);
            mv.visitInsn(DUP); // [visitor, newObj, newObj]

            // 1. Создаем массив Object[]
            ctx.iconst(leavesMap.size());
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

            // 2. Заполняем массив
            for (var entry : leavesMap.entrySet()) {
                Object leafObj = entry.getKey();
                int index = entry.getValue();

                mv.visitInsn(DUP); // Copy arrayRef
                ctx.iconst(index); // Array index

                // Загружаем поле (с его реальным типом!)
                String fieldDesc = pipeline.locals.leafTypes.getOrDefault(index, descDensity);
                ctx.loadThis();
                ctx.getField(PipelineAsmContext.DEFAULT_LEAF_FUNCTION_NAME + "_" + index, fieldDesc);

                // В зависимости от типа объекта выбираем метод визитора
                if (leafObj instanceof DensityFunction) {
                    // field.mapAll(visitor)
                    mv.visitVarInsn(ALOAD, 1); // Load visitor
                    mv.visitMethodInsn(INVOKEINTERFACE, DENSITY_TYPE, "mapAll", "(" + descVisitor + ")" + descDensity, true);
                } else if (isNoiseHolder(leafObj)) {
                    // visitor.visitNoise(field)
                    // Важно: visitNoise находится в Visitor, поэтому сначала грузим visitor, потом field
                    // Но у нас на стеке сейчас [Array, Index, Field]. Нужно поменять местами.

                    mv.visitVarInsn(ALOAD, 1); // Stack: [..., Field, Visitor]
                    mv.visitInsn(SWAP);        // Stack: [..., Visitor, Field]

                    // Вызываем visitor.visitNoise(NoiseHolder) -> NoiseHolder
                    mv.visitMethodInsn(INVOKEINTERFACE, VISITOR_TYPE, "visitNoise",
                            "(L" + NOISE_HOLDER_TYPE + ";)L" + NOISE_HOLDER_TYPE + ";", true);
                } else {
                    // Если это Spline или неизвестный объект — просто оставляем на стеке как есть.
                    // Visitor их не обрабатывает в стандартном интерфейсе.
                }

                mv.visitInsn(AASTORE); // Object array принимает всё
            }

            // 3. Вызываем конструктор ([Ljava/lang/Object;)
            mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", "([Ljava/lang/Object;)V", false);

            // 4. visitor.apply(newInstance)
            mv.visitMethodInsn(INVOKEINTERFACE, VISITOR_TYPE, "apply", "(" + descDensity + ")" + descDensity, true);

            mv.visitInsn(ARETURN);
            mv.visitMaxs(7, 2);
        }
    }

    // Хелпер для проверки типа (так как класс может быть вложенным или недоступным напрямую)
    private boolean isNoiseHolder(Object obj) {
        return obj.getClass().getName().contains("NoiseHolder");
    }

    @Override
    public MethodVisitor generateMethod(DensityCompilerPipeline pipeline, ClassWriter cw, DensityFunction root) {
        return cw.visitMethod(ACC_PUBLIC,
                "mapAll",
                "(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;",
                null,
                null);
    }
}

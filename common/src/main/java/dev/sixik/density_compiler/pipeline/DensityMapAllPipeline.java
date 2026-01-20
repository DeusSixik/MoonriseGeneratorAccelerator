package dev.sixik.density_compiler.pipeline;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.DensityCompiler;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static org.objectweb.asm.Opcodes.*;

public class DensityMapAllPipeline implements CompilerPipeline {

    private static final String DENSITY_TYPE = "net/minecraft/world/level/levelgen/DensityFunction";
    private static final String VISITOR_TYPE = "net/minecraft/world/level/levelgen/DensityFunction$Visitor";
    private static final String NOISE_HOLDER_TYPE = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";

    @Override
    public GeneratorAdapter generateMethod(DensityCompiler compiler, ClassWriter cw, DensityFunction root, String className, String simpleClassName, int id) {
        final String name = "mapAll";
        final String desc = "(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;";

        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        return new GeneratorAdapter(ACC_PUBLIC, new Method(name, desc), mv);
    }

    @Override
    public void generateMethodBody(DensityCompiler compiler, DCAsmContext ctx, DensityFunction root, String className, String simpleClassName, int id) {
        final GeneratorAdapter mv = ctx.mv();
        // Используем <Object, Integer>, так как теперь листья могут быть NoiseHolder
        var leavesMap = compiler.locals.leafToId;

        String descDensity = "L" + DENSITY_TYPE + ";";
        String descVisitor = "L" + VISITOR_TYPE + ";";

        if (leavesMap.isEmpty()) {
            // ... (старый код для пустого случая верен) ...
            mv.visitVarInsn(ALOAD, 1);
            mv.loadThis();
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
                String fieldDesc = compiler.locals.leafTypes.getOrDefault(index, descDensity);
                ctx.mv().loadThis();
                ctx.getField(DCAsmContext.DEFAULT_LEAF_FUNCTION_NAME.apply(entry.getKey()) + "_" + index, fieldDesc);

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
        }
    }

    private boolean isNoiseHolder (Object obj){
        return obj.getClass() == DensityFunctions.HolderHolder.class;
    }
}

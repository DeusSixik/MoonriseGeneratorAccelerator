import dev.sixik.density_compiler.DensityCompiler;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SomeTest {

    @BeforeAll
    static void onStart() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void testFobos() {
        // Глубина 10 даст 1024 ноды, глубина 13-14 может приблизить к лимиту в 64КБ
        final int depth = 10;
        final DensityFunction hugeTree = generateRecursive(depth);

        final var density = new DensityFunctions.BlendDensity(hugeTree);

        // compile() запустит ваш Pipeline и Optimizer
        DensityCompiler.from(density, true).compile();
    }

    /**
     * Генерирует сбалансированное дерево из Ap2 и Constant
     */
    private DensityFunction generateRecursive(int depth) {
        if (depth <= 0) {
            return new DensityFunctions.Constant(Math.random() * 10.0);
        }

        // Чередуем типы операций для разнообразия байт-кода
        DensityFunctions.TwoArgumentSimpleFunction.Type type = (depth % 2 == 0)
                ? DensityFunctions.TwoArgumentSimpleFunction.Type.ADD
                : DensityFunctions.TwoArgumentSimpleFunction.Type.MUL;

        DensityFunction left = generateRecursive(depth - 1);
        DensityFunction right = generateRecursive(depth - 1);

        // В реальности значения min/max должны считаться на основе аргументов,
        // но для теста компилятора можно поставить заглушки.
        return new DensityFunctions.Ap2(
                type,
                left,
                right,
                -1000000.0, // minValue
                1000000.0   // maxValue
        );
    }


}

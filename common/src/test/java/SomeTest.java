import dev.sixik.density_compiler.DensityCompiler;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class SomeTest {

    @BeforeAll
    static void onStart() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void testTheGreatSmasher() {
        // 1. Создаем базовый шум для тестов
        final DensityFunction.NoiseHolder noise = new DensityFunction.NoiseHolder(
                Holder.direct(new NormalNoise.NoiseParameters(33, List.of(1.0, 0.5))), null);

        // 2. Генерируем "Алмазное дерево" (Diamond Graph)
        // Глубина 15 при переиспользовании веток создаст безумное количество связей
        // и заставит Optimizer/Pipeline работать на пределе.
        DensityFunction hugeTree = generateDiamondTree(15, noise);

        // 3. Добавляем финальный "замес" из RangeChoice и Mapped
        final DensityFunction finalBoss = new DensityFunctions.RangeChoice(
                hugeTree,
                -0.5, 0.5,
                new DensityFunctions.Mapped(DensityFunctions.Mapped.Type.ABS, hugeTree, -1.0, 1.0),
                new DensityFunctions.MulOrAdd(DensityFunctions.MulOrAdd.Type.ADD, hugeTree, 0.5, 2.0, 2)
        );

        System.out.println("Starting compilation of the Great Smasher...");
        long start = System.currentTimeMillis();

        // Если здесь не вылетит VerifyError или MethodTooLarge — компилятор готов к продакшену
        var compiled = DensityCompiler.from(finalBoss, true).compile();

        System.out.println("Compiled successfully in " + (System.currentTimeMillis() - start) + "ms");
    }

    /**
     * Генерирует дерево, где каждая нода ссылается на одну и ту же подноду дважды.
     * Это гарантирует, что кэширование (CSE) будет нагружено максимально.
     */
    private DensityFunction generateDiamondTree(int depth, DensityFunction.NoiseHolder noise) {
        DensityFunction current = new DensityFunctions.Noise(noise, 1.0, 1.0);

        for (int i = 0; i < depth; i++) {
            // Чередуем типы для разнообразия инструкций
            if (i % 3 == 0) {
                current = new DensityFunctions.Ap2(
                        DensityFunctions.TwoArgumentSimpleFunction.Type.MUL,
                        current, current, -100.0, 100.0
                );
            } else if (i % 3 == 1) {
                current = new DensityFunctions.Ap2(
                        DensityFunctions.TwoArgumentSimpleFunction.Type.ADD,
                        current, current, -100.0, 100.0
                );
            } else {
                current = new DensityFunctions.Clamp(current, -50.0, 50.0);
            }
        }
        return current;
    }

    @Test
    void testYClampedGradient() {
        final var density = new DensityFunctions.YClampedGradient(
                2,
                5,
                10,
                4
        );


        DensityCompiler.from(density, true).compile();
    }

    @Test
    void testWeirdScaledSampler() {
        final DensityFunction.NoiseHolder noise = new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(33, new ArrayList<>())), null);

        final var density = new DensityFunctions.WeirdScaledSampler(new DensityFunctions.Constant(5), noise, DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE1);

        DensityCompiler.from(density, true).compile();
    }

    @Test
    void testShiftedNoise() {
        final DensityFunction.NoiseHolder noise = new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(33, new ArrayList<>())), null);

        final var density = new DensityFunctions.ShiftedNoise(
                new DensityFunctions.Constant(50),
                new DensityFunctions.Constant(20),
                new DensityFunctions.Constant(67),
                50, 10,
                noise);

        DensityCompiler.from(density, true).compile();
    }

    @Test
    void testShiftA() {
        final DensityFunction.NoiseHolder noise = new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(33, new ArrayList<>())), null);

        final var density = new DensityFunctions.ShiftA(noise);

        DensityCompiler.from(density, true).compile();
    }

    @Test
    void testRangeChoice() {
        final DensityFunction.NoiseHolder noise = new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(33, new ArrayList<>())), null);
        final DensityFunction density = new DensityFunctions.RangeChoice(
            new DensityFunctions.Noise(noise, 5, 10),
                1,
                10,
                new DensityFunctions.Constant(1),
                new DensityFunctions.Constant(4)
        );

        DensityCompiler.from(density, true).compile();
    }

    @Test
    void testNoise() {
        final DensityFunction.NoiseHolder noise = new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(33, new ArrayList<>())), null);

        final DensityFunction density = new DensityFunctions.Noise(noise, 5, 10);
        DensityCompiler.from(density, true).compile();
    }

    @Test
    void testMapped() {
        final DensityFunction density = new DensityFunctions.Mapped(
                DensityFunctions.Mapped.Type.HALF_NEGATIVE,
                new DensityFunctions.Constant(50),
                0,
                20
        );

        DensityCompiler.from(density, true).compile();
    }

    @Test
    void testHugeClampTree() {
        // Глубина 10 создаст дерево, где Clamp будет встречаться на разных уровнях
        final int depth = 10;
        final DensityFunction hugeTree = generateRecursiveWithClamp(depth);

        // Оборачиваем в BlendDensity, чтобы проверить финальный вызов
        final var density = new DensityFunctions.BlendDensity(hugeTree);

        DensityCompiler.from(density, true).compile();
    }

    /**
     * Генерирует дерево, случайно вставляя Clamp между математическими операциями
     */
    private DensityFunction generateRecursiveWithClamp(int depth) {
        if (depth <= 0) {
            return new DensityFunctions.Constant(Math.random() * 20.0);
        }

        DensityFunction node;

        // Каждые 3 уровня вложенности (или случайно) оборачиваем ветку в Clamp
        if (depth % 3 == 0) {
            node = new DensityFunctions.Clamp(
                    generateRecursiveWithClamp(depth - 1),
                    5.0,  // minValue
                    15.0  // maxValue
            );
        } else {
            DensityFunctions.TwoArgumentSimpleFunction.Type type = (depth % 2 == 0)
                    ? DensityFunctions.TwoArgumentSimpleFunction.Type.ADD
                    : DensityFunctions.TwoArgumentSimpleFunction.Type.MUL;

            node = new DensityFunctions.Ap2(
                    type,
                    generateRecursiveWithClamp(depth - 1),
                    generateRecursiveWithClamp(depth - 1),
                    -1000000.0,
                    1000000.0
            );
        }

        return node;
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

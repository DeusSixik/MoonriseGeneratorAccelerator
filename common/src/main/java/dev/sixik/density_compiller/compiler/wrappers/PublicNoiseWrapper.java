package dev.sixik.density_compiller.compiler.wrappers;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public record PublicNoiseWrapper(DensityFunction.NoiseHolder holder) implements DensityFunction {

    @Override
    public double compute(FunctionContext context) {
        // Этот метод НЕ должен вызываться в оптимизированном коде ShiftedNoise.
        // Мы там вручную достаем holder и вызываем holder.getValue(...).
        // Но для безопасности вернем 0.
        return 0.0;
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
        // No-op
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        // Стандартная реализация визитора, на всякий случай
        return visitor.apply(new PublicNoiseWrapper(visitor.visitNoise(this.holder)));
    }

    @Override
    public double minValue() {
        // Делегируем границы шуму (важно для проверок диапазонов)
        return -this.maxValue();
    }

    @Override
    public double maxValue() {
        return this.holder.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // Этот объект существует только в рантайме во время генерации.
        // Сериализовать (сохранять на диск) его не нужно.
        // Возвращаем null или кидаем ошибку.
        return null; // throw new UnsupportedOperationException("Runtime wrapper");
    }
}

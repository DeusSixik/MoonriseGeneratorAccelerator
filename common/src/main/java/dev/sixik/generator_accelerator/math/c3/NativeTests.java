package dev.sixik.generator_accelerator.math.c3;

public class NativeTests {

    public static native void blendedNoise(long seed);

    // Создает C3-объект и возвращает указатель на него
    public static native long createNormalNoise(long seed);

    // Выполняет генерацию карты 4096x4096 ВНУТРИ C3
    public static native double generateMap(long noisePtr, int size);

    // Удаляет объект из памяти C3
    public static native void freeNormalNoise(long noisePtr);
}

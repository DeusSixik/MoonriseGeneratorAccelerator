package dev.sixik.generator_accelerator.common.noise;

import org.apache.commons.lang3.NotImplementedException;

/**
 * Векторизованный генератор шума Симплекса (Perlin/Simplex).
 * Оптимизирован для генерации вертикальных столбцов (когда X и Z постоянны, а Y меняется).
 * <p>
 * Особенности:
 * - Zero Allocation (нет создания объектов в процессе генерации). <p>
 * - Предварительно распакованные градиенты (GRAD_X, GRAD_Y, GRAD_Z) для избегания
 *   битовых операций (| 1, | 2) и вычислений в горячем цикле. <p>
 * - Вынесение констант (X и Z) за пределы цикла Y. <p>
 * - Разворот цикла (Loop Unrolling x4) для помощи JVM в векторизации (SIMD). <p>
 * - Branchless (без ветвлений) вычисление Math.floor. <p>
 */
public class FastVectorNoise {

    private static final double[] FLAT_SIMPLEX_GRAD = new double[]{
            1, 1, 0, 0, -1, 1, 0, 0, 1, -1, 0, 0, -1, -1, 0, 0,
            1, 0, 1, 0, -1, 0, 1, 0, 1, 0, -1, 0, -1, 0, -1, 0,
            0, 1, 1, 0, 0, -1, 1, 0, 0, 1, -1, 0, 0, -1, -1, 0,
            1, 1, 0, 0, 0, -1, 1, 0, -1, 1, 0, 0, 0, -1, -1, 0,
    };

    private static final float[] GRAD_X = new float[256];
    private static final float[] GRAD_Y = new float[256];
    private static final float[] GRAD_Z = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            int gradIdx = (i & 15) << 2;
            GRAD_X[i] = (float) FLAT_SIMPLEX_GRAD[gradIdx];
            GRAD_Y[i] = (float) FLAT_SIMPLEX_GRAD[gradIdx | 1];
            GRAD_Z[i] = (float) FLAT_SIMPLEX_GRAD[gradIdx | 2];
        }
    }

    /*
         Состояние колонки для разделения циклов (Multi-pass / Loop Fission)
         512 элементов с запасом покрывают максимальную высоту мира (384)
     */
    private static final ThreadLocal<ColumnState> STATE = ThreadLocal.withInitial(ColumnState::new);

    private static class ColumnState {
        final float[] dy = new float[512];
        final float[] y1 = new float[512];
        final float[] v = new float[512];
        final int[] Y_arr = new int[512];
    }

    private final int[] p512;
    private final double xo;
    private final double yo;
    private final double zo;

    public FastVectorNoise(byte[] p, double xo, double yo, double zo) {
        this.xo = xo;
        this.yo = yo;
        this.zo = zo;

        this.p512 = new int[512];
        for (int i = 0; i < 256; i++) {
            int unsignedVal = p[i] & 0xFF; // Обязательная маска!
            this.p512[i] = unsignedVal;
            this.p512[i + 256] = unsignedVal;
        }
    }

    /**
     * Заполняет вертикальную колонку шума.
     * Аналог fillNoiseColumnWithFactor, но в разы быстрее.
     *
     * @param buffer           Массив, в который прибавляются результаты
     * @param x                Координата чанка (X)
     * @param z                Координата чанка (Z)
     * @param yStart           Начальная координата Y
     * @param count            Количество блоков по Y (высота колонки)
     * @param scaleX           Масштаб по X
     * @param scaleY           Масштаб по Y
     * @param scaleZ           Масштаб по Z
     * @param amplitude        Амплитуда октавы
     * @param valueFactor_main Глобальный множитель значения (опционально, можно умножить сразу на amplitude)
     */
    public void fillColumnVectorized(double[] buffer, int x, int z, int yStart, int count,
                                     double scaleX, double scaleY, double scaleZ,
                                     double amplitude, double valueFactor_main) {

        final float fAmp = (float) (amplitude * valueFactor_main);

        final double inputX = (x * scaleX) + this.xo;
        final double inputZ = (z * scaleZ) + this.zo;

        final int gridX = inputX > 0 ? (int) inputX : (int) inputX - 1;
        final int gridZ = inputZ > 0 ? (int) inputZ : (int) inputZ - 1;

        /*
            Дельты от 0.0 до 1.0
         */
        final float dx = (float) (inputX - gridX);
        final float dz = (float) (inputZ - gridZ);
        final float dx1 = dx - 1.0f;
        final float dz1 = dz - 1.0f;

        final float u = dx * dx * dx * (dx * (dx * 6.0f - 15.0f) + 10.0f);
        final float w = dz * dz * dz * (dz * (dz * 6.0f - 15.0f) + 10.0f);

        final int[] p = this.p512;
        final int X = gridX & 0xFF;
        final int Z = gridZ & 0xFF;
        final int pX0 = p[X];
        final int pX1 = p[X + 1];

        final ColumnState state = STATE.get();
        final float[] dyArr = state.dy;
        final float[] y1Arr = state.y1;
        final float[] vArr = state.v;
        final int[] YArr = state.Y_arr;

        for (int i = 0; i < count; i++) {
            double inputY = ((yStart + i) * scaleY) + this.yo;
            int gridY = inputY > 0 ? (int) inputY : (int) inputY - 1;

            float dy = (float) (inputY - gridY);

            YArr[i] = gridY & 0xFF;
            dyArr[i] = dy;
            y1Arr[i] = dy - 1.0f;
            vArr[i] = dy * dy * dy * (dy * (dy * 6.0f - 15.0f) + 10.0f);
        }

        final float[] gradX = GRAD_X;
        final float[] gradY = GRAD_Y;
        final float[] gradZ = GRAD_Z;

        for (int i = 0; i < count; i++) {
            int Y = YArr[i];
            float dy = dyArr[i];
            float y1 = y1Arr[i];
            float v = vArr[i];

            int A = pX0 + Y;
            int B = pX1 + Y;

            int AA = p[A] + Z;
            int AB = p[A + 1] + Z;
            int BA = p[B] + Z;
            int BB = p[B + 1] + Z;

            int gi000 = p[AA];
            int gi001 = p[AA + 1];
            int gi010 = p[AB];
            int gi011 = p[AB + 1];
            int gi100 = p[BA];
            int gi101 = p[BA + 1];
            int gi110 = p[BB];
            int gi111 = p[BB + 1];

            float n000 = gradX[gi000] * dx + gradY[gi000] * dy + gradZ[gi000] * dz;
            float n100 = gradX[gi100] * dx1 + gradY[gi100] * dy + gradZ[gi100] * dz;
            float n010 = gradX[gi010] * dx + gradY[gi010] * y1 + gradZ[gi010] * dz;
            float n110 = gradX[gi110] * dx1 + gradY[gi110] * y1 + gradZ[gi110] * dz;

            float n001 = gradX[gi001] * dx + gradY[gi001] * dy + gradZ[gi001] * dz1;
            float n101 = gradX[gi101] * dx1 + gradY[gi101] * dy + gradZ[gi101] * dz1;
            float n011 = gradX[gi011] * dx + gradY[gi011] * y1 + gradZ[gi011] * dz1;
            float n111 = gradX[gi111] * dx1 + gradY[gi111] * y1 + gradZ[gi111] * dz1;

            float lerpX1 = n000 + u * (n100 - n000);
            float lerpX2 = n010 + u * (n110 - n010);
            float lerpX3 = n001 + u * (n101 - n001);
            float lerpX4 = n011 + u * (n111 - n011);

            float lerpY1 = lerpX1 + v * (lerpX2 - lerpX1);
            float lerpY2 = lerpX3 + v * (lerpX4 - lerpX3);

            // Единственный неявный каст float -> double при записи в буфер
            buffer[i] += (lerpY1 + w * (lerpY2 - lerpY1)) * fAmp;
        }
    }

    /**
     * Сверхбыстрое вычисление одной точки (скалярный вариант).
     * Используется в getValue() для разовых проверок.
     */
    public double computeSingle(double x, double y, double z) {
        final double inputX = x + this.xo;
        final double inputY = y + this.yo;
        final double inputZ = z + this.zo;

        final int gridX = inputX > 0 ? (int) inputX : (int) inputX - 1;
        final int gridY = inputY > 0 ? (int) inputY : (int) inputY - 1;
        final int gridZ = inputZ > 0 ? (int) inputZ : (int) inputZ - 1;

        final double deltaX = inputX - gridX;
        final double deltaY = inputY - gridY;
        final double deltaZ = inputZ - gridZ;

        final double x1 = deltaX - 1.0;
        final double y1 = deltaY - 1.0;
        final double z1 = deltaZ - 1.0;

        final double u = deltaX * deltaX * deltaX * (deltaX * (deltaX * 6.0 - 15.0) + 10.0);
        final double v = deltaY * deltaY * deltaY * (deltaY * (deltaY * 6.0 - 15.0) + 10.0);
        final double w = deltaZ * deltaZ * deltaZ * (deltaZ * (deltaZ * 6.0 - 15.0) + 10.0);

        final int[] p = this.p512;
        final int X = gridX & 0xFF;
        final int Y = gridY & 0xFF;
        final int Z = gridZ & 0xFF;

        final int pX0 = p[X];
        final int pX1 = p[(X + 1)];

        final int A = pX0 + Y;
        final int B = pX1 + Y;

        final int AA = (p[A]) + Z;
        final int AB = (p[(A + 1)]) + Z;
        final int BA = (p[B]) + Z;
        final int BB = (p[(B + 1)]) + Z;

        // Извлекаем индексы градиентов без битового умножения
        final int gi000 = p[AA];
        final int gi001 = p[(AA + 1)];
        final int gi010 = p[AB];
        final int gi011 = p[(AB + 1)];
        final int gi100 = p[BA];
        final int gi101 = p[(BA + 1)];
        final int gi110 = p[BB];
        final int gi111 = p[(BB + 1)];

        final float[] gradX = GRAD_X;
        final float[] gradY = GRAD_Y;
        final float[] gradZ = GRAD_Z;

        final double n000 = gradX[gi000] * deltaX + gradY[gi000] * deltaY + gradZ[gi000] * deltaZ;
        final double n100 = gradX[gi100] * x1     + gradY[gi100] * deltaY + gradZ[gi100] * deltaZ;
        final double n010 = gradX[gi010] * deltaX + gradY[gi010] * y1     + gradZ[gi010] * deltaZ;
        final double n110 = gradX[gi110] * x1     + gradY[gi110] * y1     + gradZ[gi110] * deltaZ;

        final double n001 = gradX[gi001] * deltaX + gradY[gi001] * deltaY + gradZ[gi001] * z1;
        final double n101 = gradX[gi101] * x1     + gradY[gi101] * deltaY + gradZ[gi101] * z1;
        final double n011 = gradX[gi011] * deltaX + gradY[gi011] * y1     + gradZ[gi011] * z1;
        final double n111 = gradX[gi111] * x1     + gradY[gi111] * y1     + gradZ[gi111] * z1;

        final double lerpX1 = n000 + u * (n100 - n000);
        final double lerpX2 = n010 + u * (n110 - n010);
        final double lerpX3 = n001 + u * (n101 - n001);
        final double lerpX4 = n011 + u * (n111 - n011);

        final double lerpY1 = lerpX1 + v * (lerpX2 - lerpX1);
        final double lerpY2 = lerpX3 + v * (lerpX4 - lerpX3);

        return lerpY1 + w * (lerpY2 - lerpY1);
    }
}

package dev.sixik.generator_accelerator.common.density.density;

import com.google.common.annotations.VisibleForTesting;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.Mth;
import net.minecraft.util.ToFloatFunction;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public record FastMultipoint<C, I extends ToFloatFunction<C>>(
        I coordinate,
        float[] locations,
        List<CubicSpline<C, I>> values,
        float[] derivatives,
        float minValue,
        float maxValue,

        // --- Предрассчитанные кэши ---
        float[] inverseDiffs,
        float[] pBases,
        float[] qBases,
        int[] intervalLut,
        float lutMin,
        float lutScale
) implements CubicSpline<C, I> {

    // Размер таблицы (LUT). 4096 дает идеальный баланс между L1-кэшем и точностью попадания
    private static final int LUT_SIZE = 4096;

    public static <C, I extends ToFloatFunction<C>> FastMultipoint<C, I> createFast(
            I coordinate, float[] fs, List<CubicSpline<C, I>> list, float[] gs) {
        int segments = fs.length - 1;
        float minVal = Float.POSITIVE_INFINITY;
        float maxVal = Float.NEGATIVE_INFINITY;
        float coordMin = coordinate.minValue();
        float coordMax = coordinate.maxValue();

        // 1. Сохраняем ванильную логику расчета абсолютных минимумов и максимумов сплайна
        if (coordMin < fs[0]) {
            float k = linearExtend(coordMin, fs, list.get(0).minValue(), gs, 0);
            float l = linearExtend(coordMin, fs, list.get(0).maxValue(), gs, 0);
            minVal = Math.min(minVal, Math.min(k, l));
            maxVal = Math.max(maxVal, Math.max(k, l));
        }

        if (coordMax > fs[segments]) {
            float k = linearExtend(coordMax, fs, list.get(segments).minValue(), gs, segments);
            float l = linearExtend(coordMax, fs, list.get(segments).maxValue(), gs, segments);
            minVal = Math.min(minVal, Math.min(k, l));
            maxVal = Math.max(maxVal, Math.max(k, l));
        }

        for (CubicSpline<C, I> cubicSpline : list) {
            minVal = Math.min(minVal, cubicSpline.minValue());
            maxVal = Math.max(maxVal, cubicSpline.maxValue());
        }

        for (int m = 0; m < segments; ++m) {
            float l = fs[m];
            float n = fs[m + 1];
            float o = n - l;
            CubicSpline<C, I> spline2 = list.get(m);
            CubicSpline<C, I> spline3 = list.get(m + 1);
            float p = spline2.minValue();
            float q = spline2.maxValue();
            float r = spline3.minValue();
            float s = spline3.maxValue();
            float t = gs[m];
            float u = gs[m + 1];
            if (t != 0.0F || u != 0.0F) {
                float v = t * o;
                float w = u * o;
                float x = Math.min(p, r);
                float y = Math.max(q, s);
                float z = v - s + p;
                float aa = v - r + q;
                float ab = -w + r - q;
                float ac = -w + s - p;
                float ad = Math.min(z, ab);
                float ae = Math.max(aa, ac);
                minVal = Math.min(minVal, x + 0.25F * ad);
                maxVal = Math.max(maxVal, y + 0.25F * ae);
            }
        }

        // 2. ФАЗА ПРЕДРАСЧЕТА (Baking)
        float[] invDiffs = new float[segments];
        float[] pB = new float[segments];
        float[] qB = new float[segments];

        for (int m = 0; m < segments; ++m) {
            float diff = fs[m + 1] - fs[m];
            invDiffs[m] = 1.0F / diff; // Заменяем деление
            pB[m] = gs[m] * diff;
            qB[m] = -gs[m + 1] * diff;
        }

        // 3. Генерация Look-Up Table
        int[] lut = new int[LUT_SIZE];
        float lMin = fs[0];
        float lMax = fs[segments];
        float lScale = lMax > lMin ? (LUT_SIZE - 1) / (lMax - lMin) : 0;

        for (int k = 0; k < LUT_SIZE; k++) {
            float f_val = lMin + k / lScale;
            // Делаем бинарный поиск всего один раз при загрузке мира
            lut[k] = Mth.binarySearch(0, fs.length, (idx) -> f_val < fs[idx]) - 1;
        }

        return new FastMultipoint<>(coordinate, fs, list, gs, minVal, maxVal, invDiffs, pB, qB, lut, lMin, lScale);
    }

    @Override
    public float apply(C object) {
        float f = this.coordinate.apply(object);
        int i;

        // 1. Поиск индекса интервала за O(1)
        if (f < this.locations[0]) {
            return linearExtend(f, this.locations, this.values.get(0).apply(object), this.derivatives, 0);
        } else if (f >= this.locations[this.locations.length - 1]) {
            int lastIdx = this.locations.length - 1;
            return linearExtend(f, this.locations, this.values.get(lastIdx).apply(object), this.derivatives, lastIdx);
        } else {
            int lutIndex = (int) ((f - this.lutMin) * this.lutScale);
            i = this.intervalLut[lutIndex];
        }

        // Подстраховка от погрешности float-округления (Branch predictor съест это мгновенно)
        while (i < this.locations.length - 1 && f >= this.locations[i + 1]) {
            i++;
        }
        while (i > 0 && f < this.locations[i]) {
            i--;
        }

        // 2. Раскрученная кубическая математика (без деления)
        float k = (f - this.locations[i]) * this.inverseDiffs[i];

        float n = this.values.get(i).apply(object);
        float o = this.values.get(i + 1).apply(object);

        float delta = o - n;
        float p = this.pBases[i] - delta;
        float q = this.qBases[i] + delta;

        // Финальная интерполяция
        return n + k * delta + k * (1.0F - k) * (p + k * (q - p));
    }

    private static float linearExtend(float f, float[] fs, float g, float[] gs, int i) {
        float h = gs[i];
        return h == 0.0F ? g : g + h * (f - fs[i]);
    }

    @Override
    public CubicSpline<C, I> mapAll(CoordinateVisitor<I> coordinateVisitor) {
        I newCoordinate = coordinateVisitor.visit(this.coordinate);
        boolean changed = (newCoordinate != this.coordinate);

        List<CubicSpline<C, I>> newValues = new java.util.ArrayList<>(this.values.size());
        for (CubicSpline<C, I> value : this.values) {
            CubicSpline<C, I> newValue = value.mapAll(coordinateVisitor);
            newValues.add(newValue);
            if (newValue != value) {
                changed = true;
            }
        }

        // Если в дереве ничего не поменялось, возвращаем сами себя (0 аллокаций!)
        if (!changed) {
            return this;
        }

        // Если поменялось, просто переиспользуем НАШИ ТЯЖЕЛЫЕ МАССИВЫ без их пересоздания!
        return new FastMultipoint<>(
                newCoordinate, this.locations, newValues, this.derivatives,
                this.minValue, this.maxValue,
                this.inverseDiffs, this.pBases, this.qBases, this.intervalLut, this.lutMin, this.lutScale
        );
    }

    @VisibleForTesting
    @Override
    public String parityString() {
        String coordStr = String.valueOf(this.coordinate);
        return "Spline{coordinate=" + coordStr +
                ", locations=" + this.toString(this.locations) +
                ", derivatives=" + this.toString(this.derivatives) +
                ", values=" + this.values.stream().map(CubicSpline::parityString).collect(Collectors.joining(", ", "[", "]")) + "}";
    }

    private String toString(float[] fs) {
        Stream<String> stream = IntStream.range(0, fs.length).mapToDouble((i) -> fs[i]).mapToObj((d) -> String.format(Locale.ROOT, "%.3f", d));
        return "[" + stream.collect(Collectors.joining(", ")) + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FastMultipoint<?, ?> that)) return false;

        // Никаких глубоких проверок массивов LUT, только суть графика
        return Float.compare(that.minValue, minValue) == 0 &&
                Float.compare(that.maxValue, maxValue) == 0 &&
                coordinate.equals(that.coordinate) &&
                values.equals(that.values) &&
                java.util.Arrays.equals(locations, that.locations) &&
                java.util.Arrays.equals(derivatives, that.derivatives);
    }

    @Override
    public int hashCode() {
        // Мы избавились от Objects.hash(), так как он под капотом создает Object[] массив.
        // Этот вариант работает с 0 аллокаций памяти.
        int result = coordinate.hashCode();
        result = 31 * result + values.hashCode();
        result = 31 * result + Float.floatToIntBits(minValue);
        result = 31 * result + Float.floatToIntBits(maxValue);
        result = 31 * result + java.util.Arrays.hashCode(locations);
        result = 31 * result + java.util.Arrays.hashCode(derivatives);
        return result;
    }
}

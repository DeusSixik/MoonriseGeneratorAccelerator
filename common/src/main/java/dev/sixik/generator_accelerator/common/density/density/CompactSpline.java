package dev.sixik.generator_accelerator.common.density.density;

import com.google.common.annotations.VisibleForTesting;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.ToFloatFunction;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public record CompactSpline<C, I extends ToFloatFunction<C>>(
        I coordinate,
        float[] locations,
        CubicSpline<C, I>[] values, // Плоский массив вместо List
        float[] derivatives,
        float minValue,
        float maxValue,

        // Предрассчитанная математика для избавления от деления
        float[] inverseDiffs,
        float[] pBases,
        float[] qBases
) implements CubicSpline<C, I> {

    @SuppressWarnings("unchecked")
    public static <C, I extends ToFloatFunction<C>> CompactSpline<C, I> createCompact(
            I coordinate, float[] fs, List<CubicSpline<C, I>> list, float[] gs) {

        int segments = fs.length - 1;
        float minVal = Float.POSITIVE_INFINITY;
        float maxVal = Float.NEGATIVE_INFINITY;
        float coordMin = coordinate.minValue();
        float coordMax = coordinate.maxValue();

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

        float[] invDiffs = new float[segments];
        float[] pB = new float[segments];
        float[] qB = new float[segments];

        for (int m = 0; m < segments; ++m) {
            float l = fs[m];
            float n = fs[m + 1];
            float diff = n - l;

            invDiffs[m] = 1.0F / diff; // 1.0 / (h - g)
            pB[m] = gs[m] * diff;      // l * (h - g)
            qB[m] = -gs[m + 1] * diff; // -m * (h - g)

            CubicSpline<C, I> spline2 = list.get(m);
            CubicSpline<C, I> spline3 = list.get(m + 1);
            float p = spline2.minValue();
            float q = spline2.maxValue();
            float r = spline3.minValue();
            float s = spline3.maxValue();
            float t = gs[m];
            float u = gs[m + 1];

            if (t != 0.0F || u != 0.0F) {
                float v = t * diff;
                float w = u * diff;
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

        // Превращаем List в чистый массив
        CubicSpline<C, I>[] valuesArray = list.toArray(new CubicSpline[0]);

        return new CompactSpline<>(coordinate, fs, valuesArray, gs, minVal, maxVal, invDiffs, pB, qB);
    }

    @Override
    public float apply(C object) {
        float f = this.coordinate.apply(object);

        float[] locs = this.locations;
        int len = locs.length - 1;

        // Линейный поиск (Идеально для L1 кэша и предсказателя ветвлений)
        int i = 0;
        while (i < len && f >= locs[i + 1]) {
            i++;
        }

        // Обработка выхода за границы
        if (i == 0 && f < locs[0]) {
            return linearExtend(f, locs, this.values[0].apply(object), this.derivatives, 0);
        } else if (i == len && f >= locs[len]) {
            return linearExtend(f, locs, this.values[len].apply(object), this.derivatives, len);
        }

        // Кубическая интерполяция без деления
        float k = (f - locs[i]) * this.inverseDiffs[i];

        float n = this.values[i].apply(object);
        float o = this.values[i + 1].apply(object);

        float delta = o - n;
        float p = this.pBases[i] - delta;
        float q = this.qBases[i] + delta;

        return n + k * delta + k * (1.0F - k) * (p + k * (q - p));
    }

    private static float linearExtend(float f, float[] fs, float g, float[] gs, int i) {
        float h = gs[i];
        return h == 0.0F ? g : g + h * (f - fs[i]);
    }

    @SuppressWarnings("unchecked")
    @Override
    public CubicSpline<C, I> mapAll(CoordinateVisitor<I> coordinateVisitor) {
        I newCoordinate = coordinateVisitor.visit(this.coordinate);
        boolean changed = (newCoordinate != this.coordinate);

        CubicSpline<C, I>[] newValues = Arrays.copyOf(this.values, this.values.length);
        for (int i = 0; i < this.values.length; i++) {
            CubicSpline<C, I> newValue = this.values[i].mapAll(coordinateVisitor);
            newValues[i] = newValue;
            if (newValue != this.values[i]) {
                changed = true;
            }
        }

        // Возвращаем себя, если изменений нет (Zero-Allocation)
        if (!changed) {
            return this;
        }

        // Переиспользуем математические массивы без их пересчета
        return new CompactSpline<>(
                newCoordinate, this.locations, newValues, this.derivatives,
                this.minValue, this.maxValue,
                this.inverseDiffs, this.pBases, this.qBases
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompactSpline<?, ?> that)) return false;

        // Сравниваем только семантические данные, игнорируя кэши (inverseDiffs и т.д.)
        return Float.compare(that.minValue, minValue) == 0 &&
                Float.compare(that.maxValue, maxValue) == 0 &&
                coordinate.equals(that.coordinate) &&
                Arrays.equals(locations, that.locations) &&
                Arrays.equals(derivatives, that.derivatives) &&
                Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        // Избегаем Objects.hash() для предотвращения создания массива Object[]
        int result = coordinate.hashCode();
        result = 31 * result + Arrays.hashCode(values);
        result = 31 * result + Float.floatToIntBits(minValue);
        result = 31 * result + Float.floatToIntBits(maxValue);
        result = 31 * result + Arrays.hashCode(locations);
        result = 31 * result + Arrays.hashCode(derivatives);
        return result;
    }

    @VisibleForTesting
    @Override
    public String parityString() {
        String coordStr = String.valueOf(this.coordinate);
        return "Spline{coordinate=" + coordStr +
                ", locations=" + this.toString(this.locations) +
                ", derivatives=" + this.toString(this.derivatives) +
                ", values=" + Arrays.stream(this.values).map(CubicSpline::parityString).collect(Collectors.joining(", ", "[", "]")) + "}";
    }

    private String toString(float[] fs) {
        Stream<String> stream = IntStream.range(0, fs.length).mapToDouble((i) -> fs[i]).mapToObj((d) -> String.format(Locale.ROOT, "%.3f", d));
        return "[" + stream.collect(Collectors.joining(", ")) + "]";
    }
}

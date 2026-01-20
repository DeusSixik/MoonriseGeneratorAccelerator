package dev.sixik.density_compiler.pipeline;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.DensityCompiler;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.GeneratorAdapter;

import static dev.sixik.density_compiler.utils.DensityCompilerUtils.*;

public class DensityTreeOptimizerPipeline implements CompilerPipeline {

    private static final DensityFunction ZERO = new DensityFunctions.Constant(0.0D);

    @Override
    public boolean ignore(DensityCompiler compiler) {
        return true;
    }

    @Override
    public GeneratorAdapter generateMethod(DensityCompiler compiler, ClassWriter cw, DensityFunction root, String className, String simpleClassName, int id) {
        throw new UnsupportedOperationException("Only iteration by Node Tree!");
    }

    @Override
    public void generateMethodBody(DensityCompiler compiler, DCAsmContext ctx, DensityFunction root, String className, String simpleClassName, int id) {
        throw new UnsupportedOperationException("Only iteration by Node Tree!");
    }

    private boolean hasChanged = false;

    @Override
    public DensityFunction manageFunction(DensityCompiler compiler, DensityFunction inNode, String className, String simpleClassName, int id) {
        DensityFunction current = inNode;

        do {
            hasChanged = false;
            current = optimizeRecursive(current);
        } while (hasChanged);

        return current;
    }

    private DensityFunction optimizeRecursive(DensityFunction node) {
        if (node instanceof DensityFunctions.Ap2 ap2) {
            return optimizeAp2(ap2);
        }

        if (node instanceof DensityFunctions.MulOrAdd mulOrAdd) {
            return optimizeMulOrAdd(mulOrAdd);
        }

        if(node instanceof DensityFunctions.Mapped mapped) {
            return optimizeMapped(mapped);
        }

        if(node instanceof DensityFunctions.Clamp clamp) {
            return optimizeClamp(clamp);
        }

        if(node instanceof DensityFunctions.BlendDensity blendDensity) {
            return optimizeBlendDensity(blendDensity);
        }

        if(node instanceof DensityFunctions.RangeChoice rangeChoice) {
            return optimizeRangeChoice(rangeChoice);
        }

        if(node instanceof DensityFunctions.ShiftedNoise noise) {
            return optimizeShiftedNoise(noise);
        }

        if(node instanceof DensityFunctions.WeirdScaledSampler weirdScaledSampler) {
            return optimizeWeirdScaledSampler(weirdScaledSampler);
        }

        if (node instanceof DensityFunctions.Marker marker) {
            return optimizeMarker(marker);
        }

        return node;
    }

    private DensityFunction optimizeMarker(DensityFunctions.Marker marker) {
        DensityFunction wrapped = optimizeRecursive(marker.wrapped());

        if (wrapped instanceof DensityFunctions.Constant) {
            this.hasChanged = true;
            return wrapped;
        }

        if (marker.type() == DensityFunctions.Marker.Type.Interpolated) {
            this.hasChanged = true;
            return wrapped;
        }

//        // 4. Удаление кешей (Опционально, но рекомендуется для простых деревьев)
//        // Если ты Senior-разработчик и уверен в производительности своего байт-кода:
//        // Мы убираем маркер, чтобы заинлайнить вычисления прямо в цикл fillArray.
//        if (isCacheMarker(marker.type())) {
//            this.hasChanged = true;
//            return wrapped;
//        }

        if (wrapped != marker.wrapped()) {
            this.hasChanged = true;
            return new DensityFunctions.Marker(marker.type(), wrapped);
        }

        return marker;
    }

    private boolean isCacheMarker(DensityFunctions.Marker.Type type) {
        return type == DensityFunctions.Marker.Type.FlatCache ||
                type == DensityFunctions.Marker.Type.Cache2D ||
                type == DensityFunctions.Marker.Type.CacheOnce ||
                type == DensityFunctions.Marker.Type.CacheAllInCell;
    }

    private DensityFunction optimizeWeirdScaledSampler(DensityFunctions.WeirdScaledSampler node) {
        DensityFunction input = optimizeRecursive(node.input());

        if (isConst(input)) {
            double val = getConst(input);
            double e = node.rarityValueMapper().mapper.get(val);

            // Если mapper вернул 0, то по логике transform результат будет 0
            if (e == 0.0) {
                this.hasChanged = true;
                return ZERO;
            }
        }

        if (input != node.input()) {
            this.hasChanged = true;
            return new DensityFunctions.WeirdScaledSampler(input, node.noise(), node.rarityValueMapper());
        }

        return node;
    }

    private DensityFunction optimizeShiftedNoise(DensityFunctions.ShiftedNoise node) {
        DensityFunction sx = optimizeRecursive(node.shiftX());
        DensityFunction sy = optimizeRecursive(node.shiftY());
        DensityFunction sz = optimizeRecursive(node.shiftZ());

        if (sx != node.shiftX() || sy != node.shiftY() || sz != node.shiftZ()) {
            this.hasChanged = true;
            return new DensityFunctions.ShiftedNoise(sx, sy, sz, node.xzScale(), node.yScale(), node.noise());
        }

        return node;
    }

    private DensityFunction optimizeRangeChoice(DensityFunctions.RangeChoice node) {

        DensityFunction input = optimizeRecursive(node.input());
        DensityFunction whenInRange = optimizeRecursive(node.whenInRange());
        DensityFunction whenOutOfRange = optimizeRecursive(node.whenOutOfRange());

        double min = node.minInclusive();
        double max = node.maxExclusive();

        double inMin = input.minValue();
        double inMax = input.maxValue();

        if (input instanceof DensityFunctions.Constant c) {
            double val = c.value();
            this.hasChanged = true;
            return (val >= min && val < max) ? whenInRange : whenOutOfRange;
        }

        if (inMin >= min && inMax < max) {
            this.hasChanged = true;
            return whenInRange;
        }

        if (inMax < min || inMin >= max) {
            this.hasChanged = true;
            return whenOutOfRange;
        }

        if (whenInRange.equals(whenOutOfRange)) {
            this.hasChanged = true;
            return whenInRange;
        }

        if (input != node.input() || whenInRange != node.whenInRange() || whenOutOfRange != node.whenOutOfRange()) {
            this.hasChanged = true;
            return new DensityFunctions.RangeChoice(input, min, max, whenInRange, whenOutOfRange);
        }

        return node;
    }

    private DensityFunction optimizeBlendDensity(DensityFunctions.BlendDensity blendDensity) {
        DensityFunction input = optimizeRecursive(blendDensity.input());

        if(input != blendDensity.input()) {
            this.hasChanged = true;
            return new DensityFunctions.BlendDensity(input);
        }

        return blendDensity;
    }

    private DensityFunction optimizeClamp(DensityFunctions.Clamp clamp) {
        DensityFunction input = optimizeRecursive(clamp.input());
        double cMin = clamp.minValue();
        double cMax = clamp.maxValue();

        double inMin = input.minValue();
        double inMax = input.maxValue();

        if (input instanceof DensityFunctions.Constant c) {
            this.hasChanged = true;
            return new DensityFunctions.Constant(Mth.clamp(c.value(), cMin, cMax));
        }

        if (inMax <= cMin) {
            this.hasChanged = true;
            return new DensityFunctions.Constant(cMin);
        }

        if (inMin >= cMax) {
            this.hasChanged = true;
            return new DensityFunctions.Constant(cMax);
        }

        if (inMin >= cMin && inMax <= cMax) {
            this.hasChanged = true;
            return input;
        }

        if (input != clamp.input()) {
            this.hasChanged = true;
            return new DensityFunctions.Clamp(input, cMin, cMax);
        }

        return clamp;
    }

    private DensityFunction optimizeMapped(DensityFunctions.Mapped mapped) {

        DensityFunction arg1 = optimizeRecursive(mapped.input());

        if(isConst(arg1)) {
            double v1 = getConst(arg1);

            double e = Mth.clamp(v1, -1.0F, 1.0F);

            double result = switch (mapped.type()) {
                case ABS -> Math.abs(v1);
                case SQUARE -> v1 * v1;
                case CUBE -> v1 * v1 * v1;
                case HALF_NEGATIVE -> v1 > 0.0D ? v1 : v1 * 0.5D;
                case QUARTER_NEGATIVE -> v1 > 0.0D ? v1 : v1 * 0.25D;
                case SQUEEZE -> e / 2.0D - e * e * e / 24.0D;
            };

            this.hasChanged = true;
            return new DensityFunctions.Constant(result);
        }

        if(arg1 != mapped.input()) {
            this.hasChanged = true;
            return new DensityFunctions.Mapped(mapped.type(), arg1, mapped.minValue(), mapped.maxValue());
        }

        return mapped;
    }

    private DensityFunction optimizeAp2(DensityFunctions.Ap2 node) {
        // ВАЖНО: Сначала оптимизируем детей (Bottom-Up подход)
        // Если мы не спустимся вниз, мы не сможем свернуть MAX(MAX(5,3), 4)
        DensityFunction arg1 = optimizeRecursive(node.argument1());
        DensityFunction arg2 = optimizeRecursive(node.argument2());

        var type = node.type();

        // --- ЛОГИКА 1: Constant Folding (Свертка констант) ---

        if (isConst(arg1) && isConst(arg2)) {
            double v1 = getConst(arg1);
            double v2 = getConst(arg2);
            double result = switch (type) {
                case ADD -> v1 + v2;
                case MUL -> v1 * v2;
                case MIN -> Math.min(v1, v2);
                case MAX -> Math.max(v1, v2);
            };

            this.hasChanged = true; // Мы изменили структуру!
            return new DensityFunctions.Constant(result);
        }

        // --- ЛОГИКА 2: Identity & Zero (Нейтральные элементы) ---
        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
            // x + 0 = x
            if (isConst(arg1, 0)) { this.hasChanged = true; return arg2; }
            if (isConst(arg2, 0)) { this.hasChanged = true; return arg1; }
        }

        if (type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
            // x * 1 = x
            if (isConst(arg1, 1)) { this.hasChanged = true; return arg2; }
            if (isConst(arg2, 1)) { this.hasChanged = true; return arg1; }
            // x * 0 = 0
            if (isConst(arg1, 0)) { this.hasChanged = true; return ZERO; }
            if (isConst(arg2, 0)) { this.hasChanged = true; return ZERO; }
        }

        // --- ЛОГИКА 3: Reconstruct (Пересборка) ---
        // Если аргументы изменились (были оптимизированы внутри), но сама нода не свернулась в константу,
        // нам нужно вернуть новый Ap2 с новыми аргументами.
        if (arg1 != node.argument1() || arg2 != node.argument2()) {
            this.hasChanged = true;
            // Используем фабричный метод create, он сам может превратить Ap2 в MulOrAdd при необходимости
            return DensityFunctions.TwoArgumentSimpleFunction.create(type, arg1, arg2);
        }

        return node;
    }

    private DensityFunction optimizeMulOrAdd(DensityFunctions.MulOrAdd node) {
        // MulOrAdd - это уже оптимизированная нода (input * arg + 0) или (input + arg).
        // Но input мог измениться.
        DensityFunction input = optimizeRecursive(node.input());

        // Пример: (Constant(5) * 2) -> Constant(10)
        if (isConst(input)) {
            double val = getConst(input);
            double arg = node.argument();
            double result;

            if (node.specificType() == DensityFunctions.MulOrAdd.Type.MUL) {
                result = val * arg;
            } else {
                result = val + arg;
            }

            this.hasChanged = true;
            return new DensityFunctions.Constant(result);
        }

        // Если input изменился, пересобираем ноду
        if (input != node.input()) {
            this.hasChanged = true;
            return new DensityFunctions.MulOrAdd(node.specificType(), input, node.minValue(), node.maxValue(), node.argument());
        }

        return node;
    }
}

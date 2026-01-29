package dev.sixik.density_interpreter;

import dev.sixik.density_interpreter.utils.NoiseSerializer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.ArrayList;
import java.util.List;

import static dev.sixik.density_interpreter.DensityOpcode.*;

public class DensityCompiler {

    private static NoiseSerializer noiseSerializer = new NoiseSerializer();

    public static final class Data {
        public int lastCachedId = 0;

        public final List<Integer> program = new ArrayList<>();
        public final List<Double> constants = new ArrayList<>();

        public Data program(DensityOpcode opcode) {
            program.add(opcode.ordinal());
            return this;
        }

        public Data program(int command) {
            program.add(command);
            return this;
        }

        /**
         * Сохраняет константу в пул и возвращает её индекс.
         * НЕ генерирует инструкций.
         */
        public int storeConst(double value) {
            int idx = constants.size();
            constants.add(value);
            return idx;
        }

        /**
         * Генерирует инструкцию OP_CONST для загрузки значения в регистр.
         */
        public void emitConstInstruction(double value, int targetReg) {
            int idx = storeConst(value);
            program(OP_CONST);
            program(idx);
            program(targetReg);
        }

        public int pSize() {
            return program.size();
        }

        public int cSize() {
            return constants.size();
        }

        public Data end() {
            program.add(OP_FINISH.ordinal());
            return this;
        }

        public int getNextCacheId() {
            final int c = lastCachedId;
            lastCachedId++;
            return c;
        }

        @Override
        public String toString() {
            return "Data{" +
                    "program=" + program +
                    ", constants=" + constants +
                    '}';
        }
    }

    public static Data generateCommands(DensityFunction root) {
        Data data = new Data();
        generateCommands(data, root, 0);
        return data.end();
    }

    private static void generateCommands(Data data, DensityFunction function, int targetReg) {

        if(
                function instanceof DensityFunctions.BlendAlpha
                || function instanceof DensityFunctions.BlendOffset
                || function instanceof DensityFunctions.BeardifierMarker
                || function instanceof DensityFunctions.Constant
        ) {
            data.emitConstInstruction(function.maxValue(), targetReg);
            return;
        }

        if(function instanceof DensityFunctions.WeirdScaledSampler node) {
            int regA = targetReg + 1;
            generateCommands(data, node.input(), regA);

            final NormalNoise noise = node.noise().noise();

            int mapperId = node.rarityValueMapper().ordinal();

            data.program(OP_WEIRD_SAMPLER);
            data.program(regA);

            {
                int hasNoise = data.storeConst(noise != null ? 1 : 0);
                data.program(hasNoise);

                if (hasNoise == 1)
                {
                    data.program(OP_NORMAL_NOISE);
                    data.program(noiseSerializer.findOrSerialize(noise));
                }
                else
                {
                    data.program(data.storeConst(0.0));
                }
            }

            data.program(mapperId);
            data.program(targetReg);
            return;
        }

        if (function instanceof DensityFunctions.Clamp node) {
            int regInput = targetReg + 1;
            generateCommands(data, node.input(), regInput);

            int minIdx = data.storeConst(node.minValue());
            int maxIdx = data.storeConst(node.maxValue());

            data.program(OP_CLAMP);
            data.program(regInput);
            data.program(minIdx);
            data.program(maxIdx);
            data.program(targetReg);
            return;
        }

        if (function instanceof DensityFunctions.Mapped node) {
            int regInput = targetReg + 1;
            generateCommands(data, node.input(), regInput);

            switch (node.type()) {
                case ABS -> data.program(OP_ABS);
                case CUBE -> data.program(OP_CUBE);
                case SQUARE -> data.program(OP_SQUARE);
                case SQUEEZE -> data.program(OP_SQUEEZE);
                case HALF_NEGATIVE -> data.program(OP_HALF_NEGATIVE);
                case QUARTER_NEGATIVE -> data.program(OP_QUARTER_NEGATIVE);
                default -> throw new UnsupportedOperationException("Unknown Mapped type: " + node.type());
            }

            data.program(regInput);
            data.program(targetReg);
            return;
        }

        if (function instanceof DensityFunctions.TwoArgumentSimpleFunction node) {
            int regA = targetReg + 1;
            int regB = targetReg + 2;

            generateCommands(data, node.argument1(), regA);
            generateCommands(data, node.argument2(), regB);

            switch (node.type()) {
                case ADD -> data.program(OP_ADD);
                case MUL -> data.program(OP_MUL);
                case MIN -> data.program(OP_MIN);
                case MAX -> data.program(OP_MAX);
            }

            data.program(regA);
            data.program(regB);
            data.program(targetReg);
            return;
        }

        if(function instanceof DensityFunctions.RangeChoice node) {
            int inputReg = targetReg + 1;
            generateCommands(data, node.input(), inputReg);

            int minIdx = data.storeConst(node.minInclusive());
            int maxIdx = data.storeConst(node.maxExclusive());

            /*
                Генерируем начало ветвления (OP_RANGE_JUMP)
             */
            data.program(OP_RANGE_JUMP);
            data.program(inputReg);
            data.program(minIdx);
            data.program(maxIdx);

            /*
                ВАЖНО: Запоминаем индекс, куда нужно будет записать смещение прыжка
                Мы пока пишем 0, потому что не знаем размер True-блока
             */
            int jumpToFalsePatchIndex = data.pSize();
            data.program(0); // Placeholder

            generateCommands(data, node.whenInRange(), targetReg);

            /*
                После True ветки нам нужно перепрыгнуть False ветку
             */
            data.program(OP_JUMP);
            int jumpToEndPatchIndex = data.pSize();
            data.program(0); // Placeholder

            /*
                Мы узнали, где начинается False блок. Исправляем прыжок из шага 3.
                Вычисляем смещение: Текущая позиция - Позиция плейсхолдера
             */
            int offsetToFalse = data.pSize() - jumpToFalsePatchIndex;
            data.program.set(jumpToFalsePatchIndex, offsetToFalse);

            generateCommands(data, node.whenOutOfRange(), targetReg);

            /*
                Мы узнали, где конец всего RangeChoice. Исправляем прыжок из шага 5.
             */
            int offsetToEnd = data.pSize() - jumpToEndPatchIndex;
            data.program.set(jumpToEndPatchIndex, offsetToEnd);

            return;
        }
        
        if(function instanceof DensityFunctions.EndIslandDensityFunction node) {
            data.program(OP_END_ISLAND)
                .program(OP_SIMPLEX_NOISE)
                .program(noiseSerializer.findOrSerialize(node.islandNoise))
                .program(targetReg);
            return;
        }

        if(function instanceof DensityFunctions.Noise node) {
            data.program(OP_NOISE);

            final NormalNoise noise = node.noise().noise();
            {
                int hasNoise = data.storeConst(noise != null ? 1 : 0);
                data.program(hasNoise);

                if (hasNoise == 1)
                {
                    data.program(OP_NORMAL_NOISE);
                    data.program(noiseSerializer.findOrSerialize(noise));
                }
                else
                {
                    data.program(data.storeConst(0.0));
                }
            }

            data.program(targetReg);
            return;
        }

        if (function instanceof DensityFunctions.MarkerOrMarked node) {

            // 1. Unwrap для всего лишнего (CacheOnce, FlatCache, Interpolated)
            if (node.type() != DensityFunctions.Marker.Type.Cache2D) {
                generateCommands(data, node.wrapped(), targetReg);
                return;
            }

            // 2. Логика Cache2D
            int cacheId = data.getNextCacheId();

            data.program(OP_CACHE_2D_CHECK);
            data.program(cacheId);
            data.program(targetReg);

            int jumpPatch = data.pSize();
            data.program(0); // Placeholder для прыжка

            // Компилируем вычисления (тяжелая часть)
            generateCommands(data, node.wrapped(), targetReg);

            // Сохраняем результат (чтобы в следующий раз был HIT)
            data.program(OP_CACHE_2D_STORE);
            data.program(cacheId);
            data.program(targetReg);

            // Патчим прыжок (сколько пропустить, если HIT)
            // -1 не нужен, если ты в C++ делаешь pc += offset
            data.program.set(jumpPatch, data.pSize() - jumpPatch);
            return;
        }

        if(function instanceof DensityFunctions.ShiftedNoise node) {

            int regA = targetReg + 1;
            int regB = targetReg + 2;
            int regC = targetReg + 3;

            generateCommands(data, node.shiftX(), regA);
            generateCommands(data, node.shiftY(), regB);
            generateCommands(data, node.shiftZ(), regC);

            data.program(OP_SHIFTED_NOISE);
            data.program(regA).program(regB).program(regC);

            data.program(data.storeConst(node.xzScale()));
            data.program(data.storeConst(node.yScale()));

            final NormalNoise noise = node.noise().noise();
            {
                int hasNoise = data.storeConst(noise != null ? 1 : 0);
                data.program(hasNoise);

                if (hasNoise == 1)
                {
                    data.program(OP_NORMAL_NOISE);
                    data.program(noiseSerializer.findOrSerialize(noise));
                }
                else
                {
                    data.program(data.storeConst(0.0));
                }
            }
            data.program(targetReg);
            return;
        }

        if(function instanceof DensityFunctions.ShiftA node) {

            data.program(OP_SHIFT_A);
            final NormalNoise noise = node.offsetNoise().noise();
            {
                int hasNoise = data.storeConst(noise != null ? 1 : 0);
                data.program(hasNoise);

                if (hasNoise == 1)
                {
                    data.program(OP_NORMAL_NOISE);
                    data.program(noiseSerializer.findOrSerialize(noise));
                }
                else
                {
                    data.program(data.storeConst(0.0));
                }
            }
            return;
        }

        if(function instanceof DensityFunctions.ShiftB node) {

            data.program(OP_SHIFT_B);
            final NormalNoise noise = node.offsetNoise().noise();
            {
                int hasNoise = data.storeConst(noise != null ? 1 : 0);
                data.program(hasNoise);

                if (hasNoise == 1)
                {
                    data.program(OP_NORMAL_NOISE);
                    data.program(noiseSerializer.findOrSerialize(noise));
                }
                else
                {
                    data.program(data.storeConst(0.0));
                }
            }
            return;
        }

        if(function instanceof DensityFunctions.Shift node) {

            data.program(OP_SHIFT);
            final NormalNoise noise = node.offsetNoise().noise();
            {
                int hasNoise = data.storeConst(noise != null ? 1 : 0);
                data.program(hasNoise);

                if (hasNoise == 1)
                {
                    data.program(OP_NORMAL_NOISE);
                    data.program(noiseSerializer.findOrSerialize(noise));
                }
                else
                {
                    data.program(data.storeConst(0.0));
                }
            }
            return;
        }

        if(function instanceof DensityFunctions.BlendDensity node) {

            int regA = targetReg + 1;
            generateCommands(data, node.input(), regA);

            data.program(OP_BLEND);
            data.program(regA);
            data.program(targetReg);
            return;
        }

        if(function instanceof DensityFunctions.YClampedGradient node) {
            data.program(OP_Y_CLAMPED);
            data.program(data.storeConst(node.fromY()));
            data.program(data.storeConst(node.toY()));
            data.program(data.storeConst(node.fromValue()));
            data.program(data.storeConst(node.toValue()));
            data.program(targetReg);
            return;
        }

        if(function instanceof DensityFunctions.BlendDensity node) {

            int argA = targetReg + 1;

            generateCommands(data, node.input(), argA);

            data.program(OP_BLENDER);
            data.program(argA);
            data.program(targetReg);
            return;
        }

        throw new UnsupportedOperationException("Unsupported function: " + function.getClass().getSimpleName());
    }

    private static int serializeNoiseOrNull(NormalNoise noise) {
        if (noise == null) return -1;
        return noiseSerializer.findOrSerialize(noise);
    }
}

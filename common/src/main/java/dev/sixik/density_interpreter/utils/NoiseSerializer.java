package dev.sixik.density_interpreter.utils;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class NoiseSerializer {
    // Мета-данные: [IndexInParams, IndexInPerms, OctaveCount1, OctaveCount2]
    public final List<Integer> meta = new ArrayList<>();

    // Параметры: [ValueFactor, (Octave1: xo, yo, zo, amp), (Octave2...)]
    // Используем fastutil для избежания боксинга Double
    public final DoubleArrayList params = new DoubleArrayList();

    // Пермутации: [256 bytes, 256 bytes...]
    // Используем fastutil для избежания боксинга Byte
    public final ByteArrayList perms = new ByteArrayList();

    // КЭШ ДЛЯ ДУБЛИКАТОВ
    // Используем IdentityHashMap, так как Minecraft часто переиспользует одни и те же объекты шума.
    // Это предотвращает передачу гигабайтов лишних данных.
    private final Map<NormalNoise, Integer> cache = new IdentityHashMap<>();

    private final Map<SimplexNoise, Integer> simplexCache = new IdentityHashMap<>();

    public int findOrSerialize(SimplexNoise noise) {
        if (simplexCache.containsKey(noise)) {
            return simplexCache.get(noise);
        }

        int id = meta.size() / 4; // ID для C++

        // Мета-данные
        meta.add(params.size()); // Где лежат double (xo, yo, zo)
        meta.add(perms.size());  // Где лежат byte (p)
        meta.add(0);             // Заглушка (не используется)
        meta.add(0);             // Заглушка (не используется)

        // Параметры (xo, yo, zo) - нужны AT (Access Transformer)
        params.add(noise.xo);
        params.add(noise.yo);
        params.add(noise.zo);

        // Пермутации
        // В коде Minecraft p[] объявляется как int[512], но заполняется 0-255.
        // Нам достаточно сохранить 256 байт, так как p(i) делает i & 255.
        for (int i = 0; i < 256; i++) {
            // noise.p - это int[], кастим в byte
            perms.add((byte) noise.p[i]);
        }

        simplexCache.put(noise, id);
        return id;
    }

    /**
     * Сохраняет NormalNoise и возвращает его ID (индекс в meta / 4).
     * Если шум уже был сохранен ранее, возвращает существующий ID без повторной записи.
     */
    public int findOrSerialize(NormalNoise noise) {
        if (noise == null) {
            throw new IllegalArgumentException("Cannot serialize null NormalNoise");
        }

        // 1. Проверка кэша (Дедупликация)
        if (cache.containsKey(noise)) {
            return cache.get(noise);
        }

        // 2. Вычисляем новый ID
        // Каждый шум занимает 4 int-а в массиве meta, поэтому ID = size / 4
        int id = meta.size() / 4;

        // 3. Записываем смещения (где начинаются данные этого шума)
        meta.add(params.size()); // Start Index Doubles
        meta.add(perms.size());  // Start Index Bytes

        // 4. Сохраняем глобальные параметры шума
        // ! Требуется AccessTransformer для поля valueFactor
        params.add(noise.valueFactor);

        // 5. Сериализуем ПЕРВЫЙ PerlinNoise
        // ! Требуется AccessTransformer для поля first
        int count1 = serializePerlin(noise.first);

        // 6. Сериализуем ВТОРОЙ PerlinNoise
        // ! Требуется AccessTransformer для поля second
        int count2 = serializePerlin(noise.second);

        // 7. Записываем количество активных октав для C++ парсера
        meta.add(count1);
        meta.add(count2);

        // 8. Сохраняем в кэш
        cache.put(noise, id);

        return id;
    }

    private int serializePerlin(PerlinNoise perlin) {
        // ! Требуется AccessTransformer для полей noiseLevels и amplitudes
        ImprovedNoise[] levels = perlin.noiseLevels;
        DoubleList amps = perlin.amplitudes;

        int activeOctaves = 0;

        for (int i = 0; i < levels.length; i++) {
            ImprovedNoise octave = levels[i];

            // В PerlinNoise массив может быть разреженным (содержать null)
            if (octave != null) {
                // Записываем параметры октавы (4 double значения)
                // ! Требуется AccessTransformer, если xo, yo, zo приватные (обычно они public final)
                params.add(octave.xo);
                params.add(octave.yo);
                params.add(octave.zo);
                params.add(amps.getDouble(i)); // Амплитуда конкретно этой октавы

                // Записываем таблицу перестановок (256 байт)
                // ! Требуется AccessTransformer для поля p (byte[])
                byte[] pArray = octave.p;

                // Если pArray доступен, копируем его.
                // Цикл здесь надежнее arraycopy для ByteArrayList, хотя addElements быстрее
                for (byte b : pArray) {
                    perms.add(b);
                }

                activeOctaves++;
            }
        }
        return activeOctaves;
    }

    /**
     * Очищает данные. Вызывать после того, как данные переданы в C++
     * и больше не нужны (например, после загрузки измерения).
     */
    public void clear() {
        meta.clear();
        params.clear();
        perms.clear();
        cache.clear();
    }
}
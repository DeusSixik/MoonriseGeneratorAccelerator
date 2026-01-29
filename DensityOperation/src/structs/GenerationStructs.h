//
// Created by sixik on 29.01.2026.
//

#ifndef DENSITYOPERATION_GENERATIONSTRUCTS_H
#define DENSITYOPERATION_GENERATIONSTRUCTS_H
#include <cstdint>
#include <span>
#include <vector>

struct CacheEntry {
    long last_hash = -1; // Хеш координат (X, Z)
    double value = 0.0;  // Сохраненное значение
};

struct OctaveData {
    double xo, yo, zo;
    double amplitude;
    const uint8_t* p; // Указатель на перестановки
};

struct NormalNoiseData {
    double valueFactor;
    std::vector<OctaveData> first_octaves;
    std::vector<OctaveData> second_octaves;
};

struct SimplexNoiseData {
    double xo, yo, zo;
    const uint8_t* p;
};

struct NoiseContext {
    // Байт-код и константы
    std::vector<int> program;
    std::vector<double> constants;

    // Распарсенные шумы
    std::vector<NormalNoiseData> normal_noises;
    std::vector<SimplexNoiseData> simplex_noises;

    // Сырые буферы (владеем памятью)
    std::vector<uint8_t> perm_blob; // Тут лежат все таблицы перестановок

    // Кол-во кэшей (для аллокации памяти в потоках)
    int max_cache_id = 0;
};

struct GeneratorContext {
    // Входные данные (Read-only)
    std::span<int> program;       // Наш "байт-код"
    std::span<double> constants;  // Константы
    std::span<uint8_t> perlin_data; // Данные для шума

    // Буферы для интерполяции (аналог slice0/slice1 из Java)
    // Размер: (CellCountY + 1) * (CellCountXZ + 1)
    std::vector<double> slice0;
    std::vector<double> slice1;

    // Результат
    std::span<int> output_blocks;

    std::vector<CacheEntry> caches;
};

#endif //DENSITYOPERATION_GENERATIONSTRUCTS_H
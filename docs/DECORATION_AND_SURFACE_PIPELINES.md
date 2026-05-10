# Decoration Pipeline, Surface Rule Compiler и изменения ветки

Этот документ описывает крупные изменения в текущей ветке `experiment/allocations-and-metrics` по сравнению с `origin/master`. Главный фокус - новый `Decoration Pipeline`, новый `Surface Rule Compiler` и связанные изменения вокруг генерации мира, кешей, диагностик и уменьшения аллокаций.

Текст написан так, чтобы его мог читать разработчик, который впервые видит этот код. Поэтому сначала идут простые идеи, потом технические детали, а затем список классов и правил, которые важно не сломать.

## 1. Коротко о цели ветки

Ветка делает генерацию мира более data-oriented. Это значит, что горячие пути стараются работать не через цепочки объектов, `Stream`, `BlockPos` и виртуальные вызовы, а через заранее подготовленные планы, массивы, битовые маски и переиспользуемые scratch-объекты.

Основные цели:

- уменьшить количество временных объектов во время генерации чанка;
- уменьшить количество повторных обходов одних и тех же списков features, biomes, rules и sections;
- заменить часть ванильных inner loops на специализированные циклы;
- сохранить порядок генерации и RNG-поведение там, где это наблюдаемо;
- иметь fallback, если оптимизированный путь не уверен в корректности;
- добавить диагностические счетчики, чтобы понимать, где именно остаются аллокации и медленные ветки.

Самые важные новые подсистемы:

- `Decoration Pipeline` - замена горячей части `ChunkGenerator.applyBiomeDecoration`.
- `Surface Rule Compiler` - компиляция surface rules в компактную программу по section mask.
- `Feature VM` - старый/legacy интерпретатор placement modifiers, сейчас помечен как deprecated, но остается для совместимости и бенчмарков.
- `SectionDescriptor` / `SectionDescriptorCache` - легкие описатели секций для быстрого предварительного отсева features.
- `Carver` fast path - кеширование планов carver-чанков и быстрый writer для carving.
- `FlatBlockArray` - плоский `int[4096]` с fast id block states для section-level циклов.
- `GADiagnostics` - runtime-команды и JFR/JSON bundle для диагностики.
- Обновления `DensityFunctionCompiler` - не новая подсистема в этой ветке, но в ней появились важные lifecycle, metrics и native-related правки.

## 2. Где находится код

Основные пакеты:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/pipeline
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/ir
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/mask
common/src/main/java/dev/sixik/generator_accelerator/common/carver
common/src/main/java/dev/sixik/generator_accelerator/diagnostics
```

## 3. Ванильный biome decoration: что ускоряется

Ванильный `ChunkGenerator.applyBiomeDecoration` делает примерно следующее:

1. Берет центральный chunk.
2. Собирает biomes из области вокруг него.
3. По каждому `GenerationStep.Decoration` размещает structures и features.
4. Для каждого `PlacedFeature` вызывает цепочку placement modifiers.
5. Каждый modifier возвращает `Stream<BlockPos>`.
6. После всех modifiers вызывается `ConfiguredFeature.place(...)`.

У такого подхода есть несколько затрат:

- `Stream` и iterators в placement modifiers создают много временных объектов.
- `BlockPos` часто создаются или копируются.
- Для каждого чанка повторно собираются features для одинаковых наборов biomes.
- Многие features заранее невозможно поставить, но ваниль узнает это только после чтения мира.
- Некоторые ванильные features имеют простую логику, но вызываются через общий объектный API.

`Decoration Pipeline` заменяет этот путь на заранее подготовленный план и выполняет только features, которые действительно присутствуют в biome-наборе текущего чанка.

## 4. Общая схема Decoration Pipeline

Упрощенная схема:

```text
featuresPerStep из ChunkGenerator
        |
        v
StepFeatureCache
        |
        v
JavaDecorationCompiler
        |
        v
DecorationPlan -> DecorationStepPlan -> DecorationKernelPlan
        |
        v
applyBiomeDecoration собирает biomes вокруг чанка
        |
        v
BiomeSignatureFeatureMaskCache / BiomeDecorationScratch
        |
        v
DecorationPipelineExecutor.executeSelectedMask(...)
        |
        v
DecorationPlacementProgram + native kernels + fallback
```

Важная мысль: pipeline не компилирует Java bytecode. Слово compiler здесь означает, что код заранее превращает списки Minecraft objects в более удобные планы: массивы enum-like opcodes, ссылки на нужные конфиги, descriptor gates и branch plans.

## 5. Новый `applyBiomeDecoration`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/mixin/MixinChunkGenerator$apply_biome_decoration.java
```

Этот mixin полностью overwrite-ит `ChunkGenerator.applyBiomeDecoration`.

Главные изменения:

- вместо `ChunkPos.rangeClosed` используется простой двойной цикл по `x/z` от `center - 1` до `center + 1`;
- biomes собираются напрямую из `LevelChunkSection.getBiomes()`;
- для обычного `PalettedContainer` код читает palette/storage и собирает только уникальные palette indices;
- features по step выбираются через битовые маски;
- structures по generation step берутся из `StructureStepCache`;
- для размещения features вызывается `DecorationPipelineExecutor`, а не ванильный `PlacedFeature.placeWithContext`;
- scratch-объекты лежат в `ThreadLocal` и очищаются после чанка.

Thread-local объекты:

```text
WorldgenRandom
BiomeDecorationScratch
RegistryNameSupplier
DecorationPipelineExecutor
JavaDecorationCompiler
DecorationPipelineScratch
```

Такой подход уменьшает аллокации на каждый chunk. Объекты создаются один раз на worker thread и затем переиспользуются.

## 6. Feature cache epoch

В `MixinChunkGenerator$apply_biome_decoration` есть поля:

```text
ga$stepFeatureCache
ga$decorationPlan
ga$biomeSignatureFeatureCacheOwner
ga$biomeSignatureMaskCache
ga$featureCacheEpoch
```

Они живут внутри `ChunkGenerator`. Но при reload datapack или новом server lifecycle старые holders/features могут стать неактуальными. Поэтому есть `FeatureCacheEpoch` и `GARuntimeCaches.resetForServerLifecycle()`.

При смене epoch `ga$ensureFeatureCacheEpoch()` сбрасывает:

- `StepFeatureCache`;
- `DecorationPlan`;
- `BiomeSignatureFeatureMaskCache`;
- owner-ссылки на старый feature cache.

Это важно, потому что worldgen registries и holders могут быть пересозданы.

## 7. `StepFeatureCache`: быстрый индекс features по step

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/StepFeatureCache.java
```

`StepFeatureCache` строится из `featuresPerStep.get()`.

Он хранит:

- `featuresByStep` - массив features для каждого decoration step;
- `featureMaskWordsByStep` - сколько `long` нужно для битовой маски features step-а;
- `indexMappings` - функция из ванильного `FeatureSorter.StepFeatureData`, которая говорит индекс feature внутри step-а;
- `biomeFeatureData` - кеш масок features для конкретного biome.

Для biome cache строится `BiomeFeatureData`:

```text
long[][] masksByStep
int[] nonEmptySteps
long nonEmptyStepBits
```

То есть для каждого biome заранее понятно: в каких step-ах есть features и какие feature indices нужно включить.

## 8. `BiomeDecorationScratch`: рабочая память biome decoration

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/BiomeDecorationScratch.java
```

`BiomeDecorationScratch` хранит временные данные для одного чанка:

- set найденных biomes;
- combined feature masks по step;
- список выбранных feature indices;
- scratch-массив palette indices для быстрого сканирования biome palettes.

Главный методический прием: вместо списка selected features используется `long[]`, где каждый бит - feature index.

Пример:

```text
feature index 0  -> bit 0 первого long
feature index 63 -> bit 63 первого long
feature index 64 -> bit 0 второго long
```

Так можно быстро объединять features нескольких biomes через `OR`.

## 9. `BiomeSignatureFeatureMaskCache`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/BiomeSignatureFeatureMaskCache.java
```

В разных соседних chunks часто встречается один и тот же набор biomes. Тогда заново объединять feature masks всех biomes не нужно.

`BiomeSignatureFeatureMaskCache` делает маленький cache по unordered-набору biome holders:

- считает два hash значения `hashA` и `hashB`;
- сравнивает количество biomes;
- дополнительно проверяет `contains` для всех holders;
- при hit копирует готовые combined masks в scratch.

Это ускоряет внешний этап biome decoration до запуска самих features.

## 10. `DecorationPlan`, `DecorationStepPlan`, `DecorationKernelPlan`

Файлы:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/pipeline/DecorationPlan.java
common/src/main/java/dev/sixik/generator_accelerator/common/features/pipeline/DecorationStepPlan.java
common/src/main/java/dev/sixik/generator_accelerator/common/features/pipeline/DecorationKernelPlan.java
```

`DecorationPlan` - план для всех decoration steps.

`DecorationStepPlan` - план одного step-а. Он хранит:

- номер step-а;
- количество features;
- `kernelsByFeatureIndex`;
- fallback features;
- fallback feature indices;
- `descriptorFeatureMask` - какие features в этом step-е требуют section descriptors.

`DecorationKernelPlan` - план одного `PlacedFeature`. Он хранит:

- kind kernel-а;
- исходный `PlacedFeature` для fallback;
- `ConfiguredFeature` holder;
- compiled `DecorationPlacementProgram`;
- nested feature/config/program для `RandomPatch`;
- `OreTargetPlan` для ore kernels;
- `SelectorPlan` для selector features;
- flags для batching и relaxed visibility;
- имя для метрик.

Важно: `DecorationKernelPlan` всегда держит fallback feature, если он есть. Это нужно для safe fallback и quarantine.

## 11. `DecorationKernelKind`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/pipeline/DecorationKernelKind.java
```

Kernel kind говорит, насколько глубоко pipeline понимает feature:

```text
NATIVE_*                  - feature исполняется своим специализированным кодом
PARTIAL_NATIVE_PLACEMENT  - placement modifiers оптимизированы, leaf feature остается ванильным
PARTIAL_NATIVE_DESCRIPTOR_GATED - перед ванильным leaf есть descriptor gate
VANILLA_FALLBACK          - консервативный путь
```

Native kernels в этой ветке:

- `NATIVE_ORE`;
- `NATIVE_SCATTERED_ORE`;
- `NATIVE_RANDOM_PATCH_SIMPLE`;
- `NATIVE_RANDOM_PATCH_SELECTOR`;
- `NATIVE_SELECTOR_SIMPLE`;
- `NATIVE_SIMPLE_BLOCK`;
- `NATIVE_DISK`;
- `NATIVE_BLOCK_COLUMN`;
- `NATIVE_PLANT_WATER`;
- `NATIVE_SPRING`;
- `NATIVE_SNOW_FREEZE`;
- `NATIVE_LAKE`;
- `NATIVE_SCULK_PATCH`;
- `NATIVE_TREE`.

## 12. `JavaDecorationCompiler`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/pipeline/JavaDecorationCompiler.java
```

`JavaDecorationCompiler` берет `featuresByStep` и строит `DecorationPlan`.

Что он делает для каждого `PlacedFeature`:

1. Компилирует placement modifiers в `DecorationPlacementProgram`.
2. Классифицирует `ConfiguredFeature` через `classifyConfigured`.
3. Для ores строит `OreTargetPlan`, если config безопасен для raw writes.
4. Для random patch достает nested placed feature.
5. Для selectors строит `SelectorPlan`.
6. Если native path невозможен, оставляет partial native или fallback.
7. Обновляет counters компиляции.

Ограничение для selector recursion:

```text
MAX_COMPILED_BRANCH_DEPTH = 6
```

Это защита от слишком глубоких или цикличных modded selector trees.

## 13. Как компилируются placement modifiers

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/pipeline/DecorationPlacementProgram.java
```

`DecorationPlacementProgram.compile(feature)` превращает список `PlacementModifier` в массив opcodes.

Поддерживаемые быстрые opcodes:

```text
IN_SQUARE
HEIGHT_RANGE
HEIGHTMAP
RANDOM_OFFSET
REPEATING
PLACEMENT_FILTER
BIOME_FILTER
FAST_POSITIONS
VANILLA_MODIFIER
```

Для некоторых modifiers добавлены accessor-интерфейсы в `api/patches`:

```text
GA$HeightRangePlacementAccess
GA$HeightmapPlacementAccess
GA$RandomOffsetPlacementAccess
GA$RepeatingPlacementAccess
GA$PlacementFilterAccess
GA$PlacementModifierExtension
```

Если modifier не поддержан, он становится `VANILLA_MODIFIER`. Тогда pipeline открывает stream и рекурсивно продолжает выполнение. Это медленнее, но совместимо.

## 14. Выполнение placement program

Основной метод:

```text
DecorationPlacementProgram.executeAt(...)
```

Он идет по opcodes depth-first, как ванильная цепочка placement modifiers.

Пример:

```text
CountPlacement -> InSquare -> HeightRange -> BiomeFilter -> Feature.place
```

В pipeline это становится примерно таким потоком:

```text
REPEATING: повторить N раз
IN_SQUARE: x += random.nextInt(16), z += random.nextInt(16)
HEIGHT_RANGE: y = provider.sample(...)
BIOME_FILTER: проверить feature у biome
leaf: placeConfigured(...)
```

Важно: порядок остается depth-first. Это нужно для корректного RNG-поведения и для совместимости с features, которые могут наблюдать порядок world writes.

## 15. `DecorationPipelineExecutor`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/pipeline/DecorationPipelineExecutor.java
```

Executor получает `DecorationStepPlan`, execution context и selected feature mask.

Основные методы:

- `executeSelectedMask(...)` - выполнить features, выбранные битовой маской;
- `executeSelected(...)` - выполнить массив indices;
- `executeFallbacks(...)` - выполнить fallback features;
- `executeKernel(...)` - выполнить один kernel.

Перед kernel executor делает то же, что важно для ванили:

```text
random.setFeatureSeed(decorationSeed, featureIndex, step)
level.setCurrentlyGenerating(...)
```

Затем он выбирает путь:

1. Если feature quarantined - safe vanilla.
2. Если есть placement program - выполнить program.
3. Если program отсутствует - safe vanilla.
4. Если optimized path кинул exception - quarantine feature и попробовать safe vanilla.
5. Если safe vanilla тоже упал - оригинальная ошибка пробрасывается, fallback ошибка добавляется как suppressed.

Так pipeline должен терять скорость, но не ломать генерацию.

## 16. Safe vanilla path

Даже safe vanilla path в `DecorationPipelineExecutor` не полностью равен старому `Stream.flatMap` из `PlacedFeature`. Он использует свой рекурсивный обход modifiers:

```text
executeVanillaPlacedFeature(feature, context, random, pos, modifierIndex, scratch)
```

Если modifier реализует `GA$PlacementModifierExtension` и умеет `generatePositionsRaw`, используется `LongScratchBuffer` с packed block positions. Иначе вызывается обычный `modifier.getPositions(...)`.

Leaf вызывает:

```text
feature.feature().value().place(context.getLevel(), context.generator(), random, pos)
```

То есть safe vanilla path сохраняет семантику placement chain, но избегает части stream/BlockPos затрат, когда fast modifier доступен.

## 17. Quarantine совместимость

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/pipeline/DecorationPipelineCompatibility.java
```

Если optimized kernel падает, feature попадает в quarantine:

```text
QUARANTINED_FEATURES: weak-key cache по PlacedFeature
QUARANTINED_NAMESPACES: счетчик failures по namespace
DESCRIPTOR_FAILURES: дедупликация descriptor warnings
```

После quarantine этот `PlacedFeature` до конца session идет через safe vanilla path.

Лог пишет id placed feature, namespace, step, feature index, original feature index, kernel kind, configured feature class и список placement modifiers. Это помогает понять, для какого мода нужен compat или более осторожное условие.

## 18. Section descriptors: зачем они нужны

Многие features можно отсеять без дорогих world reads.

Примеры:

- ore не появится в секции без stone-like или ore-target blocks;
- water plant не появится в колонке без воды;
- simple grass/flower не появится без открытого места и ground support;
- spring не появится, если рядом точно нет нужных solid/air условий;
- tree не появится, если колонка точно не может содержать объем дерева или support.

Для этого есть `SectionDescriptor` и `SectionDescriptorCache`.

## 19. `SectionDescriptor`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/pipeline/SectionDescriptor.java
```

`SectionDescriptor` описывает одну section 16x16x16.

Он хранит aggregate flags:

```text
PALETTE_AIR
PALETTE_WATER
PALETTE_LAVA
PALETTE_SOLID

CLASS_STONE_LIKE
CLASS_DIRT_LIKE
CLASS_REPLACEABLE
CLASS_ORE_TARGET
CLASS_SURFACE_CANDIDATE
CLASS_TREE_SOIL
```

И column-level masks:

```text
columnAirMask
columnWaterMask
columnLavaMask
columnSolidMask
columnMotionBlockingMask
columnReplaceableMask
columnStoneLikeMask
columnDirtLikeMask
columnTreeSoilMask
```

Каждая column mask - это `int`, где 16 бит соответствуют local Y от 0 до 15.

Если section полностью air, descriptor использует special all-air mode. Тогда многие методы отвечают без заполнения всех массивов.

## 20. `SectionDescriptorCache`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/pipeline/SectionDescriptorCache.java
```

Cache хранит descriptors по ключу `(chunkX, chunkZ, sectionY)`. Он также хранит height caches по chunk:

- `worldSurfaceHeights`;
- `oceanFloorHeights`;
- `motionBlockingHeights`;
- `topWaterHeights`;
- chunk-level aggregate palette flags;
- chunk-level aggregate block class flags.

Есть два режима:

- `buildChunk(chunk)` - подготовить height entry и descriptors;
- `prepareChunkLazy(chunk)` - запомнить центральный chunk и строить section descriptors только когда они реально нужны.

В decoration executor сейчас используется lazy prepare:

```text
scratch.descriptors.prepareChunkLazy(context.chunk)
```

Это значит: descriptor cache знает центральный chunk, но не обязательно сканирует все sections сразу. Если kernel попросит descriptor по block pos, cache построит нужную section.

## 21. Descriptor gates должны быть консервативными

Descriptor gate - это предварительная проверка: есть ли в нужной section хотя бы шанс, что feature сможет что-то поставить. Например, ore feature не имеет смысла запускать в section, где точно нет stone-like или ore-target blocks. Water plant не имеет смысла запускать в section и соседних колонках, где точно нет воды.

Но gate не должен быть слишком умным. Его задача - безопасно отсеять только явно невозможные случаи. Если есть сомнение, gate обязан разрешить выполнение.

Простое правило:

```text
known impossible -> skip
unknown or maybe possible -> run normal logic
```

Это важно по двум причинам.

Первая причина - моды. Мод может добавить block, который выглядит как камень для своей feature, но не попал в наши быстрые class flags. Если gate скажет `skip`, мир изменится. Если gate скажет `run`, мы потеряем немного скорости, но сохраним корректность.

Вторая причина - соседние изменения. Decoration features могут писать блоки рядом с текущей позицией. Descriptor был построен до некоторых записей. Поэтому после записей его нужно обновлять, а до обновления лучше быть осторожным.

Хороший descriptor gate не доказывает, что feature точно поставится. Он доказывает только обратное: feature точно не поставится. Все остальное остается за ванильной или native логикой.

## 22. Обновление descriptors после записей

Когда native kernel пишет блоки напрямую, `SectionDescriptorCache` должен узнать об изменении. Иначе следующая feature в том же decoration pass может увидеть старую картину мира.

Обычная запись идет через helper:

```text
setChunkWriterBlockTracked(...)
  -> chunkWriter.setBlockState(...)
  -> noteBlockMutation(...)
  -> descriptors.noteBlockMutation(...)
```

`noteBlockMutation` не пересканирует весь chunk. Он перестраивает только затронутую колонку section и обновляет height caches для этой x/z колонки. Это дешевле, чем полный rebuild.

При batched writes обновление делается не сразу для каждой попытки, а через journal. В journal запоминаются затронутые `(chunk, x, sectionY, z)`. После commit вызывается:

```text
scratch.flushJournalDescriptorMutations()
```

Это важно: если внутри batch десять кандидатов пишут в одну и ту же section-column, descriptor нужно обновить один раз, а не десять.

Если descriptor cache не был подготовлен или нужного chunk entry нет, mutation может ничего не делать. Это допустимо: descriptor gates в таком случае должны вернуться к safe path или live reads. Ошибка descriptor cache не должна ломать генерацию мира.

## 23. Write journal и batching

Некоторые native kernels генерируют много кандидатов на запись. Например, random patch может сделать десятки попыток поставить simple block. Если каждый кандидат сразу вызывает `chunk.setBlockState`, то возникают лишние heightmap updates, descriptor updates и повторные lookup-и section.

Для таких случаев используется write journal в `DecorationPipelineScratch`.

Идея простая:

1. Kernel начинает journal.
2. Вместо немедленной записи складывает кандидатов в массивы `candidateX/Y/Z`, `candidateSimpleBlockState`, `candidateWriteFlags`.
3. Candidates группируются по chunk и section bucket.
4. На commit один раз открывается `CarverChunkWriter` для chunk.
5. Все записи внутри chunk/section применяются пачкой.
6. После commit flush-ятся descriptor mutations.

Journal хранит не объекты-кандидаты, а parallel arrays. Это уменьшает allocation pressure. Кандидат - это несколько значений в массивах, а не новый Java object.

Для direct writes есть dedupe по packed position. Если два кандидата хотят записать один и тот же block pos, journal может оставить первый candidate и увеличить счетчики collision/dedup. Это защищает от лишних записей.

Для simple block batch dedupe не всегда включен, потому что vanilla-like порядок попыток и survival checks могут быть важнее. Поэтому в коде есть отдельный режим `CANDIDATE_MODE_SIMPLE_BLOCK`.

Главные метрики journal:

- `journal.writeCandidates` - сколько записей было предложено;
- `journal.writesCommitted` - сколько реально записано;
- `journal.collisions` - сколько раз позиция уже была в journal;
- `journal.dedupedWrites` - сколько записей убрали как дубликаты;
- `journal.touchedSectionColumns` - сколько section-columns пришлось обновить в descriptors;
- `journal.commitBatches` - сколько chunk-level commit batches было сделано.

## 24. `CarverChunkWriter` используется не только carver-ом

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/carver/CarverChunkWriter.java
```

Название говорит про carver, но writer полезен и для decoration pipeline. Это тонкая обертка над записью block states в chunk.

Что он делает:

- при `begin(chunk)` запоминает chunk, sections, min/max Y и текущий `ChunkStatus`;
- если chunk является `ProtoChunk` до `INITIALIZE_LIGHT`, включает fast path;
- на fast path может читать raw section array `int[4096]`;
- при записи обновляет нужные heightmaps;
- умеет помечать postprocessing positions;
- при `end()` очищает ссылки, чтобы не удерживать chunk в ThreadLocal scratch.

Fast path особенно важен для генерации. На стадии worldgen chunk еще не полностью прошел lighting, поэтому можно безопаснее работать с proto data и heightmaps напрямую.

Если fast path недоступен, writer не ломается. Он вызывает обычные методы chunk. Это медленнее, но корректно.

В decoration pipeline writer живет в `DecorationPipelineScratch`:

```text
final CarverChunkWriter chunkWriter = new CarverChunkWriter();
```

Это значит, что один writer переиспользуется много раз на одном worker thread, а не создается на каждую feature.

## 25. Native ore kernel

Ore - один из самых дорогих и частых типов features. Поэтому для `Feature.ORE` добавлен native kernel `NATIVE_ORE`.

Компиляция идет так:

1. `JavaDecorationCompiler` видит `Feature.ORE`.
2. Проверяет, можно ли использовать native path для `OreConfiguration`.
3. `OreTargetCompiler` превращает targets в `OreTargetPlan`.
4. `DecorationKernelPlan` получает kind `NATIVE_ORE` и ссылку на target plan.

Выполнение идет через `DecorationPlacementProgram.placeOreNative(...)`. Placement modifiers уже прошли через opcodes, поэтому kernel получает конкретную стартовую позицию.

Native ore path старается ускорить несколько вещей:

- проверку target blocks через fast ids;
- чтение section data;
- предварительный descriptor gate по наличию ore targets;
- запись через общий tracked writer;
- уменьшение количества `BlockPos` и predicate allocations.

Если `OreConfiguration` слишком сложный или target plan не удалось построить безопасно, kernel не становится full native. Он переходит в `PARTIAL_NATIVE_DESCRIPTOR_GATED` или `PARTIAL_NATIVE_PLACEMENT`. Тогда placement может быть быстрым, но сама feature вызывается ванильным способом.

Это типичный стиль этой ветки: ускоряем только то, в чем уверены.

## 26. Native scattered ore kernel

`Feature.SCATTERED_ORE` похож на обычную ore feature, но размещение блоков другое. Для него есть отдельный kind:

```text
NATIVE_SCATTERED_ORE
```

Причина отдельного kind - не только читаемость. У scattered ore другой внутренний алгоритм выбора позиций, и его нельзя слить с обычным ore без риска изменить распределение.

Общая логика такая же:

- compiler проверяет feature type;
- компилирует ore targets;
- placement program генерирует стартовые позиции;
- native kernel пытается поставить blocks;
- descriptor gate заранее отсекает sections без подходящих targets.

Если target plan недоступен, код не пытается угадать. Он уходит в partial/fallback path.

Особенно важно не менять RNG порядок. Даже если scattered ore кажется простой, количество `random.next*` вызовов должно оставаться совместимым с vanilla для наблюдаемой генерации.

## 27. Native simple block и random patch

`Feature.SIMPLE_BLOCK` часто используется для травы, цветов, грибов и других одиночных blocks. Сам по себе simple block прост: выбрать state provider, проверить `canSurvive`, поставить block.

Но в мире он часто вложен в `RandomPatchConfiguration`:

```text
RANDOM_PATCH
  -> nested PlacedFeature
      -> SIMPLE_BLOCK
```

Ветка оптимизирует оба случая:

- `NATIVE_SIMPLE_BLOCK` - одиночный simple block;
- `NATIVE_RANDOM_PATCH_SIMPLE` - random patch, у которого nested feature является simple block;
- `NATIVE_RANDOM_PATCH_SELECTOR` - random patch, у которого nested feature является selector.

Для simple block важна survival check. Нельзя просто записать block в raw array. Нужно спросить state, может ли он стоять в этом мире на этой позиции. Поэтому код сначала подготавливает позицию и state, затем проверяет условия, и только потом пишет.

Когда `tries` у random patch достаточно большой, включается simple block batch. Кандидаты сначала собираются, затем commit-ятся пачкой. Это уменьшает overhead на повторном доступе к chunk и descriptors.

Descriptor gates для simple block не должны решать за `canSurvive`. Они только помогают понять, есть ли рядом air/replaceable/support-like блоки, чтобы не запускать явно бесполезную работу.

## 28. Selector fusion

Minecraft имеет selector features:

- `RANDOM_SELECTOR`;
- `RANDOM_BOOLEAN_SELECTOR`;
- `SIMPLE_RANDOM_SELECTOR`.

Они выбирают один из вложенных placed features и запускают его. В vanilla это означает дополнительный слой вызовов: outer placement, selector feature, nested placed feature, nested placement, nested configured feature.

`SelectorPlan` сжимает эту цепочку в plan:

```text
selector mode
weights / probability / alternatives
compiled branch kernels
fast branch kind
```

Если selector выбирает simple block или random patch simple, pipeline может сделать fusion. То есть он выполняет outer placement и nested branch в одном проходе без лишнего возвращения в общий feature API.

В `SelectorPlan` есть fast branch constants:

- `FAST_BRANCH_SIMPLE_BLOCK`;
- `FAST_BRANCH_RANDOM_PATCH_SIMPLE`;
- `FAST_BRANCH_RANDOM_PATCH_SELECTOR`;
- `FAST_BRANCH_SELECTOR`.

Fusion ограничен depth cap. Это защита от слишком глубоких или рекурсивных trees of features. Если depth cap достигнут, selector остается partial native.

Главная польза selector fusion - убрать лишние objects и лишний dispatch в популярных vegetation chains.

## 29. Partial native paths

Не все features можно безопасно переписать на native loop. Поэтому в pipeline есть два partial режима:

```text
PARTIAL_NATIVE_PLACEMENT
PARTIAL_NATIVE_DESCRIPTOR_GATED
```

`PARTIAL_NATIVE_PLACEMENT` означает: placement modifiers выполняются быстрым `DecorationPlacementProgram`, но leaf feature вызывается ванильным `ConfiguredFeature.place(...)`.

`PARTIAL_NATIVE_DESCRIPTOR_GATED` означает: кроме быстрого placement, можно использовать descriptor gates для предварительного skip-а. Например, feature явно cave/solid/ore-like, но полный native алгоритм пока не реализован.

Partial mode полезен потому что placement modifiers сами по себе создают много `Stream<BlockPos>`. Даже если leaf feature остается vanilla, быстрый placement уже убирает часть аллокаций.

Но partial mode опаснее, чем кажется. Ванильная feature может ожидать, что placement context и biome filter работают точно как обычно. Поэтому для partial path используется `PipelinePlacementContext` и fallback-compatible execution.

Если в partial mode возникает runtime exception, feature отправляется в quarantine и дальше идет safe vanilla path.

## 30. Water plants, spring, disk, block column, lake, sculk

Кроме ore и simple vegetation, compiler распознает несколько других vanilla features.

Water plants:

- `SEAGRASS`;
- `KELP`;
- `SEA_PICKLE`.

Они попадают в `NATIVE_PLANT_WATER`, если config поддержан. Kernel проверяет воду, доступное место, высоты и правила конкретного растения.

Spring:

- `Feature.SPRING` с `SpringConfiguration` может идти через `NATIVE_SPRING`.
- Если config неизвестен, путь становится descriptor-gated partial.

Disk:

- `Feature.DISK` идет через `NATIVE_DISK`.
- Здесь важны radius, half-height и replacement rules.

Block column:

- `Feature.BLOCK_COLUMN` идет через `NATIVE_BLOCK_COLUMN`.
- Для него есть cache compiled column configs, чтобы не пересобирать layers каждый раз.

Lake:

- `Feature.LAKE` с `LakeFeature.Configuration` может идти через `NATIVE_LAKE`.
- Если configuration отличается, используется partial placement.

Sculk patch:

- `Feature.SCULK_PATCH` с `SculkPatchConfiguration` может идти через `NATIVE_SCULK_PATCH`.
- Вокруг sculk есть отдельные scratch-объекты, потому что vanilla logic использует spreader/cursors.

У всех этих kernels общий принцип: поддерживаем только распознанный vanilla-like config. Модовый или неизвестный config не ломаем, а отправляем в partial/fallback.

## 31. Biome filter в pipeline

`BiomeFilter` - особый placement modifier. Он проверяет, подходит ли biome в позиции для данного placed feature.

В старом API modifier получает context и position и сам решает, пропускать позицию или нет. В pipeline `DecorationPlacementProgram` помечает наличие biome filter через `hasBiomeFilter`.

Во время выполнения есть два уровня biome filtering:

1. На уровне выбранных features по biome set чанка. Это быстрый bitmask, который говорит: feature вообще присутствует в одном из biomes вокруг чанка.
2. На уровне конкретной позиции. Это нужен для vanilla semantics, потому что позиция может попасть в другой biome внутри 3x3 области.

Нельзя заменить второй уровень первым. Первый уровень только отбирает кандидаты. Второй уровень сохраняет точность placement.

Для native/fused branch code часто передает `biomeFilterFeature`, чтобы проверка знала, какой placed feature сейчас считается активным.

Если biome filter или context ведут себя не так, как ожидает мод, feature должна уйти в fallback.

## 32. Placement modifier fast APIs

В ветке добавлены access interfaces для placement modifiers:

```text
GA$HeightRangePlacementAccess
GA$HeightmapPlacementAccess
GA$RandomOffsetPlacementAccess
GA$RepeatingPlacementAccess
GA$PlacementFilterAccess
GA$PlacementModifierExtension
```

Они нужны, чтобы не доставать приватные поля через reflection и не создавать stream там, где можно сгенерировать raw positions.

Например:

- `HeightRangePlacement` дает прямой доступ к `HeightProvider`;
- `HeightmapPlacement` дает `Heightmap.Types`;
- `RandomOffsetPlacement` дает XZ/Y providers;
- `RepeatingPlacement` может сказать count без stream;
- `PlacementFilter` может выполнить predicate напрямую;
- `GA$PlacementModifierExtension` может генерировать packed long positions.

В `DecorationPlacementProgram` эти modifiers превращаются в opcodes:

```text
IN_SQUARE
HEIGHT_RANGE
HEIGHTMAP
RANDOM_OFFSET
REPEATING
PLACEMENT_FILTER
BIOME_FILTER
FAST_POSITIONS
VANILLA_MODIFIER
```

Если modifier неизвестен, он остается `VANILLA_MODIFIER`. Тогда программа использует vanilla `getPositions(...)` только для этого участка цепочки.

Это дает хорошую совместимость: новые fast APIs ускоряют известные modifiers, но не требуют переписать все placement modifiers в модпаке.

## 33. Legacy Feature VM

Пакет:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/features/vm
```

Feature VM - это более ранний подход к ускорению placed features. Он компилирует placement modifiers в небольшую VM с opcodes и выполняет ее через `FeatureVm`.

В текущей ветке основной новый путь - `Decoration Pipeline`. Feature VM остается потому что:

- она полезна для сравнения benchmarks;
- некоторые mixin paths и старые fast placement hooks еще могут на нее ссылаться;
- метрики Feature VM помогают понять, что именно было ускорено до pipeline;
- она может быть safe fallback для экспериментов.

Feature VM имеет собственные классы:

- `FeaturePlacementCompiler`;
- `FeatureProgram`;
- `FeatureOpcode`;
- `FeatureScratch`;
- `FeatureProgramCache`;
- `FeatureVmMetrics`.

Для нового разработчика важно не путать две системы. Feature VM выполняет один placed feature. Decoration Pipeline строит план для всего biome decoration step и выбирает features по biome masks.

Если нужно менять новый decoration path, сначала смотрите `common/features/pipeline`, а не `common/features/vm`.

## 34. Почему порядок и seed важнее скорости

Decoration generation наблюдаема. Игрок видит, где выросло дерево, где появилась руда и где стоит цветок. Поэтому нельзя просто переставить features ради скорости.

В vanilla seed для feature задается примерно так:

```text
random.setFeatureSeed(decorationSeed, featureIndex, step)
```

Pipeline делает это перед каждым kernel:

```text
context.random.setFeatureSeed(context.decorationSeed, featureIndex, step)
```

Это сохраняет важное свойство: feature с тем же index в том же step получает тот же random sequence.

Также важен порядок features внутри step. Даже если selected mask хранится как bits, executor проходит bits по возрастанию feature index. Поэтому порядок соответствует массиву features.

Нельзя выполнять feature 10 раньше feature 9, даже если feature 10 native, а feature 9 fallback. Записи в мир могут влиять друг на друга.

То же касается structures и carvers: некоторые системы используют masks, first-writer behavior и postprocessing. Любая оптимизация должна сохранять порядок там, где он может повлиять на итоговый chunk.

## 35. Surface System: что оптимизируется

Surface generation отвечает за верхние слои terrain: grass, dirt, sand, snow, terracotta bands, ocean floor и другие surface blocks.

В vanilla surface rules работают как дерево правил. Для каждого столбца и Y позиция проверяется набором conditions. Если condition подходит, rule возвращает block state.

Проблема похожа на decoration:

- много маленьких объектов context/condition;
- много виртуальных вызовов;
- повторные проверки одного и того же condition;
- работа по одному block pos вместо section masks;
- сложные modded rule trees, где fallback неизбежен.

Новый `Surface Rule Compiler` пытается выполнить surface rules по section, а не по одному блоку. Он строит `Mask4096` для blocks, которые еще нужно обработать, и применяет правила к маскам.

Вместо вопроса:

```text
какой block поставить в этой одной точке?
```

новый код задает вопрос:

```text
для каких blocks в этой section это правило подходит?
```

Это позволяет одним condition pass обработать сразу много позиций.

## 36. Новый `buildSurface` flow

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/mixin/SurfaceSystem$new_build_surface.java
```

Mixin overwrite-ит `SurfaceSystem.buildSurface`.

Упрощенный flow:

```text
SurfaceProgramCache.getOrCompile(ruleSource)
prepare VectorChunkContext
build surface height map
prepare biome lookup for 16x16 columns
run badlands extension early
remember if frozen ocean exists
prepare caches required by SurfaceProgram
for each section top-down:
    get raw int[4096]
    build/load stoneMask
    apply SurfaceProgram to raw array
    postprocess fluid writes if needed
run frozen ocean extension if needed
cleanup ThreadLocal scratch/context
```

Top-down проход важен для stone depth. Surface rules часто спрашивают, сколько stone blocks above/below. Чтобы считать это по sections, код хранит `previousSectionBottomDepths` для 256 columns.

`rawBlockData` берется из `LevelChunkSection$FlatBlockArray`. Если raw array недоступен, section пропускается и метрика `rawBlockArrayMiss` увеличивается. Это сигнал, что flat block patch не сработал или section не была распакована.

В конце `finally` очищает ссылки:

- biome lookup dispose;
- массив `surfaceBiomes` заполняется null;
- `VectorBlockColumn.clear()`;
- `VectorChunkContext.clear()`.

Это нужно, чтобы ThreadLocal не удерживал chunk, biome holders и large arrays дольше, чем надо.

## 37. `SurfaceProgramCache`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/SurfaceProgramCache.java
```

Surface rules обычно одинаковые для многих chunks одного dimension. Поэтому компилировать `RuleSource` каждый раз нельзя.

Cache хранит `SurfaceRules.RuleSource -> SurfaceProgram`. Используется Caffeine weak-key cache и маленький `lastEntry` fast path.

Почему last entry полезен:

- buildSurface вызывается много раз подряд;
- чаще всего `ruleSource` один и тот же object;
- volatile last entry дешевле, чем полный cache lookup.

Метрики cache:

- `cacheHits`;
- `cacheMisses`;
- `lastEntryHits`;
- `compileTime`;
- `cacheLookupTime`.

Cache очищается через `GARuntimeCaches.resetForServerLifecycle()`. Это важно для server reload, datapack reload и смены мира. Нельзя держать старые rule objects бесконечно.

## 38. Режимы `SurfaceRuleCompiler`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/SurfaceRuleCompiler.java
```

Compiler имеет два основных режима:

1. IR compiler.
2. Legacy builder.

Настройки находятся в `SurfaceCompilerConfig`:

```text
ga.surface.compiler.ir=true
ga.surface.compiler.dag=true
ga.surface.compiler.columnInterval=true
ga.surface.compiler.dump=false
ga.surface.metrics=false
```

`ga.surface.compiler.ir` включает новый IR path. Если IR path падает с runtime exception, compiler увеличивает `irFallback` и компилирует legacy path.

`ga.surface.compiler.dag` включает оптимизацию IR, если builder считает дерево безопасным кандидатом.

`ga.surface.compiler.columnInterval` включает lower в specialized program steps для column/interval conditions.

`ga.surface.compiler.dump` печатает/сохраняет структуру compiled plan через `SurfacePlanDump`. Это отладочная опция, не обычный production mode.

`ga.surface.metrics` включает счетчики и timers.

## 39. Legacy builder

Legacy builder находится внутри `SurfaceRuleCompiler.Builder`.

Он не строит новый IR. Он напрямую превращает `SurfaceRules.RuleSource` в дерево `SurfaceRuleNode`:

- `BlockSurfaceRuleNode`;
- `SequenceSurfaceRuleNode`;
- `TestSurfaceRuleNode`;
- `TestBlockSurfaceRuleNode`;
- bridge nodes для vector fallback;
- cached condition nodes.

Legacy builder нужен по нескольким причинам.

Во-первых, это fallback для IR. Если IR не поддержал какую-то форму rule tree, legacy path все еще может собрать исполняемую программу.

Во-вторых, он поддерживает modded rules через bridge. Например, если rule source неизвестен compiler-у, можно использовать `VectorRuleCompiler` или legacy vanilla rule object.

В-третьих, это baseline для parity tests. Если IR path меняется, можно сравнить его поведение с legacy compiled nodes.

Legacy path тоже быстрее vanilla, потому что он работает с masks и caches, но IR path дает больше возможностей для оптимизации и specialized lowering.

## 40. Данные для modded surface compiler

В пакете compiler есть отдельные файлы для популярных модов:

```text
BiomesWeveGoneSurfaceCompilerData.java
LithostitchedSurfaceCompilerData.java
TerrablenderSurfaceCompilerData.java
```

Они нужны, потому что моды добавляют свои `RuleSource` и `ConditionSource`. Для Java compiler это часто private/unknown классы. Без специальных adapters такие nodes пришлось бы всегда отправлять в fallback bridge.

Compiler проверяет загрузку классов:

```text
Lithostitched
TerraBlender
BiomesWeveGone
```

Если мод загружен, compiler может распознать часть его rules и conditions. Если мод не загружен, код не должен ссылаться на его классы напрямую при class loading.

Поэтому важно держать такие integrations изолированными. Нельзя добавить hard import на optional mod class в общий hot class, если этот class будет загружен без мода.

Если новый мод добавляет surface rules, правильный путь - сделать маленький compiler data adapter и fallback bridge, а не переписывать общий compiler под конкретный мод.

## 41. Surface IR

IR означает intermediate representation. Это промежуточное представление surface rules, более простое, чем Minecraft objects.

IR classes находятся здесь:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/ir
```

Основные rule nodes:

- `SurfaceRuleIR.Empty`;
- `SurfaceRuleIR.Block`;
- `SurfaceRuleIR.Sequence`;
- `SurfaceRuleIR.Test`;
- `SurfaceRuleIR.FallbackRule`.

Основные condition nodes:

- `BiomeCondition`;
- `StoneDepth`;
- `Y`;
- `NoiseThreshold`;
- `VerticalGradient`;
- `AbovePreliminarySurface`;
- `Water`;
- `Temperature`;
- `Steep`;
- `Hole`;
- `Not`;
- `AllOf`;
- `AnyOf`;
- `FallbackCondition`;
- `Constant`.

Зачем IR нужен:

- проще делать constant folding;
- проще объединять conditions;
- проще считать requirements;
- проще lower-ить в specialized section program;
- проще fallback-ить только неизвестные islands.

IR не обязан представлять все surface rules идеально. Если node неизвестен, он становится fallback node. Это сохраняет совместимость.

## 42. `SurfaceIRBuilder`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/SurfaceIRBuilder.java
```

Builder берет `SurfaceRules.RuleSource` и строит `SurfaceRuleIR`.

Он делает несколько локальных оптимизаций уже при построении:

- разворачивает nested `SequenceRuleSource` в один список;
- убирает empty rules;
- если в sequence встретился unconditional block rule, удаляет все правила после него;
- объединяет цепочки `TestRuleSource` в один `AllOf` condition;
- упрощает `not(not(x))`;
- считает reuse count для conditions;
- отмечает, можно ли запускать full optimizer.

Пример:

```text
test(A, test(B, block(DIRT)))
```

становится примерно:

```text
test(allOf(A, B), block(DIRT))
```

Это полезно, потому что lowerer потом может один раз построить mask для `allOf(A, B)`.

Builder также считает:

- `fallbackRuleCount`;
- `fallbackConditionCount`;
- `conditionUseCounts`.

Эти числа потом попадают в metrics/dump.

## 43. Безопасность reorder

Оптимизатор иногда хочет поменять порядок conditions: дешевые conditions проверять раньше дорогих. Например, biome check может быть дешевле noise threshold.

Но reorder разрешен не всегда.

Condition можно переставлять, только если он pure и не имеет наблюдаемых side effects. В surface rules side effects обычно неявные: random, noise sampling, bridge к модовому rule, cache state, vanilla context behavior.

Поэтому optimizer использует conservative checks:

```text
isReorderSafe(condition)
```

Если хотя бы один condition небезопасен, порядок в boolean list сохраняется.

Это особенно важно для fallback conditions. Модовый condition может внутри читать random или mutable context. Даже если он выглядит как predicate, compiler не должен менять порядок его вызовов.

Практическое правило: если не доказано, что condition safe, он не reorder-ится.

## 44. `SurfaceIROptimizer`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/SurfaceIROptimizer.java
```

Optimizer работает над IR и делает более глубокие преобразования, чем builder.

Основные действия:

- constant folding для `true`/`false`;
- удаление unreachable branches;
- flatten nested `Sequence`, `AllOf`, `AnyOf`;
- удаление duplicate conditions;
- поиск complement pairs, например `A` и `not(A)`;
- объединение adjacent same-block rules;
- сортировка safe conditions по примерной стоимости;
- повторный подсчет condition use counts после оптимизации.

Пример безопасной оптимизации:

```text
allOf(A, true, A)
```

становится:

```text
A
```

Пример невозможного условия:

```text
allOf(A, not(A))
```

становится:

```text
false
```

Optimizer запускается только если `ga.surface.compiler.dag=true` и builder считает дерево full optimizer candidate. Это снижает риск на сложных/fallback-heavy modded trees.

## 45. `SurfaceIRLowerer`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/SurfaceIRLowerer.java
```

Lowerer превращает IR в `SurfaceProgram`.

На верхнем уровне он кодирует правила в arrays:

```text
opcodes
intOperands
objectOperands
steps
```

Основные opcodes:

```text
OP_BLOCK
OP_TEST_BLOCK
OP_RULE
```

Если rule - просто block, lowerer делает `OP_BLOCK`. Если rule - `test(condition, block)`, он делает `OP_TEST_BLOCK`. Если rule сложнее, он делает generic `OP_RULE` с `SurfaceRuleNode`.

Если включен `columnInterval`, lowerer пытается заменить generic mask condition на specialized step:

- `ColumnTestBlockProgramStep`;
- `IntervalTestBlockProgramStep`;
- `ColumnIntervalTestBlockProgramStep`;
- `MinYTestBlockProgramStep`;
- `AnchorYTestBlockProgramStep`;
- другие specialized steps из `SurfaceProgramSteps.java`.

Lowerer также решает, какие conditions cache-ить. Если condition встречается больше одного раза и cacheable, он получает cache slot в `SurfaceScratch`.

## 46. Column и interval conditions

Surface rules часто зависят не от каждого block отдельно, а от целой колонки или вертикального диапазона.

Column condition отвечает на вопрос:

```text
какие x/z колонки подходят?
```

Interval condition отвечает на вопрос:

```text
с какого localY в этой колонке правило начинает подходить?
```

Это сильно быстрее, чем строить полный `Mask4096`, когда правило имеет простую форму. Например, Y condition часто можно превратить в minimum local Y. Тогда вместо проверки 4096 bits можно применить block state ко всем active bits выше границы.

В `SurfaceProgramSteps.java` для этого есть interfaces:

```text
ColumnConditionPlan
IntervalConditionPlan
```

И steps, которые работают с 256 columns:

```text
activeMask.computeActiveColumns(...)
condition.filterColumns(...)
activeMask.applyBlockStateAndClearAbove(...)
```

Это одна из главных оптимизаций Surface Rule Compiler. Она переводит часть работы из 4096 block checks в 256 column checks или даже в простой fill по диапазонам.

## 47. `SurfaceProgram`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/SurfaceProgram.java
```

`SurfaceProgram` - это исполняемый результат компиляции.

Он хранит:

- opcodes;
- int operands, обычно block state ids;
- object operands, обычно condition/rule nodes;
- optional specialized `SurfaceProgramStep[]`;
- requirements bitmask;
- fallback island count;
- flag `mayWriteFluid`.

Главный метод:

```text
apply(int[] rawBlockData, Mask4096 stoneMask, VectorChunkContext ctx, SurfaceScratch scratch)
```

Он начинает section так:

```text
scratch.beginSection()
scratch.activeMask.copyFrom(stoneMask)
```

`activeMask` означает: эти blocks еще не получили surface block от предыдущих rules.

После каждого matching rule matched bits удаляются из active mask:

```text
activeMask.andNot(matchingMask)
```

Если `activeMask` пуст, программа выходит раньше. Это сохраняет vanilla rule semantics: первое подходящее правило выигрывает, правила после него уже не должны менять эти blocks.

## 48. `Mask4096`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/mask/Mask4096.java
```

`Mask4096` - bit mask для одной section 16x16x16.

Всего blocks:

```text
16 * 16 * 16 = 4096
```

Внутри:

```text
long[64]
```

Индекс block:

```text
(localY << 8) | (localZ << 4) | localX
```

Такой же layout используется в raw block array. Поэтому mask bit index напрямую соответствует index в `int[4096]`.

Основные операции:

- `loadMatchingBlockIds` - построить mask по block id;
- `and`, `or`, `andNot`, `xor`;
- `computeActiveColumns` - свернуть 4096 bits в 256 column bits;
- `applyBlockState` - записать state id во все set bits;
- `applyBlockStateAndClearAbove` - записать и убрать bits выше Y границы;
- `clearColumn` / `clearColumnBelow`.

`Mask4096` - центральная data structure surface compiler. Если в нем ошибка индексации, surface будет ломаться очень странно. Поэтому формулу индекса лучше не менять без отдельного parity test.

## 49. `SurfaceScratch`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/SurfaceScratch.java
```

`SurfaceScratch` - переиспользуемая рабочая память для surface program.

В нем есть:

- `stoneMask`;
- `activeMask`;
- stack transient masks;
- condition mask cache;
- column condition cache;
- interval minY cache;
- `previousSectionBottomDepths`;
- arrays для active/candidate columns;
- `intervalMinY`;
- mutable positions для postprocessing.

Перед каждой section вызывается:

```text
scratch.beginSection()
```

Это сбрасывает transient stack и увеличивает generation counter для condition caches. Старые cached masks не чистятся полностью каждый раз; они становятся невалидными по generation id. Это дешевле, чем делать `Arrays.fill` для всех cache slots на каждую section.

Transient masks работают как stack:

```text
int mark = scratch.mark()
Mask4096 tmp = scratch.pushMaskForOverwrite()
...
scratch.restore(mark)
```

Так код избегает создания временных `Mask4096` объектов внутри conditions.

## 50. `VectorChunkContext`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/vector/VectorChunkContext.java
```

`VectorChunkContext` хранит данные, которые нужны surface conditions сразу для всей section или chunk.

Главные поля:

- `surfaceBiomes[256]`;
- `surfaceHeights[256]`;
- `surfaceDepths[256]`;
- `secondarySurfaceNoises[256]`;
- `stoneDepthAbove[4096]`;
- `stoneDepthBelow[4096]`;
- `minSurfaceLevels[256]`;
- `waterHeights[256]`;
- section start X/Y/Z;
- default block ids: stone, air, water.

Методы подготовки:

- `buildDepthMap(chunk)`;
- `prepareSurfaceDepthCache(...)`;
- `prepareSecondarySurfaceNoiseCache(...)`;
- `preparePreliminarySurface(...)`;
- `calculateStoneDepthsAndLoadStoneMask(...)`;
- `updateForSection(...)`.

Context называется vector, потому что conditions стараются работать не с одной позицией, а с массивом позиций. Например, biome condition может отфильтровать 256 columns, а Y condition может построить interval для всех columns.

После buildSurface context очищается, чтобы не держать ссылки на chunk/randomState/surfaceSystem.

## 51. Surface requirements

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/surface/compiler/SurfaceRequirements.java
```

Surface program заранее сообщает, какие данные ему нужны. Это bitmask:

```text
BIOME
STONE_DEPTH
WATER
SURFACE_DEPTH
PRELIMINARY_SURFACE
TEMPERATURE
NOISE
RANDOM
SLOPE
FALLBACK
SECONDARY_SURFACE
```

`buildSurface` читает requirements и готовит только нужные caches.

Например:

- если нет `STONE_DEPTH` и `WATER`, не нужно считать `stoneDepthAbove/Below`;
- если нет `SECONDARY_SURFACE`, не нужно готовить secondary noise cache;
- если нет `PRELIMINARY_SURFACE`, не нужно вызывать `NoiseChunk.preliminarySurfaceLevel`;
- если нет `SURFACE_DEPTH`, не нужно считать surface depth cache, кроме frozen ocean special case.

Это простая, но важная оптимизация. Подготовка caches может стоить дорого, а многие dimensions/rule sets используют только часть условий.

Requirements собираются во время lowering через facts по IR/rule nodes. Если есть fallback node, requirements обычно становятся более широкими, потому что неизвестный код может спросить больше данных.

## 52. Fallback islands в surface compiler

Fallback island - это участок rule tree, который compiler не смог понять как native IR/node, но может встроить через bridge.

Пример:

```text
Sequence(
  known biome test -> known block,
  unknown modded rule,
  known y test -> known block
)
```

Здесь неизвестный modded rule становится fallback island. Остальные known rules могут остаться optimized.

Это лучше, чем fallback всего surface program. Модовый участок будет медленнее, но весь vanilla-compatible surrounding code все равно ускоряется.

Метрики:

- `fallbackIslands`;
- `fallbackRuleClasses`;
- `fallbackConditionClasses`;
- `fallbackRuleBridgeTime`;
- `fallbackConditionBridgeTime`.

Если в diagnostics видно много fallback islands от одного класса, это хороший кандидат для нового compiler adapter.

Важно: fallback island должен уважать active mask. Он не должен переписывать blocks, которые уже matched предыдущим rule.

## 53. Surface postprocessing

Surface rules иногда пишут blocks с fluid state. Например, waterlogged blocks или liquid surface blocks.

Если `SurfaceProgram.mayWriteFluid()` true, после применения program код проходит по `stoneMask` и ищет измененные blocks, у которых новый state имеет fluid.

Если fluid есть, chunk получает:

```text
pChunk.markPosForPostprocessing(pos)
```

Почему проход идет по `stoneMask`, а не по всей section? Surface program должен менять только default blocks, которые попали в stone mask. Это сужает scan.

`mayWriteFluid` считается при compile/lower. Если все block rules пишут dry states, postprocess pass не нужен.

Нельзя просто убрать postprocessing ради скорости. Без него fluids могут не обновиться корректно после generation.

## 54. Frozen ocean и badlands

В vanilla `SurfaceSystem` содержит специальные extension methods:

```text
erodedBadlandsExtension(...)
frozenOceanExtension(...)
```

Новый buildSurface сохраняет их.

Badlands:

- во время biome prep определяется `Biomes.ERODED_BADLANDS`;
- для таких columns вызывается `erodedBadlandsExtension`;
- используется `VectorBlockColumn`, чтобы vanilla method мог читать/писать column-like API.

Frozen ocean:

- во время biome prep ставится flag `hasFrozenOcean`;
- если он true, дополнительно готовятся surface depth и preliminary surface caches;
- после section program отдельным проходом вызывается `frozenOceanExtension` для frozen ocean columns.

Это пример области, где optimization не должна пытаться быть слишком clever. Эти vanilla extensions имеют сложное поведение и должны остаться в правильном месте flow.

## 55. Surface compiler dump и metrics

`SurfaceMetrics` собирает counters и timers для compiler и executor.

Важные counters:

- `compiledPrograms`;
- `irPrograms`;
- `irFallbacks`;
- `interpretedPrograms`;
- `optimizedPrograms`;
- `cacheHits/cacheMisses/lastEntryHits`;
- `sectionsProcessed`;
- `emptySectionsSkipped`;
- `stonelessSectionsSkipped`;
- `rawBlockArrayMisses`;
- `fallbackIslands`;
- `conditionCacheHits/Misses`;
- `activeMaskEarlyExits`.

Важные timers:

- cache lookup;
- compile;
- biome prep;
- surface depth;
- secondary surface;
- preliminary surface;
- stone depth;
- stone mask load;
- program apply;
- fluid postprocess;
- frozen ocean;
- fallback bridges.

`SurfacePlanDump` используется при `ga.surface.compiler.dump=true`. Dump помогает понять, какой root node получился, сколько cache slots создано и какие classes ушли в fallback.

В production metrics выключены по умолчанию. Их включает diagnostics command или JVM property.

## 56. Flat block array

Интерфейс:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/flat_block_structure/LevelChunkSection$FlatBlockArray.java
```

Mixin:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/flat_block_structure/mixin/MixinLevelChunkSection$flat_block_array.java
```

Flat block array добавляет к `LevelChunkSection` optional raw storage:

```text
int[4096]
```

Каждый int - fast id `BlockState`. Layout такой же, как у `Mask4096`:

```text
(y << 8) | (z << 4) | x
```

Основные методы:

- `bts$unpackForGeneration()` - распаковать `PalettedContainer` в raw array;
- `bts$getRawBlockData()` - получить raw array или null;
- `bts$packAndFreeze()` - записать raw data обратно в palette и вернуть array в pool.

Почему это важно:

- surface compiler пишет blocks через plain int array;
- descriptors могут scan-ить raw section быстрее;
- carver/decorations могут читать fast ids;
- меньше `BlockState` object access в inner loop.

При pack пересчитываются counts:

- `nonEmptyBlockCount`;
- `tickingBlockCount`;
- `tickingFluidCount`.

Это нужно, чтобы vanilla chunk section оставалась консистентной.

## 57. `FastBlockStateCache`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/api/structures/FastBlockStateCache.java
```

Fast id - это global palette id для `BlockState`. Ветка активно использует ids вместо `BlockState` references в hot arrays.

`FastBlockStateCache` хранит:

```text
BlockState[] STATES
boolean[] AIR_STATES
```

`init` проходит по `BuiltInRegistries.BLOCK`, берет все possible states и заполняет arrays. Также он записывает fast id в `GA$BlockStateExtension` для каждого state.

Почему capacity считается через max state id, а не только registry size:

- global state ids могут быть не плотными;
- modded registries могут вести себя не так, как ожидается;
- безопаснее иметь array до max id + 1.

Если id выходит за array, cache fallback-ит в `Block.stateById(id)`. Если id отрицательный, возвращается air.

`isAir(id)` тоже fast path. Это важно для loops, где нужно часто отличать air от non-air без получения `BlockState`.

## 58. Heightmap optimizations

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/heightmap/mixin/MixinHeightmap$optimize_logic.java
```

Оптимизированы две части.

Первая - `Heightmap.update`. Если block удалили на top, vanilla ищет следующий opaque block вниз. Новый код пропускает sections, где `hasOnlyAir()`, и не сканирует 16 пустых Y подряд.

Вторая - `Heightmap.primeHeightmaps`. Новый код:

- не создает лишние lists в hot loop;
- использует bit mask `remaining` для набора heightmaps, которые еще не нашли top;
- имеет special path для feature heightmap set из четырех типов:
  - `MOTION_BLOCKING`;
  - `MOTION_BLOCKING_NO_LEAVES`;
  - `OCEAN_FLOOR`;
  - `WORLD_SURFACE`.

Это важно для decoration, потому что features часто используют heightmap placement. Если heightmaps priming дешевле, вся feature generation становится дешевле.

## 59. Carver optimizations

Новые carver classes находятся здесь:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/carver
```

И mixins:

```text
common/src/main/java/dev/sixik/generator_accelerator/mixins/common_mixin/MixinNoiseBasedChunkGenerator$optimize_apply_carvers.java
common/src/main/java/dev/sixik/generator_accelerator/mixins/common_mixin/MixinWorldCarver.java
common/src/main/java/dev/sixik/generator_accelerator/mixins/common_mixin/MixinCaveWorldCarver.java
common/src/main/java/dev/sixik/generator_accelerator/mixins/common_mixin/MixinCanyonWorldCarver.java
common/src/main/java/dev/sixik/generator_accelerator/mixins/common_mixin/MixinProtoChunk$carver_plan_cache.java
```

Цель carver changes - уменьшить повторную работу при carving и ускорить inner loops cave/canyon.

Основные идеи:

- build carver plan для source chunk;
- сохранить replay seeds для vanilla carvers, где это безопасно;
- кешировать replaceable blocks per `CarverConfiguration`;
- использовать scratch objects для cave/canyon state;
- использовать `CarverChunkWriter` для быстрых block writes;
- оптимизировать skip checkers.

Carving особенно чувствителен к порядку. В `CarverChunkPlan` есть комментарий, что replay и fallback entries остаются interleaved, потому что carving mask делает first-writer order наблюдаемым.

## 60. `CarverChunkPlan`

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/common/carver/CarverChunkPlan.java
```

Plan строится для source chunk и carving step.

Он хранит arrays:

```text
ConfiguredWorldCarver<?>[] carvers
long[] replaySeeds
int[] fallbackIndexes
```

Для vanilla cave/canyon/nether cave можно replay-ить seed:

1. Во время build вызывается `setLargeFeatureSeed`.
2. Если carver является start chunk, сохраняется internal legacy random seed.
3. Во время carve seed восстанавливается и carver запускается без повторного `isStartChunk`.

Если carver неизвестен или seed replay небезопасен, plan хранит fallback index. Тогда во время carve код делает vanilla-like `setLargeFeatureSeed` и снова вызывает `isStartChunk`.

Так plan ускоряет common vanilla case, но сохраняет safe behavior для неизвестных carvers.

`CarverChunkPlan.EMPTY` используется, когда в chunk нет carvers для step.

## 61. `WorldCarver` fast path

`MixinWorldCarver` и связанные cave/canyon mixins ускоряют внутренний carving loop.

Ключевые идеи:

- меньше создавать `BlockPos`/contexts;
- использовать mutable function context;
- кешировать replaceable predicates;
- использовать specialized skip checker для cave/canyon;
- быстрее читать/писать block states через writer;
- не делать дорогую top material логику, если fast property разрешает skip.

Есть JVM properties:

```text
ga.carver.fastSimpleCaveState=true
ga.carver.fastSkipTopMaterial=true
ga.carver.fastTunnelStride=2
ga.carver.fastTunnelMinRadius=1.85
```

Эти flags нужны для тонкой настройки и отладки. Если carver regression подозревается, можно отключать fast pieces отдельно.

Carver fast path должен быть особенно осторожен с fluids, aquifer и carving mask. Ошибка здесь может давать дырки, неправильную лаву/воду или отличия cave shape.

## 62. DFC changes в этой ветке

Density Function Compiler уже был отдельной большой подсистемой. В этой ветке он не главный фокус, но получил важные изменения.

Изменены/добавлены файлы:

```text
GARuntimeCaches.java
DensityFunctionCompiler.java
DfcCellFillStats.java
DfcCompiledClassRegistry.java
DfcNativePlanningStats.java
DfcSplineStats.java
MarkerRewriter.java
GlobalCompileCache.java
Codegen.java
CompiledDensityFunction.java
IRBuilder.java
NoiseSpecCache.java
RegistryWarmer.java
NativeNoiseRegistry.java
```

Главные темы изменений:

- lifecycle reset через `GARuntimeCaches`;
- очистка global compile caches при server reload;
- статистика cell fill, native planning и spline runtime;
- opt-in warming raw density functions;
- `MarkerRewriter` для нормализации/переписывания marker nodes;
- больше информации о native handles/noise specs;
- улучшения spline search/codegen stats;
- безопаснее работа compiled class registry.

Подробная документация по DFC уже есть отдельно:

```text
docs/DENSITY_FUNCTION_COMPILER.md
```

В этом документе важно понимать связь: surface, decoration и DFC теперь разделяют lifecycle reset и diagnostics. Если кеши не очистить вместе, можно получить старые compiled objects после reload.

## 63. Runtime cache reset

Файл:

```text
common/src/main/java/dev/sixik/generator_accelerator/GARuntimeCaches.java
```

Единая точка сброса:

```text
resetForServerLifecycle()
```

Она очищает:

- feature cache epoch;
- decoration pipeline quarantine/session caches;
- shared weak caches;
- surface program cache;
- carver replaceable cache;
- density compiler caches;
- DFC stats;
- registry warmer state.

Зачем это нужно:

- datapack reload может заменить biomes/features/surface rules;
- server stop/start не должен использовать старые objects;
- weak caches не гарантируют немедленную очистку;
- diagnostics после reset должны начинаться с понятной baseline.

Если добавляется новый global/static cache, нужно почти всегда подумать, должен ли он очищаться здесь.

## 64. Diagnostics

Новые файлы:

```text
common/src/main/java/dev/sixik/generator_accelerator/diagnostics/GADiagnostics.java
common/src/main/java/dev/sixik/generator_accelerator/diagnostics/GADiagnosticsCommands.java
common/src/main/java/dev/sixik/generator_accelerator/diagnostics/DiagnosticsJson.java
docs/DIAGNOSTICS.md
```

Команды:

```text
/ga diagnostics
/ga diagnostics status
/ga diagnostics start
/ga diagnostics reset
/ga diagnostics dump
/ga diagnostics stop
/ga diagnostics folder
```

Diagnostics собирают:

- heap/non-heap memory;
- GC totals and delta;
- thread/class/JIT data;
- filtered system properties;
- GA config flags;
- decoration pipeline metrics;
- surface compiler metrics;
- Feature VM metrics;
- DFC stats;
- optional JFR recording;
- ZIP bundle.

Обычный пользователь не должен включать это постоянно. Diagnostics - инструмент для воспроизведения лагов/аллокаций в модпаке.

Команда `start/reset` также включает runtime metrics flags через system properties. Поэтому можно включить metrics без перезапуска JVM.

## 65. Config и JVM properties

Основные GA config overrides:

```text
ga.config.enableAquiferPatch
ga.config.enableBeardifierPatch
ga.config.enableBiomePatch
ga.config.enableBlenderPatch
ga.config.enableDensityCompilerPatch
ga.config.enableFeaturesPatch
ga.config.enableFlatBlockStructurePatch
ga.config.enableHeightmapPatch
ga.config.enableNoisePatch
ga.config.enableNoiseNativePatch
ga.config.enablePalettedContainerPatch
ga.config.enableStructuresPatch
ga.config.enableSurfacePatch
```

Benchmark helpers:

```text
ga.benchmark.disableAllPatches
ga.benchmark.featuresOnly
ga.benchmark.featureVmOnly
ga.benchmark.featureVm
```

Decoration/feature metrics:

```text
ga.decorationPipeline.metrics
ga.featureVm.metrics
ga.features.memoryDebug
ga.features.memoryDebugEvery
```

Surface compiler:

```text
ga.surface.metrics
ga.surface.compiler.ir
ga.surface.compiler.dag
ga.surface.compiler.columnInterval
ga.surface.compiler.dump
```

Flat block array:

```text
ga.flatBlockArray.rawPoolMax
```

Diagnostics/JFR:

```text
ga.diagnostics.enabled
ga.diagnostics.jfr
ga.diagnostics.jfr.allocations
ga.diagnostics.jfr.allocationSamples
ga.diagnostics.jfr.maxSizeBytes
ga.diagnostics.jfr.maxAgeSeconds
ga.diagnostics.jfr.settings
ga.diagnostics.jfr.sampleMs
ga.diagnostics.dumpDir
```

DFC properties начинаются с `dfc.*`. Их лучше смотреть в `docs/DENSITY_FUNCTION_COMPILER.md` и diagnostics output.

## 66. Build, tests, benchmarks

Ветка добавляет tests и benchmark tasks.

Команды из `COMMANDS.md`:

```text
./gradlew :common:surfaceQuickBenchmark
./gradlew :common:surfaceApplyQuickBenchmark
./gradlew :common:surfaceApplyQuickBenchmark -PsurfaceColumnInterval=false
./gradlew :common:surfaceQuickBenchmark -PsurfaceDag=false
./gradlew runDecorationPipelineQuickBenchmarks -PbenchRuns=1
./gradlew runDecorationPipelineQuickBenchmarks -PbenchRuns=1 -PquickPipelineMetrics=true
./gradlew runDecorationPipelineBenchmarks -PbenchRuns=1
```

## 67. Как читать Decoration Pipeline с нуля

Если вы впервые открываете этот код, не начинайте с `DecorationPlacementProgram.java`. Это самый большой и самый плотный файл.

Лучший порядок чтения:

1. `MixinChunkGenerator$apply_biome_decoration.java`
2. `StepFeatureCache.java`
3. `BiomeDecorationScratch.java`
4. `BiomeSignatureFeatureMaskCache.java`
5. `JavaDecorationCompiler.java`
6. `DecorationPlan.java`, `DecorationStepPlan.java`, `DecorationKernelPlan.java`
7. `DecorationPipelineExecutor.java`
8. `DecorationPlacementProgram.java`
9. `SectionDescriptor.java`, `SectionDescriptorCache.java`
10. tests в `common/src/test/java/.../features/pipeline`

На первом проходе ищите не детали placement, а границы ответственности.

Пример mental model:

```text
Mixin собирает входные данные чанка.
StepFeatureCache говорит, какие features вообще возможны.
Compiler заранее классифицирует features.
Executor сохраняет порядок и seed.
PlacementProgram исполняет modifier chain.
Kernel пишет blocks или вызывает fallback.
Descriptors помогают пропускать невозможные попытки.
```

Если баг связан с тем, что feature не появилась, смотрите selection mask и descriptor gate. Если feature появилась не там, смотрите placement opcode и RNG order. Если crash только с модом, смотрите quarantine/fallback.

## 68. Пример: простой grass patch

Упрощенный путь для grass/flower-like patch:

```text
Biome contains placed feature: random_patch -> simple_block
applyBiomeDecoration selects feature bit for current step
Executor sets feature seed
DecorationPlacementProgram runs outer placement modifiers
Random patch native loop creates tries
Nested simple block state is sampled
canSurvive is checked
block is written through chunkWriter
SectionDescriptorCache notes mutation
```

Какие fast paths могут включиться:

- feature selection через biome mask;
- placement opcodes вместо streams;
- `NATIVE_RANDOM_PATCH_SIMPLE`;
- simple block batch при большом tries;
- raw fast id checks;
- descriptor updates by section column.

Где возможен fallback:

- unknown placement modifier;
- modded state provider с нестандартным behavior;
- exception в native path;
- unsupported random patch nested feature.

Важно: даже если patch native, `canSurvive` должен остаться реальным. Иначе можно поставить растения в воздух, воду или на неподходящий block.

## 69. Пример: ore feature

Упрощенный путь для ore:

```text
Feature.ORE recognized by JavaDecorationCompiler
OreTargetCompiler builds OreTargetPlan
DecorationKernelPlan kind = NATIVE_ORE
Biome mask selects feature
Executor sets feature seed
PlacementProgram calculates origin positions
Descriptor gate checks possible target sections
Ore native kernel samples vein positions
OreTargetMatcher checks current block by fast id
Writer sets target block state
Descriptors and heightmaps update if needed
```

Если ore target сложный, compiler может выбрать partial path:

```text
PARTIAL_NATIVE_DESCRIPTOR_GATED
```

Тогда placement и descriptor skip остаются быстрыми, но actual ore placement вызывает vanilla configured feature.

При отладке ore обращайте внимание на три вещи:

- target plan: какие states считаются заменяемыми;
- descriptor gate: не отсекает ли section слишком агрессивно;
- RNG calls: не изменился ли vein shape из-за лишнего/недостающего random call.

## 70. Как читать Surface Rule Compiler с нуля

Рекомендуемый порядок:

1. `SurfaceSystem$new_build_surface.java`
2. `SurfaceProgramCache.java`
3. `SurfaceRuleCompiler.java`
4. `SurfaceIRBuilder.java`
5. `SurfaceRuleIR.java`, `SurfaceConditionIR.java`
6. `SurfaceIROptimizer.java`
7. `SurfaceIRLowerer.java`
8. `SurfaceProgram.java`
9. `SurfaceProgramSteps.java`
10. `Mask4096.java`
11. `SurfaceScratch.java`
12. `VectorChunkContext.java`
13. `SurfaceCompilerParityTest.java`

Mental model:

```text
RuleSource -> IR -> optimized IR -> SurfaceProgram -> section masks -> raw int[4096]
```

Не пытайтесь сразу понять все vector fallback rules. Сначала поймите `activeMask` и `stoneMask`. Это ядро системы.

`stoneMask` - blocks, которые можно заменить surface rules.

`activeMask` - subset stoneMask, который еще не matched предыдущими rules.

Каждое правило уменьшает `activeMask`. Когда mask пуст, section готова.

## 71. Пример: surface sequence

Допустим, surface rules выглядят так:

```text
sequence(
  if biome(desert) then sand,
  if y_above(60) then grass,
  dirt
)
```

IR примерно:

```text
Sequence[
  Test(Biome(desert), Block(sand)),
  Test(Y(60), Block(grass)),
  Block(dirt)
]
```

Execution по section:

1. `activeMask = stoneMask`.
2. Biome condition строит mask desert columns/blocks.
3. Sand пишется в matching bits.
4. Эти bits удаляются из `activeMask`.
5. Y condition применяется только к оставшимся bits.
6. Grass пишется и удаляется из active.
7. Unconditional dirt пишет все, что осталось.
8. `activeMask` очищается, program exits.

Так сохраняется правило vanilla sequence: первое подходящее правило выигрывает.

Если compiler смог lower-ить biome condition как column plan, первый шаг может работать на 256 columns, а не на 4096 blocks.

## 72. Decoration invariants

При изменении Decoration Pipeline нельзя нарушать эти правила:

- Порядок features внутри step должен совпадать с vanilla feature index order.
- Перед feature должен вызываться `setFeatureSeed(decorationSeed, featureIndex, step)`.
- Descriptor gate может только skip-ать доказанно невозможные cases.
- Unknown modded feature/modifier должен иметь fallback.
- Runtime exception в optimized path должен quarantine-ить feature и попробовать safe vanilla.
- Записи blocks должны обновлять descriptors, если descriptors активны.
- ThreadLocal scratch не должен удерживать chunk/level/feature после pass.
- Batch/journal не должен менять наблюдаемый порядок там, где порядок влияет на результат.
- BiomeFilter нельзя заменять только грубым biome set selection.
- Partial native path должен использовать compatible placement context.

Если новая оптимизация нарушает хотя бы одно правило, она должна быть отключаемой или не должна попадать в default path.

## 73. Surface invariants

При изменении Surface Rule Compiler нельзя нарушать эти правила:

- `activeMask` содержит только blocks, еще не matched previous rules.
- Первое подходящее rule выигрывает.
- Index formula для raw arrays и masks остается `(y << 8) | (z << 4) | x`.
- Unknown/fallback conditions нельзя reorder-ить без доказательства purity.
- Requirements должны быть не уже, чем реальные потребности program.
- Если program может писать fluid, postprocessing должен остаться включенным.
- Frozen ocean и badlands extensions должны выполняться в совместимом месте flow.
- `SurfaceScratch` caches валидны только в пределах текущей section generation.
- ThreadLocal context/scratch должны очищать ссылки на chunk/biomes.
- Parity с legacy/vanilla важнее красивого optimized plan.

Главная ловушка: surface rules выглядят как чистые predicates, но fallback/modded rules могут быть не чистыми. Поэтому optimizer должен быть осторожным.

## 74. Debugging Decoration Pipeline

Если что-то пошло не так в decoration, задавайте вопросы по порядку.

Feature вообще выбрана?

- проверить biome set в 3x3 chunks;
- проверить `StepFeatureCache`;
- проверить selected feature mask;
- посмотреть, есть ли feature index в step plan.

Feature ушла в fallback?

- проверить kind в `DecorationKernelPlan`;
- проверить quarantine в `DecorationPipelineCompatibility`;
- включить `ga.decorationPipeline.metrics=true`;
- посмотреть `fallbackVanillaCalls`, `nativeKernelsExecuted`, `partialNativeKernelsExecuted`.

Feature skip-нулась descriptor gate?

- проверить `SectionDescriptor` flags;
- проверить lazy descriptor build;
- проверить mutations after writes;
- временно сравнить с descriptor-gated path disabled, если есть локальный флаг/патч.

Feature ставится не там?

- проверить opcodes placement program;
- проверить heightmap type;
- проверить random offset/repeating order;
- проверить `BiomeFilter` per-position;
- проверить `setFeatureSeed`.

Crash only with mod?

- посмотреть class feature/modifier в логах;
- проверить fast access interface mixin для placement modifier;
- ожидать fallback/quarantine, а не hard crash.

## 75. Debugging Surface Compiler

Если surface blocks неправильные, сначала разделите проблему.

Compiler problem:

- включить `ga.surface.compiler.dump=true`;
- посмотреть root node name;
- посмотреть fallback classes;
- сравнить IR on/off: `-Dga.surface.compiler.ir=false`;
- сравнить DAG on/off: `-Dga.surface.compiler.dag=false`;
- сравнить column interval on/off: `-Dga.surface.compiler.columnInterval=false`.

Execution problem:

- проверить `rawBlockArrayMisses`;
- проверить `stoneMask` construction;
- проверить `activeMask` clearing;
- проверить requirements;
- проверить fluid postprocess;
- проверить frozen ocean/badlands special path.

Performance problem:

- включить `ga.surface.metrics=true`;
- смотреть `programApplyNanos`, `stoneDepthNanos`, `biomePrepNanos`;
- смотреть `fallbackRuleBridgeNanos` и `fallbackConditionBridgeNanos`;
- если fallback bridge большой, нужен compiler adapter для модового rule.

Parity problem:

- запустить `SurfaceCompilerParityTest`;
- отключать IR/DAG/columnInterval по одному;
- искать первый режим, где результат меняется.

## 76. Почему weak caches

Ветка часто использует weak/shared caches. Причина - Minecraft registries и datapacks.

Objects вроде `PlacedFeature`, `RuleSource`, configs и biome generation settings могут жить только до reload. Если static cache держит strong reference, старый мир или старый datapack может остаться в памяти.

Weak cache позволяет GC убрать старые keys, когда они больше никому не нужны.

Но weak cache не заменяет lifecycle reset. Поэтому есть и `SharedWeakCache.clearAll()`, и `SurfaceProgramCache.clear()`, и `FeatureCacheEpoch.bump()`.

Практическое правило:

```text
cache by registry/datapack object -> weak or lifecycle-cleared
cache by primitive/config value -> still think about reload
```

Если добавляете новый static cache, сразу добавьте его в reset path или объясните, почему он immutable across server lifecycle.

## 77. Mod compatibility

Эта ветка много работает с модами, но стратегия везде одинаковая:

```text
fast path for known safe vanilla/modded cases
fallback for unknown cases
quarantine for runtime failures
metrics to find slow fallbacks
```

Примеры compatibility hooks:

- placement modifier access mixins для Artifacts, Oreberries, Repurposed Structures, Roots, Waystones, YUNG's и других;
- surface compiler data для TerraBlender, Lithostitched, Biomes We've Gone;
- feature mixins для common vanilla features;
- OWO/TerrainSlabs ore compatibility changes;
- Biolith surface mixin compatibility.

Новый модовый optimization лучше добавлять как маленький adapter:

- распознать class/config;
- доказать, что behavior pure/safe;
- добавить fallback path;
- добавить metrics name;
- добавить test или benchmark case.

Нельзя делать optimization, которая работает только потому, что конкретный мод сейчас случайно использует vanilla internals. После обновления мода это станет crash или worldgen mismatch.

## 78. Mini glossary

`Decoration Pipeline` - новый планировщик и executor для biome decoration features.

`PlacedFeature` - feature плюс список placement modifiers.

`ConfiguredFeature` - feature type плюс configuration.

`GenerationStep.Decoration` - этап decoration, внутри которого features выполняются по порядку.

`Feature index` - позиция feature в массиве step. Используется в RNG seed.

`Kernel` - compiled execution strategy для feature: native, partial или fallback.

`Native kernel` - Java fast implementation конкретной vanilla feature logic.

`Partial native` - быстрый placement и/или descriptor gate, но leaf feature остается vanilla.

`Descriptor` - summary одной chunk section: flags, masks, min/max filled Y, column data.

`Write journal` - batch buffer для block writes перед commit.

`Surface Rule Compiler` - compiler surface rules в `SurfaceProgram`.

`IR` - intermediate representation rules/conditions перед lowering.

`Mask4096` - 4096-bit mask одной section.

`Stone mask` - blocks default material, которые surface может заменить.

`Active mask` - blocks, еще не обработанные предыдущими surface rules.

`Fallback island` - неизвестный участок surface rule tree, встроенный через bridge.

`Requirement` - bitmask данных, которые нужно подготовить для surface program.

`Scratch` - переиспользуемая рабочая память без per-call allocations.

## 79. Краткая карта изменений относительно `origin/master`

Большие новые области:

- `common/features/pipeline` - новый Decoration Pipeline.
- `common/surface/compiler` - новый Surface Rule Compiler.
- `common/surface/compiler/ir` - IR для surface rules.
- `common/surface/compiler/mask` - section masks.
- `common/carver` - carver plans, writer, scratch/cache helpers.
- `diagnostics` - команды и JSON/JFR dumps.
- `common/features/vm` - legacy Feature VM и metrics.

Крупные измененные hot paths:

- `ChunkGenerator.applyBiomeDecoration`;
- `SurfaceSystem.buildSurface`;
- `Heightmap.update/primeHeightmaps`;
- `LevelChunkSection` raw array access;
- `WorldCarver` inner loop;
- `NoiseBasedChunkGenerator.applyCarvers`;
- DFC compile/cache/warm lifecycle.

Новые shared utilities:

- `GARuntimeCaches`;
- `FastBlockStateCache` improvements;
- `FeatureCacheEpoch`;
- `SharedWeakCache`;
- `FastPositionalRandom`;
- placement access interfaces.

Новые tests/benchmarks покрывают не все, но дают точки входа для проверки:

- decoration compiler/cache/journal tests;
- surface parity and quick benchmarks;
- fast positional random test;
- shared weak cache retention test.

## 80. Практическое правило для будущих изменений

В этой ветке почти все оптимизации следуют одному правилу:

```text
Сначала докажи, что быстрый путь сохраняет vanilla/modded semantics.
Если не можешь доказать - оставь fallback.
Если fast path упал - quarantine и safe vanilla.
Если fast path добавляет cache - добавь lifecycle reset.
Если fast path пишет blocks - обнови descriptors/heightmaps/postprocessing.
Если fast path меняет порядок - почти наверняка это bug.
```

Для Decoration Pipeline главный риск - изменить порядок features, random calls или слишком агрессивно skip-нуть feature.

Для Surface Rule Compiler главный риск - нарушить first-match-wins semantics, неверно построить mask или reorder-нуть impure condition.

Для Carver главный риск - изменить carving mask order, fluid/aquifer behavior или top material logic.

Для FlatBlockArray главный риск - рассинхронизировать raw array и `PalettedContainer` counts.

Для Diagnostics главный риск - включить дорогие counters/JFR там, где пользователь этого не просил.

Если изменение сомнительное, лучше добавить метрику и fallback, чем делать silent behavior change. Эта ветка выигрывает скорость за счет data-oriented fast paths, но ее надежность держится на консервативных границах этих fast paths.

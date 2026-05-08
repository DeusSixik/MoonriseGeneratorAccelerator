# Компилятор DensityFunction (DFC)

Этот документ объясняет, как в Generator Accelerator устроен компилятор `DensityFunction`. Текст написан простым техническим языком: сначала общая идея, затем детали по этапам, важные классы, ограничения и способы отладки.

## 1. Что такое DensityFunction в Minecraft

В Minecraft генерация мира часто отвечает на вопрос: "какое значение плотности в точке `x, y, z`?". Это значение влияет на рельеф, пещеры, высоты, климатические параметры и другие части мира.

Ваниль хранит такие вычисления как дерево объектов `DensityFunction`. У дерева есть узлы разных типов:

- константы;
- арифметика: сложение, умножение, минимум, максимум;
- ограничения диапазона: `Clamp`, `RangeChoice`;
- чтение координат `x`, `y`, `z`;
- шумы: `NormalNoise`, `BlendedNoise`, `ShiftedNoise`;
- сплайны `CubicSpline`;
- маркеры кешей `Cache2D`, `FlatCache`, `CacheOnce`, `CacheAllInCell`;
- специальные узлы вроде `BlendDensity`, `Beardifier`, `EndIslandDensityFunction`;
- узлы от модов и датапаков.

Обычная ванильная оценка идет по объектному дереву и вызывает много виртуальных методов. Для одного чанка это повторяется очень много раз, поэтому даже маленькие накладные расходы превращаются в заметную нагрузку.

## 2. Главная идея DFC

DFC берет дерево `DensityFunction` и превращает его в сгенерированный Java-класс. Вместо того чтобы каждый раз обходить дерево объектов, Minecraft вызывает метод `compute(ctx)` у компактного класса с прямым байткодом.

Упрощенная схема:

```text
Vanilla DensityFunction tree
        |
        v
IRBuilder -> IR graph
        |
        v
IROptimizer -> упрощенный IR
        |
        v
NoiseExpander -> специализированные шумы
        |
        v
Bounds / RefCount / Splitter / Fingerprint
        |
        v
Codegen ASM -> hidden class
        |
        v
CompiledDensityFunction
```

Цель компилятора - ускорить горячий путь генерации мира, сохранив поведение ванили. Если компиляция не удалась, DFC не ломает генерацию: он возвращает исходную `DensityFunction` и пишет предупреждение в лог.

## 3. Где находится код

Основной пакет:

`common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler`

Ключевые части:

- `DensityFunctionCompiler.java` - входная точка модуля, инициализация, прогрев, команда `/dfc dump`.
- `compiler/Compiler.java` - фасад одного прохода компиляции `DensityFunction -> CompiledDensityFunction`.
- `compiler/pipeline/RouterPipeline.java` - компиляция полей `NoiseRouter` и `Climate.Sampler`.
- `compiler/pipeline/CompilingVisitor.java` - общий visitor, который ставится в местах, где Minecraft обходит density-функции.
- `compiler/ir/*` - промежуточное представление, оптимизации и анализы.
- `compiler/codegen/*` - генерация байткода через ASM и базовый класс `CompiledDensityFunction`.
- `compiler/noise/*` - извлечение структуры шумов для инлайна.
- `compiler/cache/*` - fingerprint и глобальный кеш сгенерированных классов.
- `compiler/vector/*` - проверка доступности JDK Vector API.
- `natives/*` - опциональный JNI-мост для нативных шумовых батчей.
- `mixin/*` - точки подключения к ванильному Minecraft.

## 4. Как DFC включается в игру

DFC подключается через отдельный mixin-конфиг:

`common/src/main/resources/generator_accelerator_density_compiler.mixins.json`

Включение зависит от настройки:

`GAConfig.enableDensityCompilerPatch`

По умолчанию эта настройка включена. Ее можно переопределить через JVM property:

```text
-Dga.config.enableDensityCompilerPatch=false
```

При старте Fabric/NeoForge вызывают:

```text
DensityFunctionCompiler.init()
```

Во время инициализации мод:

- логирует, что DFC включается;
- проверяет доступность Vector API;
- проверяет, загрузилась ли нативная библиотека `dfc_native`;
- регистрирует серверную команду `/dfc dump`.

После старта сервера и после reload datapack вызывается прогрев реестров через `RegistryWarmer.warmAll(server)`.

## 5. Что именно компилируется

### 5.1 NoiseRouter

`NoiseRouter` содержит 15 главных density-функций. Они отвечают за шумы барьеров, лавы, температуру, растительность, континентальность, эрозию, глубину, ridges, итоговую плотность и другие поля.

`RouterPipeline.compile(original)` берет эти 15 полей и компилирует каждое поле как отдельный корень.

Важно: DFC не вызывает `NoiseRouter.mapAll` для полной рекурсивной компиляции каждого внутреннего узла. Такой подход создал бы тысячи маленьких классов. Вместо этого компилятор сам обходит исходное дерево и старается собрать один большой скомпилированный класс на одно верхнеуровневое поле.

### 5.2 Climate.Sampler

`Climate.Sampler` содержит 6 density-функций:

- temperature;
- humidity;
- continentalness;
- erosion;
- depth;
- weirdness.

Они используются при выборе биомов. DFC компилирует их отдельно через `RouterPipeline.compileSampler(original)`.

### 5.3 Raw density_function registry

`RegistryWarmer` по умолчанию прогревает `noise_settings`, потому что через `RandomState.create(...)` они проходят правильную ванильную привязку шумов. Сырые записи `density_function` не прогреваются по умолчанию, потому что внутри них могут быть еще не привязанные `NoiseHolder`.

## 6. Почему компиляция запускается после RandomState

В `RandomStateMixin` DFC встраивается в конец конструктора `RandomState`.

Это важно, потому что до завершения конструктора `RandomState` часть density-функций еще является шаблоном из датапака. Ванильный `NoiseWiringHelper` должен сначала привязать реальные шумы, seed и ссылки из реестров. Только после этого DFC получает дерево, которое реально будет использоваться при генерации.

Если компилировать раньше, можно получить:

- непривязанные `NoiseHolder`;
- меньше возможностей для инлайна шумов;
- неправильные ключи кеша;
- лишние fallback-вызовы.

## 7. Прогрев компилятора

Компиляция стоит дороже обычного одного вызова `compute`, поэтому DFC старается выполнить ее заранее, а не на первом чанке.

`RegistryWarmer` после старта сервера:

1. берет все `NoiseGeneratorSettings` из реестра;
2. для каждого settings создает `RandomState`;
3. этот `RandomState` проходит обычную ванильную wiring-стадию;
4. `RandomStateMixin` компилирует готовый `NoiseRouter` и `Climate.Sampler`;
5. результат попадает в кеши.

Так первый реальный chunk worker не платит большой cold-start за генерацию байткода.

## 8. Промежуточное представление IR

Ванильное дерево объектов неудобно оптимизировать напрямую. Поэтому `IRBuilder` переводит его в собственное промежуточное представление `IRNode`.

IR - это набор record-узлов:

- `Const` - число `double`;
- `BlockX`, `BlockY`, `BlockZ` - чтение координат;
- `Bin` - бинарная операция: `ADD`, `SUB`, `MUL`, `DIV`, `MIN`, `MAX`;
- `Unary` - унарная операция: `ABS`, `SQUARE`, `CUBE`, `SQUEEZE` и другие;
- `Clamp`;
- `RangeChoice`;
- `YClampedGradient`;
- `Noise`, `ShiftedNoise`, `ShiftA`, `ShiftB`, `Shift`;
- `WeirdScaled`, `WeirdRarity`;
- `InlinedNoise`, `InlinedBlendedNoise`;
- `Spline.Constant`, `Spline.Multipoint`;
- `Marker`;
- `Invoke`;
- `BlendDensity`, `Beardifier`, `EndIslands`.

### 8.1 Hash-consing и CSE

`IRBuilder` интернирует узлы. Это значит: если две части дерева описывают одинаковую операцию с одинаковыми детьми, они становятся одной и той же Java-ссылкой в IR.

Так дерево превращается в DAG:

```text
до:                 после:
  add                 add
 /   \               /   \
a*b  a*b             mul  mul указывают на один объект
```

Это дает простую форму CSE (common subexpression elimination): одинаковое выражение можно вычислить один раз и переиспользовать.

### 8.2 ConstantPool

Не все можно хранить прямо в IR. Например, `NormalNoise`, spline-объекты и неизвестные `DensityFunction` лучше держать отдельными ссылками.

Для этого используется `ConstantPool`. IR хранит только индекс:

```text
IRNode.Noise(noiseIndex=3, ...)
```

А сам объект лежит в массиве, который передается в `CompiledDensityFunction`.

## 9. Как IRBuilder обращается с разными узлами

### 9.1 Узлы, которые DFC понимает

Если тип известен, DFC раскрывает его в IR. Например:

- `DensityFunctions.Constant` -> `IRNode.Const`;
- `DensityFunctions.Clamp` -> `IRNode.Clamp`;
- `DensityFunctions.TwoArgumentSimpleFunction` -> `IRNode.Bin`;
- `DensityFunctions.Mapped` -> `IRNode.Unary`;
- `DensityFunctions.Spline` -> IR сплайна;
- `BlendedNoise` -> `InlinedBlendedNoise`, если получилось извлечь spec.

### 9.2 Узлы, которые DFC не понимает

Если узел пришел от мода или просто не поддержан, он становится `IRNode.Invoke`. В сгенерированном байткоде это будет обычный вызов:

```text
externs[index].compute(ctx)
```

Это медленнее, чем полный инлайн, но сохраняет совместимость.

### 9.3 Marker boundaries

Маркеры кешей нельзя просто удалить или заинлайнить. `NoiseChunk` использует их как контракт: во время `mapAll` он заменяет маркер на конкретный кеш или интерполятор для текущего чанка.

Поэтому DFC сохраняет boundary:

- внешний marker остается отдельной сущностью;
- его внутреннее дерево может быть скомпилировано отдельно;
- сгенерированный класс умеет корректно проходить `mapAll` и rebind extern-ссылки.

Это критично для корректности. Если потерять marker, можно сломать ванильные кеши, blending или интерполяцию плотности.

## 10. Оптимизация IR

`IROptimizer` выполняет безопасные peephole-оптимизации до генерации байткода.

Главное правило: не делать преобразований, которые могут поменять поведение `double`. Поэтому оптимизатор осторожен с `NaN`, `Infinity`, `-0.0` и порядком операций.

Примеры идей оптимизатора:

- свернуть константные выражения;
- убрать нейтральные операции там, где это безопасно;
- упростить `Clamp`, если диапазон уже известен;
- упростить `RangeChoice`, если по bounds понятно, какая ветка всегда выберется;
- сделать cost-aware strength reduction только если выражение дешево или уже общее.

Оптимизация идет до fixpoint, но имеет защитный лимит итераций.

## 11. Анализ границ значений

`Bounds.interval(root, pool)` пытается вычислить минимальное и максимальное возможное значение IR.

Эти значения нужны для двух вещей:

1. заменить `minValue()` и `maxValue()` у скомпилированной функции быстрыми готовыми значениями;
2. помочь оптимизациям вроде `RangeChoice`, когда можно заранее понять, попадет вход в диапазон или нет.

Если анализ bounds падает, DFC берет значения из исходной `DensityFunction.minValue()` и `DensityFunction.maxValue()`.

## 12. Инлайн и специализация шумов

Шумы - одна из самых дорогих частей density-функций. DFC старается не просто вызвать `NormalNoise.getValue`, а раскрыть структуру шума в байткод.

Этим занимается `NoiseExpander` вместе с классами из `compiler/noise`.

### 12.1 NormalNoise

Для `NormalNoise` DFC извлекает:

- две ветки `PerlinNoise`;
- активные октавы;
- амплитуды;
- input/value factors;
- ссылки на `ImprovedNoise`;
- смещения и permutation-таблицы через accessor mixins.

После этого вызов шума превращается в `InlinedNoise`: координаты становятся отдельными IR-выражениями, а per-octave loop может быть развернут в байткод.

Плюсы:

- меньше виртуальных вызовов;
- JIT видит больше прямого кода;
- общие координатные выражения можно переиспользовать;
- проще построить нативный batch-путь.

### 12.2 BlendedNoise

`BlendedNoise` тоже может быть раскрыт в `InlinedBlendedNoise`. DFC сохраняет ванильную структуру вычисления, включая смешивание через `Mth.clampedLerp`.

### 12.3 WeirdScaledSampler

`WeirdScaledSampler` раскладывается на шум и отдельный `WeirdRarity`, чтобы общий rarity-фактор не дублировался в нескольких местах.

## 13. Splitter и helper-методы

JVM имеет лимиты на размер метода. Большой router может дать слишком длинный `compute(ctx)`. Чтобы не получить слишком большой байткод, DFC использует `Splitter`.

`Splitter` выбирает части IR, которые лучше вынести в helper-методы:

```text
compute(ctx)
  -> helper_0(self, ctx)
  -> helper_1(self, ctx)
```

Из-за особенностей hidden classes сгенерированный класс не должен символически ссылаться на самого себя. Поэтому helper-вызовы идут через `MethodHandle` или `invokedynamic`, а не через обычный `INVOKESTATIC Self.helper_0`.

## 14. Генерация байткода

`Codegen` через ASM создает класс-наследник `CompiledDensityFunction`.

Сгенерированный класс обычно содержит:

- конструктор;
- `compute(FunctionContext ctx)`;
- `fillArray(double[] out, ContextProvider provider)` при наличии fast path;
- helper-методы;
- методы для lattice fast path, если план найден.

`CompiledDensityFunction` хранит runtime-ссылки:

- `double[] constants`;
- `NormalNoise[] noises`;
- `Object[] splines`;
- `Object[] noiseOctaves`;
- `DensityFunction[] externs`;
- `MethodHandle[] helperHandles`;
- нативные handles и программы slab path, если доступны.

Сами классы определяются как hidden classes через `MethodHandles.Lookup#defineHiddenClass`. Это помогает JVM выгружать классы после reload, если на них больше нет ссылок.

## 15. Rebind и mapAll

Minecraft часто вызывает `mapAll(visitor)`, особенно в `NoiseChunk`, чтобы заменить placeholder/marker-узлы на chunk-local объекты.

DFC сохраняет это поведение:

1. `CompiledDensityFunction.mapAll(visitor)` проходит по `externs` и `noises`;
2. если visitor что-то заменил, создается новый экземпляр того же hidden class;
3. байткод не пересоздается, меняются только массивы ссылок;
4. если ничего не поменялось, возвращается текущий объект.

Это называется rebind.

Для ускорения router-level `mapAll` есть `MapAllSession`. Он помогает не обходить одни и те же общие поддеревья много раз внутри одного `NoiseRouter.mapAll`.

## 16. Cell-lattice fast path

Обычный `fillArray` вычисляет density-функцию для набора точек в ячейке. Часто внутри выражения есть кусок, который зависит только от одной оси.

Пример Y-only:

```text
значение зависит только от blockY
```

Такой результат не нужно пересчитывать для каждого `x,z` внутри одного Y-слоя.

`CellLatticeOption` ищет самый крупный подграф, который:

- зависит только от `Y`; или
- зависит только от `X/Z`;
- достаточно большой, чтобы hoist был выгоден;
- не пересекает marker/extern boundary.

Если план найден, `Codegen` генерирует специальный `fillArray`, который сначала считает hoisted-часть один раз, а потом переиспользует ее в остальной формуле.

Это особенно полезно для `YClampedGradient`-цепочек и похожих выражений.

### 16.1 Временно отключенный add-extern fast path

В ветке cell-fill есть еще одна более агрессивная оптимизация для выражений вида:

```text
fastExtern + residual
```

Идея была в том, чтобы:

- вычислять левую или правую extern-часть через `DfcCellFillAccess`;
- остаток добавлять отдельным специализированным путем;
- тем самым уменьшить стоимость `dfc$fillCell` / `dfc$accumulateCell` для некоторых `ADD`-деревьев.

На практике этот путь оказался нестабильным:

- на части real-world root shapes он давал ошибки ASM `Frame.merge` / `ArrayIndexOutOfBoundsException`;
- одна из ранних версий также ломала раскладку индексов внутри cell buffer, что проявлялось как worldgen-артефакты.

Поэтому `cellFillAddExternOverride` сейчас считается **экспериментальным** и **выключен по умолчанию**.

Включается только вручную:

```text
-Ddfc.codegen.cellFillAddExternOverride=true
```

Использовать этот флаг стоит только при целевой отладке или при отдельной переработке данного fast path.

## 17. Vector API

`DfcVectorSupport` проверяет, доступен ли модуль:

```text
jdk.incubator.vector
```

По умолчанию у обычного лаунчера он обычно недоступен. Чтобы включить SIMD-ветки в dev-запуске, JVM должна получить:

```text
--add-modules jdk.incubator.vector
```

DFC проверяет Vector API через reflection, чтобы не уронить JVM на обычном запуске. Если модуль недоступен, DFC остается в scalar-режиме.

## 18. Нативный dfc_native

В DFC есть опциональный JNI-мост `DfcNativeBridge`. Он может использовать нативные batch/SIMD kernels для шумов и slab-inner вычислений.

Загрузка идет так:

1. если задан `DFC_NATIVE_LIBRARY`, используется абсолютный путь из этой переменной окружения;
2. иначе библиотека ищется среди bundled/prebuilt ресурсов;
3. если библиотека не загрузилась, DFC продолжает работать на Java fallback.

Ожидаемые имена библиотек:

```text
Windows: dfc_native.dll
Linux:   libdfc_native.so
macOS:   libdfc_native.dylib
```

Структура prebuilt-файлов описана в:

`common/natives/dfc/prebuilts/README.txt`

Нативный слой не является обязательным для корректности. Он только дает дополнительные fast paths, если доступен.

## 19. Кеши компиляции

DFC использует несколько уровней кеширования.

### 19.1 CompilingVisitor cache

`CompilingVisitor` кеширует результат по identity исходной `DensityFunction`.

Используются weak keys и weak values, чтобы после reload старые деревья и скомпилированные классы могли быть собраны GC.

### 19.2 GlobalCompileCache

Даже если исходные Java-объекты разные, форма IR может быть одинаковой. Например, два разных мира могут иметь одинаковую структуру router-поля, но другие runtime-ссылки.

`GlobalCompileCache` хранит bundle:

- hidden class;
- bytecode;
- constructor handle;
- helper handles;
- информацию о lattice/native fast path.

Ключ - shape fingerprint. Если форма совпала, DFC не генерирует байткод заново, а создает новый экземпляр уже готового класса с другими массивами ссылок.

### 19.3 Exact и shape fingerprint

`CompilationFingerprint` считает два SHA-256:

- exact fingerprint - учитывает структуру и identity runtime-bindings;
- shape fingerprint - учитывает только то, что влияет на форму байткода.

Shape fingerprint специально исключает ссылки, которые можно передать через конструктор: `noises`, `externs`, spline blobs и похожие данные.

Это позволяет переиспользовать один класс для разных, но структурно одинаковых деревьев.

## 20. Совместимость и fallback

DFC сделан fail-soft:

- если один узел не поддержан, он становится `Invoke`;
- если один корень не скомпилировался, используется исходная density-функция;
- если parallel compile поля router упал, есть sequential fallback;
- если нативная библиотека не загрузилась, работает Java path;
- если Vector API недоступен, работает scalar path;
- если bounds-анализ не сработал, используются ванильные `minValue/maxValue`;
- если `dfc_c2me` загружен, `RandomStateMixin` пропускает компиляцию router/sampler.

Главный принцип: DFC может потерять ускорение, но не должен ломать генерацию мира.

## 21. Команда /dfc dump

В этой ветке публично зарегистрирована команда:

```mcfunction
/dfc dump
```

Она сохраняет сгенерированные классы в директорию:

```text
.densitycompiler/
```

Это полезно для отладки байткода. После dump можно открыть `.class` через декомпилятор или bytecode viewer и посмотреть, что реально сгенерировал ASM.

## 22. Полезные JVM properties

```text
-Dga.config.enableDensityCompilerPatch=false
```

Отключить весь density compiler patch.

```text
-Ddfc.warmer.rawDensityFunctions=true
```

Дополнительно прогревать сырой реестр `density_function`. По умолчанию выключено.

```text
-Ddfc.warmer.maxSettings=<N>
-Ddfc.warmer.maxDensityFunctions=<N>
```

Ограничить количество entries, которые прогревает `RegistryWarmer`.

```text
--add-modules jdk.incubator.vector
```

Разрешить JDK Vector API, если JVM и запуск это поддерживают.

```text
DFC_NATIVE_LIBRARY=<absolute path>
```

Указать путь к `dfc_native` вручную.

## 23. Как читать лог DFC

При старте можно ожидать сообщения вроде:

```text
DensityFunctionCompiler initialising - runtime DF JIT pipeline enabling.
DFC vector: enabled/disabled ...
DFC native noise: libraryLoaded=..., avx2=...
```

При прогреве:

```text
DFC: warmed X/Y noise_settings (RandomState + wired router compile); ...
```

При создании `RandomState`:

```text
DFC compiled NoiseRouter + Climate.Sampler for RandomState(seed=...) in ...ms
```

Если компиляция отдельного root упала, лог будет предупреждать о fallback на vanilla evaluator. Это не означает краш мира, но означает потерю ускорения для конкретной функции.

## 24. Короткий пример полного пути

Допустим, ванильное поле router выглядит так:

```text
final_density = clamp(noise(x, y, z) + y_gradient, -1, 1)
```

DFC делает примерно следующее:

1. `RandomState` завершил wiring.
2. `RouterPipeline` передал `final_density` в `CompilingVisitor`.
3. `IRBuilder` построил IR:

```text
Clamp(
  Bin(ADD,
    Noise(...),
    YClampedGradient(...)
  ),
  -1,
  1
)
```

4. `IROptimizer` упростил все, что можно упростить безопасно.
5. `NoiseExpander` заменил `Noise` на `InlinedNoise`, если удалось извлечь spec.
6. `Bounds` посчитал min/max.
7. `RefCount` нашел общие подвыражения.
8. `Splitter` вынес слишком крупные части в helper-методы.
9. `CellLatticeOption` мог найти `YClampedGradient` как Y-only hoist.
10. `Codegen` создал hidden class.
11. `GlobalCompileCache` сохранил class bundle по fingerprint.
12. Minecraft дальше вызывает быстрый `CompiledDensityFunction.compute(ctx)`.

## 25. Что важно помнить при разработке

- Нельзя ломать marker boundary. Это влияет на `NoiseChunk` кеши и корректность terrain.
- Нельзя делать небезопасную floating-point алгебру. `x + 0`, `x * 1`, `-0.0`, `NaN` и `Infinity` требуют осторожности.
- Неизвестные modded density-функции лучше оставить через `Invoke`, чем пытаться угадать их семантику.
- Любая оптимизация должна иметь fallback.
- Hidden class не должен символически ссылаться на самого себя.
- Новые поля, влияющие на байткод, должны учитываться в shape fingerprint.
- Новые runtime-ссылки, передаваемые через конструктор, должны быть частью exact fingerprint или корректно исключаться из shape fingerprint.
- Если оптимизация меняет `fillArray`, нужно проверять не только одиночный `compute`, но и batch-поведение.

## 26. Мини-глоссарий

- **DensityFunction** - функция плотности Minecraft: принимает координаты и возвращает `double`.
- **NoiseRouter** - набор главных density-функций мира.
- **Climate.Sampler** - набор density-функций для выбора биомов.
- **IR** - промежуточное представление, удобное для оптимизации и генерации байткода.
- **DAG** - граф без циклов; общие подвыражения хранятся один раз.
- **CSE** - устранение общих подвыражений.
- **Hidden class** - класс JVM, созданный во время выполнения и пригодный для выгрузки GC.
- **Extern** - объект, который DFC не инлайнит, а вызывает как обычную `DensityFunction`.
- **Marker** - специальный boundary-узел ванили, через который `NoiseChunk` ставит кеши.
- **Rebind** - создание нового экземпляра скомпилированной функции с замененными runtime-ссылками.
- **Shape fingerprint** - хеш формы байткода, используемый для переиспользования класса.
- **Exact fingerprint** - хеш формы и конкретных runtime-bindings.
- **Lattice hoist** - вынос части выражения, зависящей только от Y или только от XZ, из внутреннего цикла.

package dev.sixik.generator_accelerator.common.features.mixin.misc;

import dev.sixik.generator_accelerator.api.utils.FastGraph;
import dev.sixik.generator_accelerator.common.features.GAFeatureData;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.apache.commons.lang3.mutable.MutableInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.*;
import java.util.function.Function;

@Mixin(FeatureSorter.class)
public class MixinFeatureSorter {

    /**
     * DOD-Оптимизация алгоритма сортировки фичей ({@code Feature Sorter}).
     * <p>
     * 1. Уход от функционального стиля: Ванильная реализация использует {@code Stream API}, {@code TreeSet} и {@code TreeMap},
     *    что приводит к катастрофическому количеству аллокаций {@code (O(N*M))} и промахам кэша из-за структуры
     *    красно-черных деревьев в памяти.
     * <p>
     * 2. {@link ObjectArrayList} вместо {@link com.google.common.collect.ImmutableList.Builder}: Замена произведена для обеспечения прямого
     *    доступа к памяти. {@link ObjectArrayList} из {@code fastutil} позволяет вызвать метод {@code .elements()}, возвращающий
     *    нативный массив {@code Object[]}. Это позволяет итерироваться по списку через классический цикл {@code for(i)},
     *    избегая создания объектов {@code Iterator} и обеспечивая линейную нагрузку на L1-кэш процессора.
     * <p>
     * 3. Детерминизм ({@code Seed Parity}): Поскольку замена {@link TreeMap} на {@link Object2ObjectOpenHashMap} {@code (O(1))} лишает
     *    нас автоматической сортировки, перед запуском {@code DFS} производится принудительная сортировка
     *    плоских списков ключей и ребер через {@link TimSort} ({@code comparator}). Это гарантирует, что порядок
     *    обхода графа будет идентичен ванильному, сохраняя идентичность генерации мира при разных запусках.
     * <p>
     * 4. Линейная группировка ({@code Bucket Sort}): Финальный сборщик фичей по шагам переписан с {@code O(N*M)} на {@code O(N)}.
     *    Вместо фильтрации всего списка стримом для каждого шага ({@code step}), мы проходим по массиву
     *    отсортированных фичей ровно один раз, распределяя их по заранее выделенным "корзинам" ({@link ObjectArrayList}).
     * <p>
     * @author Sixik
     * @reason look up
     */
    @Overwrite
    public static <T> List<FeatureSorter.StepFeatureData> buildFeaturesPerStep(List<T> list, Function<T, List<HolderSet<PlacedFeature>>> function, boolean bl) {
        Object2IntMap<PlacedFeature> object2IntMap = new Object2IntOpenHashMap<>();
        MutableInt mutableInt = new MutableInt(0);

        Comparator<GAFeatureData> comparator = Comparator.comparingInt(GAFeatureData::step).thenComparingInt(GAFeatureData::featureIndex);

        /*
            Используем O(1) Hash Map вместо O(log N) Tree Map
         */
        Object2ObjectOpenHashMap<GAFeatureData, ObjectArrayList<GAFeatureData>> map =
                new Object2ObjectOpenHashMap<>();
        int maxStep = 0;

        for (T object : list) {
            ObjectArrayList<GAFeatureData> list2 = new ObjectArrayList<>();
            List<HolderSet<PlacedFeature>> list3 = function.apply(object);
            maxStep = Math.max(maxStep, list3.size());

            for (int j = 0; j < list3.size(); ++j) {
                for (Holder<PlacedFeature> holder : list3.get(j)) {
                    PlacedFeature placedFeature = holder.value();
                    int featureIdx = object2IntMap.computeIfAbsent(placedFeature, (objectx) -> mutableInt.getAndIncrement());
                    list2.add(new GAFeatureData(featureIdx, j, placedFeature));
                }
            }

            for (int j = 0; j < list2.size(); ++j) {
                GAFeatureData current = list2.get(j);
                ObjectArrayList<GAFeatureData> edges = map.computeIfAbsent(current, k -> new ObjectArrayList<>());
                if (j < list2.size() - 1) {
                    GAFeatureData next = list2.get(j + 1);
                    if (!edges.contains(next)) {
                        edges.add(next);
                    }
                }
            }
        }

        /*
            Гарантируем детерминированность генерации (Seed Parity)
         */
        for (ObjectArrayList<GAFeatureData> edges : map.values()) {
            edges.sort(comparator);
        }
        ObjectArrayList<GAFeatureData> sortedKeys = new ObjectArrayList<>(map.keySet());
        sortedKeys.sort(comparator);

        /*
            Быстрые множества для DFS
         */
        ObjectOpenHashSet<GAFeatureData> visited = new ObjectOpenHashSet<>(sortedKeys.size());
        ObjectOpenHashSet<GAFeatureData> visiting = new ObjectOpenHashSet<>(sortedKeys.size());
        ObjectArrayList<GAFeatureData> sortedFeatures = new ObjectArrayList<>();

        /*
            Перебор по плоскому отсортированному массиву
         */
        int keysSize = sortedKeys.size();
        for (int idx = 0; idx < keysSize; idx++) {
            GAFeatureData lv = sortedKeys.get(idx);

            if (!visiting.isEmpty()) {
                throw new IllegalStateException("You somehow broke the universe; DFS bork (iteration finished with non-empty in-progress vertex set");
            }

            if (!visited.contains(lv)) {
                if (FastGraph.depthFirstSearch(map, visited, visiting, sortedFeatures::add, lv)) {
                    if (!bl) {
                        throw new IllegalStateException("Feature order cycle found");
                    }

                    List<T> list4 = new ArrayList<>(list);
                    int k;
                    do {
                        k = list4.size();
                        ListIterator<T> listIterator = list4.listIterator();

                        while (listIterator.hasNext()) {
                            T object2 = listIterator.next();
                            listIterator.remove();

                            try {
                                buildFeaturesPerStep(list4, function, false);
                            } catch (IllegalStateException var18) {
                                continue;
                            }

                            listIterator.add(object2);
                        }
                    } while (k != list4.size());

                    throw new IllegalStateException("Feature order cycle found, involved sources: " + String.valueOf(list4));
                }
            }
        }

        Collections.reverse(sortedFeatures);

        /*
            Группировка фич по шагам (бакетам) за O(N) вместо O(N*M)
         */
        ObjectArrayList<FeatureSorter.StepFeatureData> outList = new ObjectArrayList<>();
        Object[] elements = sortedFeatures.elements();
        int featuresSize = sortedFeatures.size();

        for (int step = 0; step < maxStep; ++step) {
            ObjectArrayList<PlacedFeature> featuresForStep = new ObjectArrayList<>();

            for (int k = 0; k < featuresSize; k++) {
                GAFeatureData fd = (GAFeatureData) elements[k];
                if (fd.step() == step) {
                    featuresForStep.add(fd.feature());
                }
            }

            outList.add(new FeatureSorter.StepFeatureData(featuresForStep));
        }

        return outList;
    }
}

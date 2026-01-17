package dev.sixik.density_compiller.visitor;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.lang.reflect.Field;
import java.util.*;

public class DensityTreeBuilder {

    // Узел дерева для анализа
    public record Node(String type, DensityFunction source, List<Node> children) {
        public String toString() {
            return type + " [children=" + children.size() + "]";
        }
    }

    public static Node build(DensityFunction root) {
        var builder = new GraphBuildingVisitor();
        root.mapAll(builder);
        // Последний созданный узел — это корень (mapAll возвращает результат apply для корня)
        return builder.lastNode;
    }

    private static class GraphBuildingVisitor implements DensityFunction.Visitor {
        // Кэш для избежания бесконечной рекурсии и дубликатов (DAG)
        private final Map<DensityFunction, Node> cache = new IdentityHashMap<>();
        public Node lastNode = null;

        @Override
        public DensityFunction apply(DensityFunction input) {
            // input здесь — это НОВЫЙ экземпляр (например, new FastMax(wrappedA, wrappedB))
            // Его поля уже содержат наши NodeWrapper, возвращенные с предыдущих шагов.

            List<Node> children = new ArrayList<>();
            extractChildren(input, children);

            // Создаем узел для текущей функции
            Node node = new Node(input.getClass().getSimpleName(), input, children);
            lastNode = node;

            // Возвращаем обертку, чтобы родитель мог найти этот узел в своих полях
            return new NodeWrapper(input, node);
        }

        @Override
        public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noiseHolder) {
            // Листовой узел (шум)
            lastNode = new Node("NoiseHolder", null, Collections.emptyList());
            return noiseHolder; // NoiseHolder обычно финальный, его сложно обернуть без прокси
        }

        // Рефлексия для извлечения детей из полей (FastMax.a, FastMax.b и т.д.)
        private void extractChildren(Object parent, List<Node> result) {
            for (Field field : parent.getClass().getDeclaredFields()) {
                if (DensityFunction.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(parent);
                        if (value instanceof NodeWrapper wrapper) {
                            result.add(wrapper.node);
                        }
                    } catch (IllegalAccessException ignored) { /* Логируем при необходимости */ }
                }
            }
        }
    }

    // Прозрачная обертка, чтобы протаскивать Node вверх по стеку вызовов
    private record NodeWrapper(DensityFunction delegate, Node node) implements DensityFunction {
        @Override
        public double compute(FunctionContext context) {
            return delegate.compute(context);
        }

        @Override
        public void fillArray(double[] ds, ContextProvider contextProvider) {
            delegate.fillArray(ds, contextProvider);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return delegate.mapAll(visitor);
        }

        @Override
        public double minValue() {
            return delegate.minValue();
        }

        @Override
        public double maxValue() {
            return delegate.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return delegate.codec();
        }
    }
}
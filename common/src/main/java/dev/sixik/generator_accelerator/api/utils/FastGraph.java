package dev.sixik.generator_accelerator.api.utils;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.Map;
import java.util.function.Consumer;

public class FastGraph {

    public static <T> boolean depthFirstSearch(
            Map<T, ObjectArrayList<T>> map,
            ObjectOpenHashSet<T> visited,
            ObjectOpenHashSet<T> visiting,
            Consumer<T> consumer,
            T node
    ) {
        if (visited.contains(node)) {
            return false;
        } else if (visiting.contains(node)) {
            return true;
        } else {
            visiting.add(node);

            /*
                Читаем напрямую из массива смежности без создания итераторов
             */
            ObjectArrayList<T> neighbors = map.get(node);
            if (neighbors != null) {
                Object[] elements = neighbors.elements();
                int size = neighbors.size();
                for (int i = 0; i < size; i++) {
                    if (depthFirstSearch(map, visited, visiting, consumer, (T) elements[i])) {
                        return true;
                    }
                }
            }

            visiting.remove(node);
            visited.add(node);
            consumer.accept(node);
            return false;
        }
    }
}

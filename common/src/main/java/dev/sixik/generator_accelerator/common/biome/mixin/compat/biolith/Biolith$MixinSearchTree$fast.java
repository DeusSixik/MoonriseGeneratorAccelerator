package dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith;

import com.bawnorton.mixinsquared.TargetHandler;
import com.terraformersmc.biolith.api.biome.BiolithFittestNodes;
import dev.sixik.generator_accelerator.common.biome.compat.biolith.GABiolithSearchContext;
import dev.sixik.generator_accelerator.common.biome.compat.biolith.GABiolithFlatSearch;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(value = Climate.RTree.class, priority = 1600)
public abstract class Biolith$MixinSearchTree$fast<T> {
    @Unique
    private static final boolean GA$FAST_SEARCH_ENABLED =
            Boolean.parseBoolean(System.getProperty("ga.biolith.fastSearch", "true"));
    @Unique
    private static final boolean GA$FLAT_SEARCH_ENABLED =
            Boolean.parseBoolean(System.getProperty("ga.biolith.flatSearch", "true"));

    @Shadow
    public Climate.RTree.Node<T> root;

    @Unique
    private final ThreadLocal<GABiolithSearchContext> ga$biolithSearchContext =
            ThreadLocal.withInitial(GABiolithSearchContext::new);
    @Unique
    private GABiolithFlatSearch<T> ga$flatSearch;
    @Unique
    private Climate.RTree.Node<T> ga$flatSearchRoot;

    @TargetHandler(
            mixin = "com.terraformersmc.biolith.impl.mixin.MixinSearchTree",
            name = "biolith$searchTreeGet"
    )
    @Inject(method = "@MixinSquared:Handler", at = @At("HEAD"), cancellable = true, remap = false)
    private void ga$fastBiolithSearch(
            Climate.TargetPoint target,
            Climate.DistanceMetric<T> metric,
            CallbackInfoReturnable<BiolithFittestNodes<T>> cir
    ) {
        if (!GA$FAST_SEARCH_ENABLED) {
            return;
        }
        if (GA$FLAT_SEARCH_ENABLED) {
            cir.setReturnValue(this.ga$flatSearchTreeGet(target));
            return;
        }
        cir.setReturnValue(this.ga$searchTreeGet(target, metric));
    }

    @Unique
    @SuppressWarnings("unchecked")
    private BiolithFittestNodes<T> ga$flatSearchTreeGet(Climate.TargetPoint target) {
        Climate.RTree.Node<T> currentRoot = this.root;
        GABiolithFlatSearch<T> search = this.ga$flatSearch;
        if (search == null || this.ga$flatSearchRoot != currentRoot) {
            search = new GABiolithFlatSearch<>((Climate.RTree<T>) (Object) this);
            this.ga$flatSearch = search;
            this.ga$flatSearchRoot = currentRoot;
        }
        return search.search(target);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private BiolithFittestNodes<T> ga$searchTreeGet(
            Climate.TargetPoint target,
            Climate.DistanceMetric<T> metric
    ) {
        GABiolithSearchContext context = this.ga$biolithSearchContext.get();
        long[] parameterArray = context.parameterArray;
        parameterArray[0] = target.temperature();
        parameterArray[1] = target.humidity();
        parameterArray[2] = target.continentalness();
        parameterArray[3] = target.erosion();
        parameterArray[4] = target.depth();
        parameterArray[5] = target.weirdness();
        parameterArray[6] = 0L;

        Climate.RTree.Leaf<T> best = (Climate.RTree.Leaf<T>) context.previousUltimate;
        Climate.RTree.Leaf<T> second = (Climate.RTree.Leaf<T>) context.previousPenultimate;
        long bestDistance = best == null ? Long.MAX_VALUE : metric.distance(best, parameterArray);
        long secondDistance = second == null ? Long.MAX_VALUE : metric.distance(second, parameterArray);
        if (bestDistance > secondDistance) {
            Climate.RTree.Leaf<T> leafSwap = best;
            best = second;
            second = leafSwap;
            long distanceSwap = bestDistance;
            bestDistance = secondDistance;
            secondDistance = distanceSwap;
        }
        if (best != null && second != null && ga$sameBiomeKey(best, second)) {
            second = null;
            secondDistance = Long.MAX_VALUE;
        }

        Climate.RTree.Node<T> rootNode = this.root;
        if (rootNode == null) {
            context.previousUltimate = best;
            context.previousPenultimate = second;
            return second == null
                    ? new BiolithFittestNodes<>(best, bestDistance)
                    : new BiolithFittestNodes<>(best, bestDistance, second, secondDistance);
        }

        Climate.RTree.Node<?>[] stack = context.stack;
        int stackPointer = 0;
        stack[stackPointer++] = rootNode;
        while (stackPointer > 0) {
            Climate.RTree.Node<T> node = (Climate.RTree.Node<T>) stack[--stackPointer];
            long distance = metric.distance(node, parameterArray);
            if (distance >= secondDistance) {
                continue;
            }

            if (node instanceof Climate.RTree.SubTree<T> subtree) {
                Climate.RTree.Node<T>[] children = subtree.children;
                int childCount = children.length;
                int required = stackPointer + childCount;
                if (required > stack.length) {
                    stack = context.growStack(required);
                }
                for (int i = childCount - 1; i >= 0; i--) {
                    stack[stackPointer++] = children[i];
                }
                continue;
            }

            if (!(node instanceof Climate.RTree.Leaf<T> leaf)) {
                continue;
            }

            if (best == null || distance < bestDistance) {
                if (best != null && !ga$sameBiomeKey(leaf, best)) {
                    second = best;
                    secondDistance = bestDistance;
                }
                best = leaf;
                bestDistance = distance;
            } else if (!ga$sameBiomeKey(leaf, best)) {
                second = leaf;
                secondDistance = distance;
            }
        }

        context.previousUltimate = best;
        context.previousPenultimate = second;
        return second == null
                ? new BiolithFittestNodes<>(best, bestDistance)
                : new BiolithFittestNodes<>(best, bestDistance, second, secondDistance);
    }

    @Unique
    private static boolean ga$sameBiomeKey(Climate.RTree.Leaf<?> left, Climate.RTree.Leaf<?> right) {
        Object leftValue = left.value;
        Object rightValue = right.value;
        if (leftValue == rightValue) {
            return true;
        }
        if (leftValue instanceof Holder.Reference<?> leftHolder && rightValue instanceof Holder.Reference<?> rightHolder) {
            return leftHolder.key().equals(rightHolder.key());
        }
        if (leftValue instanceof Holder<?> leftHolder && rightValue instanceof Holder<?> rightHolder) {
            Optional<? extends ResourceKey<?>> leftKey = leftHolder.unwrapKey();
            Optional<? extends ResourceKey<?>> rightKey = rightHolder.unwrapKey();
            if (leftKey.isPresent() || rightKey.isPresent()) {
                return leftKey.equals(rightKey);
            }
            Object leftDirectValue = leftHolder.value();
            Object rightDirectValue = rightHolder.value();
            return leftDirectValue == rightDirectValue
                    || (leftDirectValue != null && leftDirectValue.equals(rightDirectValue));
        }
        return leftValue != null && leftValue.equals(rightValue);
    }

}

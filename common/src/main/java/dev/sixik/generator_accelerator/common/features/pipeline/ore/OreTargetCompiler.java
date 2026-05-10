package dev.sixik.generator_accelerator.common.features.pipeline.ore;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.features.FastTarget;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

import java.util.List;
import java.util.Optional;

public final class OreTargetCompiler {

    private OreTargetCompiler() {
    }

    public static OreTargetPlan compile(OreConfiguration config) {
        if (config.targetStates.isEmpty()) {
            return OreTargetPlan.EMPTY;
        }
        ensureStateCache();

        int stateCount = FastBlockStateCache.STATES.length;
        FastTarget[] targets = new FastTarget[config.targetStates.size()];
        boolean hasFallbackTargets = false;
        boolean placementMayBeAir = false;

        for (int i = 0; i < config.targetStates.size(); i++) {
            OreConfiguration.TargetBlockState target = config.targetStates.get(i);
            CompiledRule compiledRule = compileRule(target.target, stateCount);
            int placementStateId = fastStateId(target.state);

            targets[i] = new FastTarget(
                    compiledRule.singleStateId(),
                    compiledRule.validStateIds(),
                    compiledRule.validStateIdMask(),
                    compiledRule.fallbackRule(),
                    target.state,
                    placementStateId
            );
            hasFallbackTargets |= compiledRule.fallbackRule() != null;
            placementMayBeAir |= FastBlockStateCache.isAir(placementStateId);
        }

        return new OreTargetPlan(targets, hasFallbackTargets, placementMayBeAir);
    }

    public static FastTarget compileTarget(OreConfiguration.TargetBlockState target) {
        ensureStateCache();

        CompiledRule compiledRule = compileRule(target.target, FastBlockStateCache.STATES.length);
        int placementStateId = fastStateId(target.state);
        return new FastTarget(
                compiledRule.singleStateId(),
                compiledRule.validStateIds(),
                compiledRule.validStateIdMask(),
                compiledRule.fallbackRule(),
                target.state,
                placementStateId
        );
    }

    private static CompiledRule compileRule(RuleTest rule, int stateCount) {
        if (rule instanceof BlockMatchTest blockMatch) {
            StateSet stateSet = collectStateIds(blockMatch.block, stateCount);
            return new CompiledRule(stateSet.singleStateId(), stateSet.validStateIds(), stateSet.validStateIdMask(), null);
        }

        if (rule instanceof TagMatchTest tagMatch) {
            Optional<HolderSet.Named<Block>> tag = BuiltInRegistries.BLOCK.getTag(tagMatch.tag);
            if (tag.isEmpty()) {
                return new CompiledRule(-1, new int[0], null, null);
            }

            StateSet stateSet = collectStateIds(tag.get(), stateCount);
            return new CompiledRule(stateSet.singleStateId(), stateSet.validStateIds(), stateSet.validStateIdMask(), null);
        }

        return new CompiledRule(-1, null, null, rule);
    }

    private static StateSet collectStateIds(Block block, int stateCount) {
        List<BlockState> states = block.getStateDefinition().getPossibleStates();
        int size = states.size();
        if (size == 1) {
            return new StateSet(fastStateId(states.getFirst()), null, null);
        }

        IntArrayList ids = new IntArrayList(size);
        for (int i = 0; i < size; i++) {
            ids.add(fastStateId(states.get(i)));
        }
        return packStateIds(ids, stateCount);
    }

    private static StateSet collectStateIds(HolderSet.Named<Block> blocks, int stateCount) {
        IntArrayList ids = new IntArrayList();
        List<Holder<Block>> contents = blocks.contents();
        for (int i = 0; i < contents.size(); i++) {
            Block block = contents.get(i).value();
            List<BlockState> states = block.getStateDefinition().getPossibleStates();
            for (int stateIndex = 0; stateIndex < states.size(); stateIndex++) {
                ids.add(fastStateId(states.get(stateIndex)));
            }
        }
        return packStateIds(ids, stateCount);
    }

    private static StateSet packStateIds(IntArrayList ids, int stateCount) {
        int size = ids.size();
        if (size == 0) {
            return new StateSet(-1, new int[0], null);
        }
        if (size == 1) {
            return new StateSet(ids.getInt(0), null, null);
        }
        if (size <= 4) {
            return new StateSet(-1, ids.toIntArray(), null);
        }

        boolean[] mask = new boolean[stateCount];
        for (int i = 0; i < size; i++) {
            int stateId = ids.getInt(i);
            if (stateId >= 0 && stateId < stateCount) {
                mask[stateId] = true;
            }
        }
        return new StateSet(-1, null, mask);
    }

    private static void ensureStateCache() {
        if (FastBlockStateCache.STATES == null) {
            FastBlockStateCache.init(GeneratorAccelerator.platform);
        }
    }

    private static int fastStateId(BlockState state) {
        return GA$BlockStateExtension.get(state).bts$getFastId();
    }

    private record StateSet(int singleStateId, int[] validStateIds, boolean[] validStateIdMask) {
    }

    private record CompiledRule(int singleStateId, int[] validStateIds, boolean[] validStateIdMask, RuleTest fallbackRule) {
    }
}

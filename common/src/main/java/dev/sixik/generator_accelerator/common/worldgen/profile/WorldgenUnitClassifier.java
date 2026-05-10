package dev.sixik.generator_accelerator.common.worldgen.profile;

import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.CountConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SculkPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class WorldgenUnitClassifier {
    private static final String MINECRAFT_NAMESPACE = "minecraft";

    private WorldgenUnitClassifier() {
    }

    public static WorldgenUnitProfile classify(PlacedFeature feature) {
        if (feature == null) {
            return disabled("placed_feature:null", PlacedFeature.class, "null placed feature");
        }
        ConfiguredFeature<?, ?> configuredFeature;
        try {
            configuredFeature = feature.feature().value();
        } catch (RuntimeException failure) {
            return classifyClass(namespaceOf(feature.getClass()), feature.getClass());
        }
        WorldgenUnitProfile configured = classify(configuredFeature);
        EnumSet<WorldgenEffectFlag> flags = copyFlags(configured.effectFlags());
        WorldgenSafetyTier tier = configured.safetyTier();
        String namespace = configured.namespace();
        int estimatedCost = configured.estimatedCost() + 1;
        ArrayList<String> guards = new ArrayList<>(configured.guards());
        StringBuilder reason = new StringBuilder(configured.fallbackReason());

        for (PlacementModifier modifier : feature.placement()) {
            WorldgenUnitProfile modifierProfile = classify(modifier);
            WorldgenSafetyTier previousTier = tier;
            flags.addAll(modifierProfile.effectFlags());
            tier = moreConservative(tier, modifierProfile.safetyTier());
            namespace = moreConservativeNamespace(namespace, previousTier, modifierProfile);
            estimatedCost += modifierProfile.estimatedCost();
            guards.addAll(modifierProfile.guards());
            appendReason(reason, modifierProfile.fallbackReason());
        }

        return new WorldgenUnitProfile(
                "placed_feature:" + configured.id(),
                namespace,
                feature.getClass().getName(),
                "",
                "",
                0L,
                "PlacedFeature.place",
                estimatedCost,
                flags,
                tier,
                guards,
                reason.toString()
        );
    }

    public static WorldgenUnitProfile classify(ConfiguredFeature<?, ?> configuredFeature) {
        if (configuredFeature == null) {
            return disabled("configured_feature:null", ConfiguredFeature.class, "null configured feature");
        }
        return classifyFeature(configuredFeature.feature(), configuredFeature.config());
    }

    public static WorldgenUnitProfile classify(PlacementModifier modifier) {
        if (modifier == null) {
            return disabled("placement_modifier:null", PlacementModifier.class, "null placement modifier");
        }
        EnumSet<WorldgenEffectFlag> flags = EnumSet.of(WorldgenEffectFlag.PURE);
        WorldgenSafetyTier tier = WorldgenSafetyTier.PURE_READ_ONLY;
        String reason = "";

        if (modifier instanceof HeightmapPlacement) {
            flags.add(WorldgenEffectFlag.READS_HEIGHTMAP);
        } else if (modifier instanceof BiomeFilter) {
            flags.add(WorldgenEffectFlag.READS_BIOMES);
        } else if (modifier instanceof PlacementFilter) {
            flags.add(WorldgenEffectFlag.READS_BLOCKS);
            flags.add(WorldgenEffectFlag.CALLS_UNKNOWN_METHOD);
            tier = WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE;
            reason = "filter may call block predicates";
        } else if (modifier instanceof RepeatingPlacement || modifier instanceof RandomOffsetPlacement || modifier instanceof InSquarePlacement) {
            flags.add(WorldgenEffectFlag.USES_RANDOM);
        } else {
            return classifyClass(modifier.getClass());
        }

        return profile(
                "placement_modifier:" + simpleId(modifier.getClass()),
                namespaceOf(modifier.getClass()),
                modifier.getClass(),
                "PlacementModifier.getPositions",
                1,
                flags,
                tier,
                reason
        );
    }

    public static WorldgenUnitProfile classifyClass(Class<?> unitClass) {
        return classifyClass(namespaceOf(unitClass), unitClass);
    }

    public static WorldgenUnitProfile classifyClass(String namespace, Class<?> unitClass) {
        if (unitClass == null) {
            return disabled("class:null", Object.class, "null class");
        }
        if (MINECRAFT_NAMESPACE.equals(namespace)) {
            return profile(
                    "class:" + simpleId(unitClass),
                    namespace,
                    unitClass,
                    "",
                    1,
                    EnumSet.of(WorldgenEffectFlag.CALLS_UNKNOWN_METHOD),
                    WorldgenSafetyTier.SERIAL_ISOLATED,
                    "unknown vanilla worldgen class"
            );
        }
        return profile(
                "class:" + simpleId(unitClass),
                namespace,
                unitClass,
                "",
                1,
                EnumSet.of(WorldgenEffectFlag.CALLS_UNKNOWN_METHOD, WorldgenEffectFlag.USES_GLOBAL_MUTABLE_STATE),
                WorldgenSafetyTier.SERIAL_ISOLATED,
                "unknown namespace defaults to serial safe vanilla"
        );
    }

    public static String namespaceOf(Class<?> unitClass) {
        if (unitClass == null || unitClass.getPackageName().startsWith("net.minecraft.")) {
            return MINECRAFT_NAMESPACE;
        }
        String packageName = unitClass.getPackageName();
        int firstDot = packageName.indexOf('.');
        return firstDot < 0 ? packageName.toLowerCase(Locale.ROOT) : packageName.substring(0, firstDot).toLowerCase(Locale.ROOT);
    }

    private static WorldgenUnitProfile classifyFeature(Feature<?> feature, FeatureConfiguration config) {
        if (feature == null) {
            return disabled("feature:null", Feature.class, "null feature");
        }
        if (isNativeDeterministicFeature(feature, config)) {
            return profile(
                    "feature:" + simpleId(feature.getClass()),
                    MINECRAFT_NAMESPACE,
                    feature.getClass(),
                    "Feature.place",
                    4,
                    EnumSet.of(WorldgenEffectFlag.READS_BLOCKS, WorldgenEffectFlag.WRITES_BLOCKS, WorldgenEffectFlag.USES_RANDOM, WorldgenEffectFlag.ORDER_SENSITIVE),
                    WorldgenSafetyTier.GA_NATIVE_DETERMINISTIC_WRITES,
                    "known vanilla feature constant"
            );
        }
        if (isPartialNativeFeature(feature)) {
            return profile(
                    "feature:" + simpleId(feature.getClass()),
                    MINECRAFT_NAMESPACE,
                    feature.getClass(),
                    "Feature.place",
                    3,
                    EnumSet.of(WorldgenEffectFlag.READS_BLOCKS, WorldgenEffectFlag.WRITES_BLOCKS, WorldgenEffectFlag.USES_RANDOM, WorldgenEffectFlag.CALLS_UNKNOWN_METHOD),
                    WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE,
                    "known placement or selector acceleration only"
            );
        }
        return classifyClass(namespaceOf(feature.getClass()), feature.getClass());
    }

    private static boolean isNativeDeterministicFeature(Feature<?> feature, FeatureConfiguration config) {
        return ((feature == Feature.ORE || feature == Feature.SCATTERED_ORE)
                        && config instanceof OreConfiguration oreConfiguration
                        && canUseNativeOre(oreConfiguration))
                || feature == Feature.SIMPLE_BLOCK
                || (feature == Feature.SPRING && config instanceof SpringConfiguration)
                || feature == Feature.SEAGRASS
                || feature == Feature.KELP
                || (feature == Feature.SEA_PICKLE && config instanceof CountConfiguration)
                || feature == Feature.DISK
                || feature == Feature.BLOCK_COLUMN
                || feature == Feature.FREEZE_TOP_LAYER
                || (feature == Feature.LAKE && config instanceof LakeFeature.Configuration)
                || (feature == Feature.SCULK_PATCH && config instanceof SculkPatchConfiguration)
                || (isRandomPatchFeature(feature) && config instanceof RandomPatchConfiguration randomPatch && isNativeRandomPatch(randomPatch));
    }

    private static boolean isPartialNativeFeature(Feature<?> feature) {
        return feature == Feature.ORE
                || feature == Feature.SCATTERED_ORE
                || feature == Feature.TREE
                || feature == Feature.RANDOM_PATCH
                || feature == Feature.FLOWER
                || feature == Feature.NO_BONEMEAL_FLOWER
                || feature == Feature.RANDOM_SELECTOR
                || feature == Feature.RANDOM_BOOLEAN_SELECTOR
                || feature == Feature.SIMPLE_RANDOM_SELECTOR
                || feature == Feature.GEODE
                || feature == Feature.MONSTER_ROOM
                || feature == Feature.MULTIFACE_GROWTH
                || feature == Feature.DRIPSTONE_CLUSTER
                || feature == Feature.LARGE_DRIPSTONE
                || feature == Feature.POINTED_DRIPSTONE;
    }

    private static boolean canUseNativeOre(OreConfiguration config) {
        if (config.targetStates.isEmpty()) {
            return false;
        }
        for (int i = 0; i < config.targetStates.size(); i++) {
            if (!isRawOreWriteSafe(config.targetStates.get(i).state)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRawOreWriteSafe(net.minecraft.world.level.block.state.BlockState state) {
        return state != null && !state.isAir() && state.getFluidState().isEmpty();
    }

    private static boolean isNativeRandomPatch(RandomPatchConfiguration randomPatch) {
        ConfiguredFeature<?, ?> nested = nestedConfiguredFeature(randomPatch);
        if (nested == null) {
            return false;
        }
        return nested.feature() == Feature.SIMPLE_BLOCK || isSelectorFeature(nested.feature());
    }

    private static ConfiguredFeature<?, ?> nestedConfiguredFeature(RandomPatchConfiguration randomPatch) {
        try {
            PlacedFeature placedFeature = randomPatch.feature().value();
            return placedFeature == null ? null : placedFeature.feature().value();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static boolean isRandomPatchFeature(Feature<?> feature) {
        return feature == Feature.RANDOM_PATCH
                || feature == Feature.FLOWER
                || feature == Feature.NO_BONEMEAL_FLOWER;
    }

    private static boolean isSelectorFeature(Feature<?> feature) {
        return feature == Feature.RANDOM_SELECTOR
                || feature == Feature.RANDOM_BOOLEAN_SELECTOR
                || feature == Feature.SIMPLE_RANDOM_SELECTOR;
    }

    private static WorldgenUnitProfile disabled(String id, Class<?> unitClass, String reason) {
        return profile(
                id,
                namespaceOf(unitClass),
                unitClass,
                "",
                0,
                EnumSet.of(WorldgenEffectFlag.CALLS_UNKNOWN_METHOD),
                WorldgenSafetyTier.VANILLA_FALLBACK_DISABLED,
                reason
        );
    }

    private static WorldgenUnitProfile profile(
            String id,
            String namespace,
            Class<?> unitClass,
            String entryPoint,
            int estimatedCost,
            Set<WorldgenEffectFlag> flags,
            WorldgenSafetyTier tier,
            String reason
    ) {
        return new WorldgenUnitProfile(
                id,
                namespace,
                unitClass.getName(),
                "",
                "",
                0L,
                entryPoint,
                estimatedCost,
                flags,
                tier,
                List.of(),
                reason
        );
    }

    private static EnumSet<WorldgenEffectFlag> copyFlags(Set<WorldgenEffectFlag> flags) {
        if (flags == null || flags.isEmpty()) {
            return EnumSet.noneOf(WorldgenEffectFlag.class);
        }
        return EnumSet.copyOf(flags);
    }

    private static WorldgenSafetyTier moreConservative(WorldgenSafetyTier first, WorldgenSafetyTier second) {
        return first.id() >= second.id() ? first : second;
    }

    private static String moreConservativeNamespace(String current, WorldgenSafetyTier previousTier, WorldgenUnitProfile modifierProfile) {
        if (MINECRAFT_NAMESPACE.equals(modifierProfile.namespace())) {
            return current;
        }
        return modifierProfile.safetyTier().id() >= previousTier.id() ? modifierProfile.namespace() : current;
    }

    private static void appendReason(StringBuilder out, String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append("; ");
        }
        out.append(reason);
    }

    private static String simpleId(Class<?> unitClass) {
        return unitClass.getSimpleName().isEmpty() ? unitClass.getName() : unitClass.getSimpleName();
    }
}

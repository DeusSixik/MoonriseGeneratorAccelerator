package dev.sixik.generator_accelerator.common.worldgen.profile;

import net.minecraft.core.Holder;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldgenUnitClassifierTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void safetyTierIdsMatchPhaseFourContract() {
        assertEquals(0, WorldgenSafetyTier.PURE_READ_ONLY.id());
        assertEquals(1, WorldgenSafetyTier.GA_NATIVE_DETERMINISTIC_WRITES.id());
        assertEquals(2, WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE.id());
        assertEquals(3, WorldgenSafetyTier.TRANSACTIONAL_UNKNOWN.id());
        assertEquals(4, WorldgenSafetyTier.SERIAL_ISOLATED.id());
        assertEquals(5, WorldgenSafetyTier.VANILLA_FALLBACK_DISABLED.id());
    }

    @Test
    void profileDefensivelyCopiesMutableInputs() {
        Set<WorldgenEffectFlag> flags = java.util.EnumSet.of(WorldgenEffectFlag.PURE);
        WorldgenUnitProfile profile = new WorldgenUnitProfile(
                "id",
                "minecraft",
                "Class",
                null,
                null,
                0L,
                "entry",
                1,
                flags,
                WorldgenSafetyTier.PURE_READ_ONLY,
                List.of("guard"),
                null
        );

        flags.add(WorldgenEffectFlag.USES_IO);

        assertTrue(profile.hasEffect(WorldgenEffectFlag.PURE));
        assertEquals(false, profile.hasEffect(WorldgenEffectFlag.USES_IO));
        assertThrows(UnsupportedOperationException.class, () -> profile.effectFlags().add(WorldgenEffectFlag.USES_IO));
    }

    @Test
    void vanillaSimpleBlockClassifiesAsNativeDeterministicWrite() {
        WorldgenUnitProfile profile = WorldgenUnitClassifier.classify(configured(
                Feature.SIMPLE_BLOCK,
                new SimpleBlockConfiguration(BlockStateProvider.simple(Blocks.STONE))
        ));

        assertEquals(WorldgenSafetyTier.GA_NATIVE_DETERMINISTIC_WRITES, profile.safetyTier());
        assertTrue(profile.hasEffect(WorldgenEffectFlag.WRITES_BLOCKS));
        assertTrue(profile.hasEffect(WorldgenEffectFlag.USES_RANDOM));
    }

    @Test
    void placedFeatureDelegatesToConfiguredFeatureSafety() {
        PlacedFeature placed = new PlacedFeature(Holder.direct(configured(Feature.TREE, FeatureConfiguration.NONE)), List.of());

        WorldgenUnitProfile profile = WorldgenUnitClassifier.classify(placed);

        assertEquals(WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE, profile.safetyTier());
        assertTrue(profile.id().startsWith("placed_feature:"));
    }

    @Test
    void placedFeatureFoldsUnknownPlacementModifierIntoTier() {
        PlacedFeature placed = new PlacedFeature(
                Holder.direct(configured(
                        Feature.SIMPLE_BLOCK,
                        new SimpleBlockConfiguration(BlockStateProvider.simple(Blocks.STONE))
                )),
                List.of(new CustomPlacementModifier())
        );

        WorldgenUnitProfile profile = WorldgenUnitClassifier.classify(placed);

        assertEquals(WorldgenSafetyTier.SERIAL_ISOLATED, profile.safetyTier());
        assertEquals("dev", profile.namespace());
        assertTrue(profile.hasEffect(WorldgenEffectFlag.USES_GLOBAL_MUTABLE_STATE));
        assertTrue(profile.fallbackReason().contains("unknown namespace defaults"));
    }

    @Test
    void unsafeOreConfigDoesNotClaimNativeDeterministicTier() {
        WorldgenUnitProfile profile = WorldgenUnitClassifier.classify(configured(
                Feature.ORE,
                new OreConfiguration(
                        List.of(OreConfiguration.target(new BlockMatchTest(Blocks.STONE), Blocks.AIR.defaultBlockState())),
                        8
                )
        ));

        assertEquals(WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE, profile.safetyTier());
    }

    @Test
    void randomPatchOnlyClaimsNativeTierForSimpleNestedFeature() {
        PlacedFeature nestedTree = new PlacedFeature(Holder.direct(configured(Feature.TREE, FeatureConfiguration.NONE)), List.of());

        WorldgenUnitProfile profile = WorldgenUnitClassifier.classify(configured(
                Feature.RANDOM_PATCH,
                new RandomPatchConfiguration(4, 2, 1, Holder.direct(nestedTree))
        ));

        assertEquals(WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE, profile.safetyTier());
    }

    @Test
    @SuppressWarnings("unchecked")
    void placedFeatureClassifierDoesNotPropagateHolderFailure() {
        Holder<ConfiguredFeature<?, ?>> holder = mock(Holder.class);
        when(holder.value()).thenThrow(new IllegalStateException("unbound"));
        PlacedFeature placed = new PlacedFeature(holder, List.of());

        WorldgenUnitProfile profile = assertDoesNotThrow(() -> WorldgenUnitClassifier.classify(placed));

        assertEquals(WorldgenSafetyTier.SERIAL_ISOLATED, profile.safetyTier());
    }

    @Test
    void knownPlacementModifiersRemainPureReadOnly() {
        WorldgenUnitProfile inSquare = WorldgenUnitClassifier.classify(InSquarePlacement.spread());
        WorldgenUnitProfile biome = WorldgenUnitClassifier.classify(BiomeFilter.biome());

        assertEquals(WorldgenSafetyTier.PURE_READ_ONLY, inSquare.safetyTier());
        assertTrue(inSquare.hasEffect(WorldgenEffectFlag.USES_RANDOM));
        assertEquals(WorldgenSafetyTier.PURE_READ_ONLY, biome.safetyTier());
        assertTrue(biome.hasEffect(WorldgenEffectFlag.READS_BIOMES));
    }

    @Test
    void unknownClassesDefaultToSerialIsolated() {
        WorldgenUnitProfile profile = WorldgenUnitClassifier.classifyClass("examplemod", CustomWorldgenHook.class);

        assertEquals("examplemod", profile.namespace());
        assertEquals(WorldgenSafetyTier.SERIAL_ISOLATED, profile.safetyTier());
        assertTrue(profile.hasEffect(WorldgenEffectFlag.CALLS_UNKNOWN_METHOD));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ConfiguredFeature<?, ?> configured(Feature<?> feature, FeatureConfiguration config) {
        return new ConfiguredFeature((Feature) feature, config);
    }

    private static final class CustomWorldgenHook {
    }

    private static final class CustomPlacementModifier extends PlacementModifier {
        @Override
        public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos) {
            return Stream.of(pos);
        }

        @Override
        public PlacementModifierType<?> type() {
            return null;
        }
    }
}

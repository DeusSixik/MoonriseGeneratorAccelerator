package dev.sixik.generator_accelerator.common.surface.compiler;

import com.mojang.datafixers.util.Either;
import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SurfaceCompilerParityTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void irDagAndPhase3MatchLegacyForColumnIntervalRules() {
        SurfaceRules.RuleSource groupedColumn = groupedColumnRules();
        SurfaceRules.RuleSource interval = intervalRules();
        SurfaceRules.RuleSource mixed = mixedColumnIntervalRules();
        SurfaceRules.RuleSource contradiction = contradictionAndDedupRules();
        SurfaceRules.RuleSource[] rules = new SurfaceRules.RuleSource[]{
                groupedColumn,
                interval,
                mixed,
                contradiction
        };

        for (SurfaceRules.RuleSource rule : rules) {
            SurfaceProgram legacy = SurfaceRuleCompiler.compileLegacyDirect(rule);
            SurfaceProgram ir = SurfaceIRCompiler.compile(rule).program();

            assertEquals(legacy.requirements(), ir.requirements(), "requirements must match");
            assertEquals(legacy.mayWriteFluid(), ir.mayWriteFluid(), "fluid write flag must match");

            assertProgramOutput(rule, legacy, ir, fullMask());
            assertProgramOutput(rule, legacy, ir, sparseMask());
        }
    }

    private static void assertProgramOutput(SurfaceRules.RuleSource rule, SurfaceProgram legacy, SurfaceProgram ir, Mask4096 stoneMask) {
        VectorChunkContext ctx = context();
        SurfaceScratch legacyScratch = new SurfaceScratch();
        SurfaceScratch irScratch = new SurfaceScratch();
        int[] legacyBlocks = filledBlocks();
        int[] irBlocks = filledBlocks();

        legacy.apply(legacyBlocks, stoneMask, ctx, legacyScratch);
        ir.apply(irBlocks, stoneMask, ctx, irScratch);

        assertArrayEquals(legacyBlocks, irBlocks, rule.getClass().getName());
    }

    private static SurfaceRules.RuleSource groupedColumnRules() {
        SurfaceRules.ConditionSource plains = SurfaceRules.isBiome(Biomes.PLAINS);
        SurfaceRules.ConditionSource desert = SurfaceRules.isBiome(Biomes.DESERT);
        SurfaceRules.ConditionSource hole = SurfaceRules.hole();
        SurfaceRules.RuleSource sand = SurfaceRules.state(Blocks.SAND.defaultBlockState());
        SurfaceRules.RuleSource gravel = SurfaceRules.state(Blocks.GRAVEL.defaultBlockState());
        SurfaceRules.RuleSource stone = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(plains, sand),
                SurfaceRules.ifTrue(desert, sand),
                SurfaceRules.ifTrue(hole, sand),
                SurfaceRules.ifTrue(SurfaceRules.not(plains), gravel),
                stone
        );
    }

    private static SurfaceRules.RuleSource intervalRules() {
        SurfaceRules.ConditionSource y66 = SurfaceRules.yBlockCheck(VerticalAnchor.absolute(66), 0);
        SurfaceRules.ConditionSource y70 = SurfaceRules.yBlockCheck(VerticalAnchor.absolute(70), 0);
        SurfaceRules.ConditionSource water = SurfaceRules.waterBlockCheck(-1, 0);
        SurfaceRules.RuleSource grass = SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState());
        SurfaceRules.RuleSource dirt = SurfaceRules.state(Blocks.DIRT.defaultBlockState());
        SurfaceRules.RuleSource clay = SurfaceRules.state(Blocks.CLAY.defaultBlockState());
        SurfaceRules.RuleSource stone = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(y70, grass),
                SurfaceRules.ifTrue(water, clay),
                SurfaceRules.ifTrue(y66, dirt),
                stone
        );
    }

    private static SurfaceRules.RuleSource mixedColumnIntervalRules() {
        SurfaceRules.ConditionSource plains = SurfaceRules.isBiome(Biomes.PLAINS);
        SurfaceRules.ConditionSource notPlains = SurfaceRules.not(plains);
        SurfaceRules.ConditionSource y70 = SurfaceRules.yBlockCheck(VerticalAnchor.absolute(70), 0);
        SurfaceRules.ConditionSource y69WithDepth = SurfaceRules.yBlockCheck(VerticalAnchor.absolute(69), 1);
        SurfaceRules.ConditionSource abovePreliminary = SurfaceRules.abovePreliminarySurface();
        SurfaceRules.ConditionSource floor = SurfaceRules.stoneDepthCheck(0, false, CaveSurface.FLOOR);
        SurfaceRules.ConditionSource deepFloor = SurfaceRules.stoneDepthCheck(2, true, 3, CaveSurface.FLOOR);
        SurfaceRules.ConditionSource steep = SurfaceRules.steep();
        SurfaceRules.RuleSource grass = SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState());
        SurfaceRules.RuleSource dirt = SurfaceRules.state(Blocks.DIRT.defaultBlockState());
        SurfaceRules.RuleSource sand = SurfaceRules.state(Blocks.SAND.defaultBlockState());
        SurfaceRules.RuleSource gravel = SurfaceRules.state(Blocks.GRAVEL.defaultBlockState());
        SurfaceRules.RuleSource clay = SurfaceRules.state(Blocks.CLAY.defaultBlockState());
        SurfaceRules.RuleSource stone = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(plains, SurfaceRules.ifTrue(y70, clay)),
                SurfaceRules.ifTrue(notPlains, SurfaceRules.ifTrue(y70, sand)),
                SurfaceRules.ifTrue(steep, gravel),
                SurfaceRules.ifTrue(floor, grass),
                SurfaceRules.ifTrue(abovePreliminary, dirt),
                SurfaceRules.ifTrue(deepFloor, sand),
                SurfaceRules.ifTrue(y69WithDepth, stone),
                stone
        );
    }

    private static SurfaceRules.RuleSource contradictionAndDedupRules() {
        SurfaceRules.ConditionSource plains = SurfaceRules.isBiome(Biomes.PLAINS);
        SurfaceRules.ConditionSource notPlains = SurfaceRules.not(plains);
        SurfaceRules.ConditionSource y70 = SurfaceRules.yBlockCheck(VerticalAnchor.absolute(70), 0);
        SurfaceRules.RuleSource grass = SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState());
        SurfaceRules.RuleSource sand = SurfaceRules.state(Blocks.SAND.defaultBlockState());
        SurfaceRules.RuleSource stone = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(plains, SurfaceRules.ifTrue(notPlains, grass)),
                SurfaceRules.ifTrue(plains, SurfaceRules.ifTrue(plains, SurfaceRules.ifTrue(y70, sand))),
                stone
        );
    }

    @SuppressWarnings("unchecked")
    private static VectorChunkContext context() {
        Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder<?>[256];
        for (int xz = 0; xz < 256; xz++) {
            biomes[xz] = new TestBiomeHolder((xz & 1) == 0 ? Biomes.PLAINS : Biomes.DESERT);
        }
        VectorChunkContext ctx = new VectorChunkContext(biomes, Block.getId(Blocks.STONE.defaultBlockState()), null, null, null);
        ctx.updateForSection(0, 64, 0);
        for (int xz = 0; xz < 256; xz++) {
            ctx.surfaceDepths[xz] = (xz & 3) == 0 ? 0 : 3;
            ctx.surfaceHeights[xz] = (short) (((xz >> 4) & 1) == 0 ? 64 : 71);
            ctx.minSurfaceLevels[xz] = 68 + (xz & 1);
            ctx.waterHeights[xz] = (xz & 7) == 0 ? 68 : Integer.MIN_VALUE;
        }
        Arrays.fill(ctx.secondarySurfaceNoises, 0.25D);
        for (int i = 0; i < 4096; i++) {
            int y = i >> 8;
            ctx.stoneDepthAbove[i] = (byte) (16 - y);
            ctx.stoneDepthBelow[i] = (byte) (y + 1);
        }
        return ctx;
    }

    private static int[] filledBlocks() {
        int[] blocks = new int[4096];
        Arrays.fill(blocks, Block.getId(Blocks.STONE.defaultBlockState()));
        return blocks;
    }

    private static Mask4096 fullMask() {
        Mask4096 mask = new Mask4096();
        mask.fill();
        return mask;
    }

    private static Mask4096 sparseMask() {
        Mask4096 mask = new Mask4096();
        for (int i = 0; i < 4096; i += 3) {
            mask.set(i);
        }
        return mask;
    }

    private record TestBiomeHolder(ResourceKey<Biome> key) implements Holder<Biome> {
        @Override
        public Biome value() {
            return null;
        }

        @Override
        public boolean isBound() {
            return true;
        }

        @Override
        public boolean is(ResourceLocation location) {
            return this.key.location().equals(location);
        }

        @Override
        public boolean is(ResourceKey<Biome> key) {
            return this.key.equals(key);
        }

        @Override
        public boolean is(Predicate<ResourceKey<Biome>> predicate) {
            return predicate.test(this.key);
        }

        @Override
        public boolean is(TagKey<Biome> tagKey) {
            return false;
        }

        @Override
        public boolean is(Holder<Biome> holder) {
            return holder.unwrapKey().filter(this.key::equals).isPresent();
        }

        @Override
        public Stream<TagKey<Biome>> tags() {
            return Stream.empty();
        }

        @Override
        public Either<ResourceKey<Biome>, Biome> unwrap() {
            return Either.left(this.key);
        }

        @Override
        public Optional<ResourceKey<Biome>> unwrapKey() {
            return Optional.of(this.key);
        }

        @Override
        public Kind kind() {
            return Kind.REFERENCE;
        }

        @Override
        public boolean canSerializeIn(HolderOwner<Biome> holderOwner) {
            return true;
        }
    }
}

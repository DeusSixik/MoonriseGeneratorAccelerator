package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.lang.reflect.Method;
import java.util.Map;

final class AlexsCavesSurfaceCompilerData {
    private static final String AC_BIOME_CONDITION_SOURCE =
            "com.github.alexmodguy.alexscaves.server.level.surface.ACSurfaceRuleConditionRegistry$ACBiomeConditionSource";
    private static final String SIMPLEX_CONDITION_SOURCE =
            "com.github.alexmodguy.alexscaves.server.level.surface.ACSurfaceRuleConditionRegistry$SimplexConditionSource";

    private AlexsCavesSurfaceCompilerData() {
    }

    static SurfaceConditionNode compileCondition(SurfaceRules.ConditionSource conditionSource) {
        String className = conditionSource.getClass().getName();
        if (AC_BIOME_CONDITION_SOURCE.equals(className)) {
            Integer rarityOffset = invokeIntAccessor(conditionSource, "rarityOffset");
            return rarityOffset == null ? null : new ACBiomeSurfaceConditionNode(rarityOffset);
        }
        if (SIMPLEX_CONDITION_SOURCE.equals(className)) {
            Float noiseMin = invokeFloatAccessor(conditionSource, "noiseMin");
            Float noiseMax = invokeFloatAccessor(conditionSource, "noiseMax");
            Float noiseScale = invokeFloatAccessor(conditionSource, "noiseScale");
            Float yScale = invokeFloatAccessor(conditionSource, "yScale");
            Integer offsetType = invokeIntAccessor(conditionSource, "offsetType");
            if (noiseMin == null || noiseMax == null || noiseScale == null || yScale == null || offsetType == null) {
                return null;
            }
            return new SimplexSurfaceConditionNode(noiseMin, noiseMax, noiseScale, yScale, offsetType);
        }
        return null;
    }

    private static Integer invokeIntAccessor(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return (Integer) method.invoke(target);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private static Float invokeFloatAccessor(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return (Float) method.invoke(target);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private static boolean isOverworld(ResourceKey<?> dimension, boolean nullDimensionMatches) {
        return dimension == null ? nullDimensionMatches : Level.OVERWORLD.equals(dimension);
    }

    private static final class ACBiomeSurfaceConditionNode implements SurfaceConditionNode {
        private final int rarityOffset;

        private ACBiomeSurfaceConditionNode(int rarityOffset) {
            this.rarityOffset = rarityOffset;
        }

        @Override
        public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
            if (activeMask.isEmpty()) {
                return;
            }
            if (!isOverworld(Reflection.getDimension(), false)) {
                activeMask.clear();
                return;
            }
            long seed = Reflection.getSeed();
            if (seed == 0L) {
                activeMask.clear();
                return;
            }
            if (ctx.sectionStartY > 50) {
                activeMask.clear();
                return;
            }

            if (ctx.sectionStartY + 15 <= 50) {
                activeMask.computeActiveColumns(scratch.activeColumns);
                for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
                    long columnWord = scratch.activeColumns[columnWordIndex];
                    while (columnWord != 0L) {
                        int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);
                        if (!this.matchesColumn(seed, ctx, xz)) {
                            activeMask.clearColumn(xz);
                        }
                        columnWord &= columnWord - 1L;
                    }
                }
                return;
            }

            long[] words = activeMask.words();
            for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
                long word = words[wordIndex];
                while (word != 0L) {
                    int bit = Long.numberOfTrailingZeros(word);
                    int index = (wordIndex << 6) + bit;
                    int y = ctx.sectionStartY + (index >> 8);
                    if (y > 50 || !this.matchesColumn(seed, ctx, index & 255)) {
                        activeMask.clear(index);
                    }
                    word &= word - 1L;
                }
            }
        }

        private boolean matchesColumn(long seed, VectorChunkContext ctx, int xz) {
            int x = ctx.sectionStartX + (xz & 15);
            int z = ctx.sectionStartZ + (xz >> 4);
            Object rareBiomeInfo = Reflection.getRareBiomeInfoForQuad(seed, x >> 2, z >> 2);
            if (rareBiomeInfo == null || Reflection.getRareBiomeOffsetId(rareBiomeInfo) != this.rarityOffset) {
                return false;
            }
            Object biomeKey = Reflection.getACBiomeForPosition(seed, x, z);
            if (biomeKey == null) {
                return false;
            }
            Object biomeCondition = Reflection.getBiomeCondition(biomeKey);
            return biomeCondition != null && Reflection.getBiomeRarityOffset(biomeCondition) == this.rarityOffset;
        }

        @Override
        public int requirements() {
            return 0;
        }
    }

    private static final class SimplexSurfaceConditionNode implements SurfaceConditionNode {
        private final float noiseMin;
        private final float noiseMax;
        private final float noiseScale;
        private final float yScale;
        private final int offsetType;

        private SimplexSurfaceConditionNode(float noiseMin, float noiseMax, float noiseScale, float yScale, int offsetType) {
            this.noiseMin = noiseMin;
            this.noiseMax = noiseMax;
            this.noiseScale = noiseScale;
            this.yScale = yScale;
            this.offsetType = offsetType;
        }

        @Override
        public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
            if (activeMask.isEmpty()) {
                return;
            }
            if (!isOverworld(Reflection.getDimension(), true)) {
                activeMask.clear();
                return;
            }

            long[] words = activeMask.words();
            for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
                long word = words[wordIndex];
                while (word != 0L) {
                    int bit = Long.numberOfTrailingZeros(word);
                    int index = (wordIndex << 6) + bit;
                    int xz = index & 255;
                    int x = ctx.sectionStartX + (xz & 15);
                    int y = ctx.sectionStartY + (index >> 8);
                    int z = ctx.sectionStartZ + (xz >> 4);
                    float noise = Reflection.sampleNoise3D(
                            x + this.offsetType * 1000,
                            (int) (y * this.yScale + this.offsetType * 2000.0F),
                            z - this.offsetType * 3000,
                            this.noiseScale
                    );
                    if (!(noise > this.noiseMin && noise <= this.noiseMax)) {
                        activeMask.clear(index);
                    }
                    word &= word - 1L;
                }
            }
        }

        @Override
        public int requirements() {
            return 0;
        }
    }

    private static final class Reflection {
        private static final Method GET_SEED;
        private static final Method GET_DIMENSION;
        private static final Method GET_RARE_BIOME_INFO_FOR_QUAD;
        private static final Method GET_RARE_BIOME_OFFSET_ID;
        private static final Method GET_AC_BIOME_FOR_POSITION;
        private static final Method GET_BIOMES_SNAPSHOT;
        private static final Method GET_RARITY_OFFSET;
        private static final Method SAMPLE_NOISE_3D;

        static {
            Method getSeed = null;
            Method getDimension = null;
            Method getRareBiomeInfoForQuad = null;
            Method getRareBiomeOffsetId = null;
            Method getACBiomeForPosition = null;
            Method getBiomesSnapshot = null;
            Method getRarityOffset = null;
            Method sampleNoise3D = null;
            try {
                ClassLoader loader = AlexsCavesSurfaceCompilerData.class.getClassLoader();
                Class<?> seedHolder = Class.forName("com.github.alexmodguy.alexscaves.server.level.biome.ACWorldSeedHolder", false, loader);
                Class<?> biomeRarity = Class.forName("com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeRarity", false, loader);
                Class<?> voronoiInfo = Class.forName("com.github.alexmodguy.alexscaves.server.misc.VoronoiGenerator$VoronoiInfo", false, loader);
                Class<?> biomeGenerationConfig = Class.forName("com.github.alexmodguy.alexscaves.server.config.BiomeGenerationConfig", false, loader);
                Class<?> biomeGenerationNoiseCondition = Class.forName("com.github.alexmodguy.alexscaves.server.config.BiomeGenerationNoiseCondition", false, loader);
                Class<?> acMath = Class.forName("com.github.alexmodguy.alexscaves.server.misc.ACMath", false, loader);

                getSeed = seedHolder.getMethod("getSeed");
                getDimension = seedHolder.getMethod("getDimension");
                getRareBiomeInfoForQuad = biomeRarity.getMethod("getRareBiomeInfoForQuad", long.class, int.class, int.class);
                getRareBiomeOffsetId = biomeRarity.getMethod("getRareBiomeOffsetId", voronoiInfo);
                getACBiomeForPosition = biomeRarity.getMethod("getACBiomeForPosition", long.class, int.class, int.class);
                getBiomesSnapshot = biomeGenerationConfig.getMethod("getBiomesSnapshot");
                getRarityOffset = biomeGenerationNoiseCondition.getMethod("getRarityOffset");
                sampleNoise3D = acMath.getMethod("sampleNoise3D", int.class, int.class, int.class, float.class);
            } catch (ReflectiveOperationException ignored) {
            }
            GET_SEED = getSeed;
            GET_DIMENSION = getDimension;
            GET_RARE_BIOME_INFO_FOR_QUAD = getRareBiomeInfoForQuad;
            GET_RARE_BIOME_OFFSET_ID = getRareBiomeOffsetId;
            GET_AC_BIOME_FOR_POSITION = getACBiomeForPosition;
            GET_BIOMES_SNAPSHOT = getBiomesSnapshot;
            GET_RARITY_OFFSET = getRarityOffset;
            SAMPLE_NOISE_3D = sampleNoise3D;
        }

        private static long getSeed() {
            try {
                return GET_SEED == null ? 0L : (Long) GET_SEED.invoke(null);
            } catch (ReflectiveOperationException | ClassCastException e) {
                return 0L;
            }
        }

        @SuppressWarnings("unchecked")
        private static ResourceKey<?> getDimension() {
            try {
                return GET_DIMENSION == null ? null : (ResourceKey<?>) GET_DIMENSION.invoke(null);
            } catch (ReflectiveOperationException | ClassCastException e) {
                return null;
            }
        }

        private static Object getRareBiomeInfoForQuad(long seed, int quartX, int quartZ) {
            try {
                return GET_RARE_BIOME_INFO_FOR_QUAD == null ? null : GET_RARE_BIOME_INFO_FOR_QUAD.invoke(null, seed, quartX, quartZ);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        private static int getRareBiomeOffsetId(Object rareBiomeInfo) {
            try {
                return GET_RARE_BIOME_OFFSET_ID == null ? Integer.MIN_VALUE : (Integer) GET_RARE_BIOME_OFFSET_ID.invoke(null, rareBiomeInfo);
            } catch (ReflectiveOperationException | ClassCastException e) {
                return Integer.MIN_VALUE;
            }
        }

        private static Object getACBiomeForPosition(long seed, int x, int z) {
            try {
                return GET_AC_BIOME_FOR_POSITION == null ? null : GET_AC_BIOME_FOR_POSITION.invoke(null, seed, x, z);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        private static Object getBiomeCondition(Object biomeKey) {
            try {
                if (GET_BIOMES_SNAPSHOT == null) {
                    return null;
                }
                Object snapshot = GET_BIOMES_SNAPSHOT.invoke(null);
                return snapshot instanceof Map<?, ?> map ? map.get(biomeKey) : null;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        private static int getBiomeRarityOffset(Object biomeCondition) {
            try {
                return GET_RARITY_OFFSET == null ? Integer.MIN_VALUE : (Integer) GET_RARITY_OFFSET.invoke(biomeCondition);
            } catch (ReflectiveOperationException | ClassCastException e) {
                return Integer.MIN_VALUE;
            }
        }

        private static float sampleNoise3D(int x, int y, int z, float scale) {
            try {
                return SAMPLE_NOISE_3D == null ? Float.NaN : (Float) SAMPLE_NOISE_3D.invoke(null, x, y, z, scale);
            } catch (ReflectiveOperationException | ClassCastException e) {
                return Float.NaN;
            }
        }
    }
}

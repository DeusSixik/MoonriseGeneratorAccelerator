package dev.sixik.generator_accelerator.common.surface.compiler.ir;

import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceRequirements;

public final class SurfaceIRAnalyzer {
    private SurfaceIRAnalyzer() {
    }

    public static int requirements(SurfaceRuleIR rule) {
        return switch (rule) {
            case SurfaceRuleIR.Empty ignored -> 0;
            case SurfaceRuleIR.Block ignored -> 0;
            case SurfaceRuleIR.Sequence sequence -> {
                int req = 0;
                for (SurfaceRuleIR child : sequence.rules()) {
                    req |= requirements(child);
                }
                yield req;
            }
            case SurfaceRuleIR.Test test -> requirements(test.condition()) | requirements(test.thenRun());
            case SurfaceRuleIR.FallbackRule ignored -> fallbackRequirements();
        };
    }

    public static int requirements(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Constant ignored -> 0;
            case SurfaceConditionIR.BiomeCondition ignored -> SurfaceRequirements.BIOME;
            case SurfaceConditionIR.HolderSetBiomeCondition ignored -> SurfaceRequirements.BIOME;
            case SurfaceConditionIR.StoneDepth stoneDepth -> {
                int req = SurfaceRequirements.STONE_DEPTH;
                if (stoneDepth.addSurfaceDepth() || stoneDepth.secondaryDepthRange() != 0) {
                    req |= SurfaceRequirements.SURFACE_DEPTH;
                }
                if (stoneDepth.secondaryDepthRange() != 0) {
                    req |= SurfaceRequirements.SECONDARY_SURFACE;
                }
                yield req;
            }
            case SurfaceConditionIR.Y y -> {
                int req = SurfaceRequirements.SURFACE_DEPTH;
                if (y.addStoneDepth()) {
                    req |= SurfaceRequirements.STONE_DEPTH;
                }
                yield req;
            }
            case SurfaceConditionIR.NoiseThreshold ignored -> SurfaceRequirements.NOISE;
            case SurfaceConditionIR.VerticalGradient ignored -> SurfaceRequirements.RANDOM;
            case SurfaceConditionIR.AbovePreliminarySurface ignored -> SurfaceRequirements.PRELIMINARY_SURFACE;
            case SurfaceConditionIR.Water water -> {
                int req = SurfaceRequirements.WATER | SurfaceRequirements.SURFACE_DEPTH;
                if (water.addStoneDepth()) {
                    req |= SurfaceRequirements.STONE_DEPTH;
                }
                yield req;
            }
            case SurfaceConditionIR.Temperature ignored -> SurfaceRequirements.TEMPERATURE | SurfaceRequirements.BIOME;
            case SurfaceConditionIR.Steep ignored -> SurfaceRequirements.SLOPE;
            case SurfaceConditionIR.Hole ignored -> SurfaceRequirements.SURFACE_DEPTH;
            case SurfaceConditionIR.Not not -> requirements(not.target());
            case SurfaceConditionIR.AllOf allOf -> conditionListRequirements(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> conditionListRequirements(anyOf.conditions());
            case SurfaceConditionIR.FallbackCondition ignored -> fallbackRequirements();
        };
    }

    public static boolean mayWriteFluid(SurfaceRuleIR rule) {
        return switch (rule) {
            case SurfaceRuleIR.Empty ignored -> false;
            case SurfaceRuleIR.Block block -> block.mayWriteFluid();
            case SurfaceRuleIR.Sequence sequence -> {
                boolean fluid = false;
                for (SurfaceRuleIR child : sequence.rules()) {
                    fluid |= mayWriteFluid(child);
                }
                yield fluid;
            }
            case SurfaceRuleIR.Test test -> mayWriteFluid(test.thenRun());
            case SurfaceRuleIR.FallbackRule ignored -> true;
        };
    }

    public static SurfaceIRDomain domain(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Constant ignored -> SurfaceIRDomain.COLUMN;
            case SurfaceConditionIR.BiomeCondition ignored -> SurfaceIRDomain.COLUMN;
            case SurfaceConditionIR.HolderSetBiomeCondition ignored -> SurfaceIRDomain.COLUMN;
            case SurfaceConditionIR.NoiseThreshold ignored -> SurfaceIRDomain.COLUMN;
            case SurfaceConditionIR.Steep ignored -> SurfaceIRDomain.COLUMN;
            case SurfaceConditionIR.Hole ignored -> SurfaceIRDomain.COLUMN;
            case SurfaceConditionIR.Y y -> y.addStoneDepth() ? SurfaceIRDomain.VOXEL : SurfaceIRDomain.INTERVAL;
            case SurfaceConditionIR.AbovePreliminarySurface ignored -> SurfaceIRDomain.INTERVAL;
            case SurfaceConditionIR.Water water -> water.addStoneDepth() ? SurfaceIRDomain.VOXEL : SurfaceIRDomain.INTERVAL;
            case SurfaceConditionIR.StoneDepth ignored -> SurfaceIRDomain.VOXEL;
            case SurfaceConditionIR.Temperature ignored -> SurfaceIRDomain.VOXEL;
            case SurfaceConditionIR.VerticalGradient ignored -> SurfaceIRDomain.VOXEL;
            case SurfaceConditionIR.Not not -> domain(not.target());
            case SurfaceConditionIR.AllOf allOf -> mergeDomains(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> mergeDomains(anyOf.conditions());
            case SurfaceConditionIR.FallbackCondition ignored -> SurfaceIRDomain.FALLBACK;
        };
    }

    public static SurfaceIRPurity purity(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.VerticalGradient ignored -> SurfaceIRPurity.DETERMINISTIC_RANDOM;
            case SurfaceConditionIR.Not not -> purity(not.target());
            case SurfaceConditionIR.AllOf allOf -> mergePurity(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> mergePurity(anyOf.conditions());
            case SurfaceConditionIR.FallbackCondition ignored -> SurfaceIRPurity.FALLBACK;
            default -> SurfaceIRPurity.PURE;
        };
    }

    public static int ruleCount(SurfaceRuleIR rule) {
        return switch (rule) {
            case SurfaceRuleIR.Empty ignored -> 1;
            case SurfaceRuleIR.Block ignored -> 1;
            case SurfaceRuleIR.Sequence sequence -> {
                int count = 1;
                for (SurfaceRuleIR child : sequence.rules()) {
                    count += ruleCount(child);
                }
                yield count;
            }
            case SurfaceRuleIR.Test test -> 1 + ruleCount(test.thenRun());
            case SurfaceRuleIR.FallbackRule ignored -> 1;
        };
    }

    public static int conditionCount(SurfaceRuleIR rule) {
        return switch (rule) {
            case SurfaceRuleIR.Empty ignored -> 0;
            case SurfaceRuleIR.Block ignored -> 0;
            case SurfaceRuleIR.Sequence sequence -> {
                int count = 0;
                for (SurfaceRuleIR child : sequence.rules()) {
                    count += conditionCount(child);
                }
                yield count;
            }
            case SurfaceRuleIR.Test test -> conditionCount(test.condition()) + conditionCount(test.thenRun());
            case SurfaceRuleIR.FallbackRule ignored -> 0;
        };
    }

    public static int conditionCount(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Not not -> 1 + conditionCount(not.target());
            case SurfaceConditionIR.AllOf allOf -> 1 + nestedConditionCount(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> 1 + nestedConditionCount(anyOf.conditions());
            case SurfaceConditionIR.FallbackCondition ignored -> 1;
            default -> 1;
        };
    }

    public static int fallbackRuleCount(SurfaceRuleIR rule) {
        return switch (rule) {
            case SurfaceRuleIR.Empty ignored -> 0;
            case SurfaceRuleIR.Block ignored -> 0;
            case SurfaceRuleIR.FallbackRule ignored -> 1;
            case SurfaceRuleIR.Sequence sequence -> {
                int count = 0;
                for (SurfaceRuleIR child : sequence.rules()) {
                    count += fallbackRuleCount(child);
                }
                yield count;
            }
            case SurfaceRuleIR.Test test -> fallbackRuleCount(test.thenRun());
        };
    }

    public static int fallbackConditionCount(SurfaceRuleIR rule) {
        return switch (rule) {
            case SurfaceRuleIR.Empty ignored -> 0;
            case SurfaceRuleIR.Block ignored -> 0;
            case SurfaceRuleIR.FallbackRule ignored -> 0;
            case SurfaceRuleIR.Sequence sequence -> {
                int count = 0;
                for (SurfaceRuleIR child : sequence.rules()) {
                    count += fallbackConditionCount(child);
                }
                yield count;
            }
            case SurfaceRuleIR.Test test -> fallbackConditionCount(test.condition()) + fallbackConditionCount(test.thenRun());
        };
    }

    public static int fallbackConditionCount(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.FallbackCondition ignored -> 1;
            case SurfaceConditionIR.Not not -> fallbackConditionCount(not.target());
            case SurfaceConditionIR.AllOf allOf -> nestedFallbackConditionCount(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> nestedFallbackConditionCount(anyOf.conditions());
            default -> 0;
        };
    }

    private static int conditionListRequirements(Iterable<SurfaceConditionIR> conditions) {
        int req = 0;
        for (SurfaceConditionIR condition : conditions) {
            req |= requirements(condition);
        }
        return req;
    }

    private static SurfaceIRDomain mergeDomains(Iterable<SurfaceConditionIR> conditions) {
        SurfaceIRDomain current = null;
        for (SurfaceConditionIR condition : conditions) {
            SurfaceIRDomain domain = domain(condition);
            if (current == null) {
                current = domain;
            } else if (current != domain) {
                return SurfaceIRDomain.MIXED;
            }
        }
        return current == null ? SurfaceIRDomain.COLUMN : current;
    }

    private static SurfaceIRPurity mergePurity(Iterable<SurfaceConditionIR> conditions) {
        SurfaceIRPurity purity = SurfaceIRPurity.PURE;
        for (SurfaceConditionIR condition : conditions) {
            SurfaceIRPurity child = purity(condition);
            if (child == SurfaceIRPurity.FALLBACK) {
                return SurfaceIRPurity.FALLBACK;
            }
            if (child == SurfaceIRPurity.DETERMINISTIC_RANDOM) {
                purity = SurfaceIRPurity.DETERMINISTIC_RANDOM;
            }
        }
        return purity;
    }

    private static int nestedConditionCount(Iterable<SurfaceConditionIR> conditions) {
        int count = 0;
        for (SurfaceConditionIR condition : conditions) {
            count += conditionCount(condition);
        }
        return count;
    }

    private static int nestedFallbackConditionCount(Iterable<SurfaceConditionIR> conditions) {
        int count = 0;
        for (SurfaceConditionIR condition : conditions) {
            count += fallbackConditionCount(condition);
        }
        return count;
    }

    private static int fallbackRequirements() {
        return SurfaceRequirements.FALLBACK
                | SurfaceRequirements.BIOME
                | SurfaceRequirements.STONE_DEPTH
                | SurfaceRequirements.WATER
                | SurfaceRequirements.SURFACE_DEPTH
                | SurfaceRequirements.SECONDARY_SURFACE
                | SurfaceRequirements.PRELIMINARY_SURFACE
                | SurfaceRequirements.TEMPERATURE
                | SurfaceRequirements.NOISE
                | SurfaceRequirements.RANDOM
                | SurfaceRequirements.SLOPE;
    }
}

package dev.sixik.generator_accelerator.common.surface.compiler.ir;

import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.List;

public sealed interface SurfaceRuleIR permits
        SurfaceRuleIR.Empty,
        SurfaceRuleIR.Block,
        SurfaceRuleIR.Sequence,
        SurfaceRuleIR.Test,
        SurfaceRuleIR.FallbackRule {

    record Empty() implements SurfaceRuleIR {
    }

    record Block(int blockId, boolean mayWriteFluid) implements SurfaceRuleIR {
    }

    record Sequence(List<SurfaceRuleIR> rules) implements SurfaceRuleIR {
        public Sequence {
            rules = List.copyOf(rules);
        }
    }

    record Test(SurfaceConditionIR condition, SurfaceRuleIR thenRun) implements SurfaceRuleIR {
    }

    record FallbackRule(SurfaceRules.RuleSource source) implements SurfaceRuleIR {
    }
}

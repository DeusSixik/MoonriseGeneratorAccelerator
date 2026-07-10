package net.minecraft.world.level.levelgen;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

public final class TestVanillaLikeSequenceRuleSource implements SurfaceRules.RuleSource {
    private final List<SurfaceRules.RuleSource> sequence = List.of(
            SurfaceRules.state(Blocks.STONE.defaultBlockState())
    );

    public List<SurfaceRules.RuleSource> sequence() {
        return this.sequence;
    }

    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        return (x, y, z) -> null;
    }

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        return SurfaceRules.state(Blocks.AIR.defaultBlockState()).codec();
    }
}

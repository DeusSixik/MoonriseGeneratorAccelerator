package dev.sixik.generator_accelerator.common.surface_compiler.semantic;

import dev.sixik.generator_accelerator.common.surface_compiler.frontend.SurfaceIrBuilder;
import dev.sixik.generator_accelerator.common.surface_compiler.frontend.SurfaceRuleScanner;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import net.minecraft.world.level.levelgen.SurfaceRules;

/** Debug-only semantic helper; Mojang classes remain the authoritative oracle. */
public final class BaselineSurfaceInterpreter {
    private final SurfaceRuleScanner scanner = new SurfaceRuleScanner();
    private final SurfaceIrBuilder builder = new SurfaceIrBuilder();

    public SurfaceProgramIr debugIr(SurfaceRules.RuleSource source) {
        return this.builder.build(this.scanner.scan(source));
    }
}

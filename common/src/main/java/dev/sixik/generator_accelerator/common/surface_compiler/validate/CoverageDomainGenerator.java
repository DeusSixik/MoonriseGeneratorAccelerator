package dev.sixik.generator_accelerator.common.surface_compiler.validate;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceDomain;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;

import java.util.LinkedHashSet;
import java.util.Set;

public final class CoverageDomainGenerator {
    public Set<SurfaceDomain> domains(SurfaceProgramIr ir) {
        Set<SurfaceDomain> out = new LinkedHashSet<>();
        if (ir != null) {
            ir.ops().forEach(op -> out.add(op.domain()));
        }
        return out;
    }
}

package dev.sixik.generator_accelerator.common.surface_compiler.validate;

import dev.sixik.generator_accelerator.common.surface_compiler.SurfaceCompilerConfig;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.template.ShapeTemplateBackend;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceNode;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;

import java.util.EnumSet;
import java.util.Set;

public final class SyntheticCoverageRunner {
    private final CoverageDomainGenerator domains = new CoverageDomainGenerator();
    private final ShapeTemplateBackend templates = new ShapeTemplateBackend();

    public CoverageStatus run(SurfaceProgramIr ir) {
        return report(ir).status();
    }

    public CoverageReport report(SurfaceProgramIr ir) {
        if (!SurfaceCompilerConfig.ENABLE_TIER0_DIRECT) {
            return new CoverageReport(CoverageStatus.NOT_CERTIFIED, matrix(ir), false, RejectionReason.TIER0_DISABLED);
        }
        if (ir == null || !ir.tokenChainIsLinear()) {
            return new CoverageReport(CoverageStatus.NOT_CERTIFIED, matrix(ir), false, RejectionReason.NON_LINEAR_STATE_TRACE);
        }
        if (this.templates.canUseDirectTemplate(ir)) {
            return new CoverageReport(CoverageStatus.PASSED, matrix(ir), true, RejectionReason.NONE);
        }
        CoverageMatrix matrix = CoverageMatrix.from(ir, this.domains.domains(ir));
        int domains = matrix.domainCount();
        RejectionReason reason = rejectionReason(ir, matrix, domains);
        if (reason == RejectionReason.NONE) {
            return new CoverageReport(CoverageStatus.PASSED, matrix, false, RejectionReason.NONE);
        }
        return new CoverageReport(CoverageStatus.NOT_CERTIFIED, matrix, false, reason);
    }

    public CoverageMatrix matrix(SurfaceProgramIr ir) {
        return CoverageMatrix.from(ir, this.domains.domains(ir));
    }

    public enum CoverageStatus {
        PASSED,
        NOT_CERTIFIED
    }

    public enum RejectionReason {
        NONE,
        TIER0_DISABLED,
        NON_LINEAR_STATE_TRACE,
        TOO_FEW_SYNTHETIC_SAMPLES,
        TOO_FEW_DOMAINS,
        MISSING_BRANCH_COVERAGE,
        MISSING_MATERIAL_ACTION_COVERAGE,
        MISSING_STATE_TRACE_COVERAGE
    }

    public record CoverageReport(CoverageStatus status, CoverageMatrix matrix, boolean directTemplate, RejectionReason rejectionReason) {
        public CoverageReport(CoverageStatus status, CoverageMatrix matrix, boolean directTemplate) {
            this(status, matrix, directTemplate, status == CoverageStatus.PASSED ? RejectionReason.NONE : RejectionReason.TOO_FEW_SYNTHETIC_SAMPLES);
        }

    }

    private static RejectionReason rejectionReason(SurfaceProgramIr ir, CoverageMatrix matrix, int domains) {
        if (ir.ops().size() < SurfaceCompilerConfig.SYNTHETIC_COVERAGE_SAMPLES) {
            return RejectionReason.TOO_FEW_SYNTHETIC_SAMPLES;
        }
        if (domains < SurfaceCompilerConfig.SYNTHETIC_COVERAGE_MIN_DOMAINS) {
            return RejectionReason.TOO_FEW_DOMAINS;
        }
        if (!matrix.hasBranchCoverage()) {
            return RejectionReason.MISSING_BRANCH_COVERAGE;
        }
        if (!matrix.hasMaterialActionCoverage()) {
            return RejectionReason.MISSING_MATERIAL_ACTION_COVERAGE;
        }
        if (!matrix.hasStateTraceCoverage()) {
            return RejectionReason.MISSING_STATE_TRACE_COVERAGE;
        }
        return RejectionReason.NONE;
    }

    public record CoverageMatrix(int domainCount, int branchNodes, int materialActions, int statefulOps, Set<SurfaceNode.Kind> nodeKinds) {
        static CoverageMatrix from(SurfaceProgramIr ir, Set<dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceDomain> domains) {
            if (ir == null) {
                return new CoverageMatrix(0, 0, 0, 0, Set.of());
            }
            EnumSet<SurfaceNode.Kind> kinds = EnumSet.noneOf(SurfaceNode.Kind.class);
            if (ir.root() != null) {
                for (SurfaceNode node : ir.root().flattenPreOrder()) {
                    kinds.add(node.kind());
                }
            }
            int materialActions = 0;
            int stateful = 0;
            for (SurfaceOp op : ir.ops()) {
                if ("STATE".equals(op.opcode())) {
                    materialActions++;
                }
                if (op.isStateful()) {
                    stateful++;
                }
            }
            int branchNodes = kinds.contains(SurfaceNode.Kind.SEQUENCE) || kinds.contains(SurfaceNode.Kind.TEST) ? 1 : 0;
            return new CoverageMatrix(domains == null ? 0 : domains.size(), branchNodes, materialActions, stateful, Set.copyOf(kinds));
        }

        public boolean hasBranchCoverage() {
            return this.branchNodes > 0;
        }

        public boolean hasMaterialActionCoverage() {
            return this.materialActions > 0;
        }

        public boolean hasStateTraceCoverage() {
            return this.statefulOps > 0;
        }
    }
}

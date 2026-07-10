package dev.sixik.generator_accelerator.common.surface_compiler.validate;

public record SurfaceCertification(
        SyntheticCoverageRunner.CoverageStatus coverageStatus,
        SyntheticCoverageRunner.CoverageMatrix coverageMatrix,
        boolean directTemplateCertified
) {
    public static SurfaceCertification from(SyntheticCoverageRunner.CoverageReport report) {
        if (report == null) {
            return new SurfaceCertification(SyntheticCoverageRunner.CoverageStatus.NOT_CERTIFIED,
                    new SyntheticCoverageRunner.CoverageMatrix(0, 0, 0, 0, java.util.Set.of()), false);
        }
        return new SurfaceCertification(report.status(), report.matrix(), report.directTemplate());
    }
}

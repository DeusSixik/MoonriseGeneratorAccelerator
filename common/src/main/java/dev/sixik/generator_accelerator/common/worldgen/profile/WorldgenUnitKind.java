package dev.sixik.generator_accelerator.common.worldgen.profile;

public enum WorldgenUnitKind {
    FEATURE("Feature.place"),
    PLACED_FEATURE("PlacedFeature.place"),
    PLACEMENT_MODIFIER("PlacementModifier.getPositions"),
    DENSITY_FUNCTION("DensityFunction.compute"),
    SURFACE_RULE("SurfaceRules.RuleSource.apply"),
    CARVER("WorldCarver.carve"),
    STRUCTURE("Structure.generate"),
    UNKNOWN("");

    private final String defaultEntryPoint;

    WorldgenUnitKind(String defaultEntryPoint) {
        this.defaultEntryPoint = defaultEntryPoint;
    }

    public String defaultEntryPoint() {
        return this.defaultEntryPoint;
    }
}

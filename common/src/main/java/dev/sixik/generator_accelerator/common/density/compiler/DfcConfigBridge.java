package dev.sixik.generator_accelerator.common.density.compiler;

import dev.sixik.generator_accelerator.api.config.GAConfig;
import dev.sixik.generator_accelerator.api.config.GAConfigHolder;

public final class DfcConfigBridge {

    private static volatile boolean applied;

    private DfcConfigBridge() {
    }

    public static void applySystemPropertiesFromConfig() {
        if (applied) {
            return;
        }
        synchronized (DfcConfigBridge.class) {
            if (applied) {
                return;
            }

            GAConfig.DfcDebugConfig config = GAConfigHolder.getConfig().dfc;
            setBoolean("dfc.codegen.splineRuntimeStats", config.splineRuntimeStats);
            setInt("dfc.codegen.splineRuntimeStats.sampleShift", config.splineRuntimeStatsSampleShift);
            setBoolean("dfc.codegen.splineRuntimeStats.emit", config.splineRuntimeStatsEmit);
            setBoolean("dfc.codegen.splineSegmentLut", config.splineSegmentLut);
            setInt("dfc.codegen.splineSegmentLutMinPoints", config.splineSegmentLutMinPoints);
            setInt("dfc.codegen.splineSegmentLutBuckets", config.splineSegmentLutBuckets);
            setString("dfc.codegen.splineSearchMode", config.splineSearchMode);
            setInt("dfc.codegen.splineLinearSearchMaxPoints", config.splineLinearSearchMaxPoints);
            setBoolean("dfc.cellfill.stats", config.cellFillStats);
            setBoolean("dfc.cellfill.stats.residualClassDebug", config.cellFillResidualClassDebug);
            setBoolean("dfc.cellfill.parity", config.cellFillParity);
            setInt("dfc.cellfill.parity.maxChecks", config.cellFillParityMaxChecks);
            setDouble("dfc.cellfill.parity.epsilon", config.cellFillParityEpsilon);
            setBoolean("ga.dfc.cacheFastPath.stats", config.cacheFastPathStats);
            setBoolean("dfc.codegen.logSplineSearch", config.logSplineSearch);
            setInt("dfc.codegen.latticeMinHoistSize", config.latticeMinHoistSize);
            setBoolean("dfc.compileMarkerInners", config.compileMarkerInners);
            setBoolean("dfc.codegen.cellFillDirectExternResidual", config.cellFillDirectExternResidual);
            setBoolean("dfc.codegen.cellFillAddExternOverride", config.cellFillAddExternOverride);
            setBoolean("dfc.warmer.rawDensityFunctions", config.warmerRawDensityFunctions);
            setBudget("dfc.warmer.maxSettings", config.warmerMaxSettings);
            setBudget("dfc.warmer.maxDensityFunctions", config.warmerMaxDensityFunctions);

            applied = true;
        }
    }

    private static void setBoolean(String key, boolean value) {
        System.setProperty(key, Boolean.toString(value));
    }

    private static void setInt(String key, int value) {
        System.setProperty(key, Integer.toString(value));
    }

    private static void setDouble(String key, double value) {
        System.setProperty(key, Double.toString(value));
    }

    private static void setString(String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        System.setProperty(key, value.trim());
    }

    private static void setBudget(String key, int value) {
        if (value < 0) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, Integer.toString(value));
    }
}

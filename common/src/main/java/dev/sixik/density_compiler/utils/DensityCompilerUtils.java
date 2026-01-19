package dev.sixik.density_compiler.utils;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public class DensityCompilerUtils {

    public static boolean isConst(DensityFunction f) {
        return f instanceof DensityFunctions.Constant || f instanceof DensityFunctions.BlendAlpha || f instanceof DensityFunctions.BlendOffset || f instanceof DensityFunctions.BeardifierMarker;
    }

    public static boolean isConst(DensityFunction f, double val) {
        if(f instanceof DensityFunctions.Constant c && c.value() == val)
            return true;

        if(f instanceof DensityFunctions.BlendAlpha c && c.minValue() == val)
            return true;

        if(f instanceof DensityFunctions.BeardifierMarker marker && marker.maxValue() == val)
            return true;

        return f instanceof DensityFunctions.BlendOffset c && c.minValue() == val;
    }

    public static double getConst(DensityFunction f) {

        if(f instanceof DensityFunctions.Constant constant)
            return constant.value();

        if(f instanceof DensityFunctions.BlendOffset blendAlpha)
            return blendAlpha.minValue();

        if(f instanceof DensityFunctions.BlendAlpha blendAlpha)
            return blendAlpha.minValue();

        if(f instanceof DensityFunctions.BeardifierMarker marker)
            return marker.maxValue();

        throw new NullPointerException("Can't get constant from " + f.getClass().getName() + " !");
    }
}

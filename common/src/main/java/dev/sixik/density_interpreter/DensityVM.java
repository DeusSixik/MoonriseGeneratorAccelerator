package dev.sixik.density_interpreter;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public class DensityVM {

    private static boolean loaded = false;

    public static boolean IsLoaded() {
        return loaded;
    }

    public synchronized static void Initialize() {
        if(loaded) return;

        try {
            // Создаём одну временную папку под все DLL
            Path tempDir = Files.createTempDirectory("density_native_");
            tempDir.toFile().deleteOnExit();

            // Список библиотек, как ты их назовёшь / как дают Noesis
            String[] libs = {
                    "libDensityOperation.so",
            };

            for (String lib : libs) {
                extractLib("/native/linux/" + lib, tempDir.resolve(lib));
            }

            // ВАЖНО: сначала грузим сами Noesis DLL,
            // потом – наш noesis_jni.dll
            System.load(tempDir.resolve("libDensityOperation.so").toString());
            loaded = true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Noesis native libs", e);
        }
    }

    private static void extractLib(String resourcePath, Path dst) throws Exception {
        try (InputStream in = DensityVM.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Native resource not found: " + resourcePath);
            }
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            dst.toFile().deleteOnExit();
        }
    }



    public static native double noise(long ptr, double d, double e, double f, double g, double h);

    public static native long createNativeImprovedNoise(double xo, double yo, double zo, byte[] p);

    public static long createPerlinNoise(PerlinNoise noise) {
        final double[] amplitudes = noise.amplitudes.toDoubleArray();

        int activeCount = 0;
        for (ImprovedNoise oct : noise.noiseLevels) {
            if (oct != null) activeCount++;
        }

        final double[] xo = new double[activeCount];
        final double[] yo = new double[activeCount];
        final double[] zo = new double[activeCount];
        final byte[] pFlat = new byte[activeCount * 256];

        int idx = 0;
        for (ImprovedNoise element : noise.noiseLevels) {
            if (element == null) continue;

            xo[idx] = element.xo;
            yo[idx] = element.yo;
            zo[idx] = element.zo;

            System.arraycopy(element.p, 0, pFlat, idx * 256, 256);
            idx++;
        }

        return createPerlinNoise(
                noise.firstOctave,
                noise.lowestFreqValueFactor,
                noise.lowestFreqInputFactor,
                noise.maxValue,
                amplitudes, xo, yo, zo, pFlat
        );
    }

    private static native long createPerlinNoise(
            int firstOctave, double lowestFreqValueFactor, double lowestFreqInputFactor, double maxValue,
            double[] amplitudes, double[] noiseLevels_xo, double[] noiseLevels_yo, double[] noiseLevels_zo, byte[] noiseLevels_p);

    public static long createVMContext(DensityCompiler.Data data) {
        final int[] commands = new int[data.program.size()];
        for (int i = 0; i < commands.length; i++) {
            commands[i] = data.program.get(i);
        }

        final double[] constants = new double[data.constants.size()];
        for (int i = 0; i < constants.length; i++) {
            constants[i] = data.constants.get(i);
        }

        return createVMContext(commands, constants);
    }

    private static native long createVMContext(int[] commands, double[] constants);

    public static native void deleteVMContext(long ptr);

    public static native double densityInvoke(long vmPtr);

    static {
        Initialize();
    }
}

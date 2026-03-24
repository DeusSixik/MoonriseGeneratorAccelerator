package dev.sixik.generator_accelerator;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class GeneratorAcceleratorNatives {

    private static String[] NATIVES_NAME = {
        "c3_minecraft.dll"
    };

    private static boolean loaded = false;

    public static boolean isLoaded() {
        return loaded;
    }

    static void initialize() {
        if(loaded) return;

        try {
            Path tempDir = Files.createTempDirectory("generator_accelerator_native_");
            tempDir.toFile().deleteOnExit();

            for (String lib : NATIVES_NAME) {
                extractLib("/natives/" + lib, tempDir.resolve(lib));
            }

            for (String s : NATIVES_NAME) {
                System.load(tempDir.resolve(s).toString());
            }
            loaded = true;

            System.out.println("Loaded NATIVE DLL");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load GeneratorAccelerator(C3) native libs", e);
        }
    }

    private static void extractLib(String resourcePath, Path dst) throws Exception {
        try (InputStream in = GeneratorAcceleratorNatives.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Native resource not found: " + resourcePath);
            }
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            dst.toFile().deleteOnExit();
        }
    }
}

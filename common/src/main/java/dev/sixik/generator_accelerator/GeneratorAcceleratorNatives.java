package dev.sixik.generator_accelerator;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class GeneratorAcceleratorNatives {

    private static final String LIB_NAME = "c3_minecraft";
    private static boolean loaded = false;

    public static boolean isLoaded() {
        return loaded;
    }

    public static void initialize() {
        if (loaded) return;

        try {
            String os = System.getProperty("os.name").toLowerCase();
            String fileName;

            if (os.contains("win")) {
                fileName = LIB_NAME + ".dll";
            } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                fileName = "lib" + LIB_NAME + ".so";
            } else {
                throw new RuntimeException("Unsupported OS: " + os);
            }

            Path tempDir = Files.createTempDirectory("generator_accelerator_");
            tempDir.toFile().deleteOnExit();
            Path tempFile = tempDir.resolve(fileName);

            String resourcePath = "/natives/" + fileName;

            try (InputStream in = GeneratorAcceleratorNatives.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new RuntimeException("Native resource not found: " + resourcePath);
                }
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            tempFile.toFile().setExecutable(true);
            tempFile.toFile().deleteOnExit();

            System.load(tempFile.toAbsolutePath().toString());
            loaded = true;

            GeneratorAccelerator.LOGGER.info("[C3 Native] Successfully loaded: {}", fileName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load GeneratorAccelerator(C3) native libs", e);
        }
    }
}

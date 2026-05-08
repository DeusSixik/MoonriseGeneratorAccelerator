package dev.sixik.generator_accelerator.common.density.compiler.natives;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;

/**
 * Loads {@code dfc_native} from (in order):
 * <ol>
 *   <li>Absolute path in env {@code DFC_NATIVE_LIBRARY} (dev / broken classpath).</li>
 *   <li>Resource {@code natives/&lt;platform&gt;/&lt;lib&gt;} from several class loaders.</li>
 * </ol>
 * Safe to call from multiple threads; extraction happens once.
 */
public final class NativeLibraryLoader {

    private static final String ENV_OVERRIDE = "DFC_NATIVE_LIBRARY";

    private static volatile boolean loaded;
    private static volatile Throwable loadError;

    private NativeLibraryLoader() {}

    public static boolean isLoaded() {
        return loaded;
    }

    public static Throwable loadError() {
        return loadError;
    }

    /**
     * Loads the shared library. Returns {@code true} on success.
     */
    public static synchronized boolean loadBundled() {
        if (loaded) {
            return true;
        }
        if (loadError != null) {
            return false;
        }
        try {
            String override = System.getenv(ENV_OVERRIDE);
            if (override != null) {
                String p = override.trim();
                if (!p.isEmpty()) {
                    Path abs = Path.of(p);
                    if (!Files.isRegularFile(abs)) {
                        loadError = new UnsatisfiedLinkError(
                                ENV_OVERRIDE + " is not a regular file: " + abs.toAbsolutePath());
                        return false;
                    }
                    System.load(abs.toAbsolutePath().toString());
                    loaded = true;
                    return true;
                }
            }

            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            String platform;
            String libFile;
            if (os.contains("windows")) {
                platform = "windows_amd64";
                libFile = "dfc_native.dll";
            } else if (os.contains("mac")) {
                boolean arm = arch.contains("aarch64") || arch.contains("arm64");
                platform = arm ? "macos_aarch64" : "macos_x64";
                libFile = "libdfc_native.dylib";
            } else {
                platform = "linux_x64";
                libFile = "libdfc_native.so";
            }
            String noSlash = "natives/" + platform + "/" + libFile;
            String withSlash = "/" + noSlash;

            try (InputStream in = openBundledStream(withSlash, noSlash)) {
                if (in == null) {
                    loadError = new UnsatisfiedLinkError(
                            "Native library not bundled: " + withSlash
                                    + " (set " + ENV_OVERRIDE + " to an absolute path, run Gradle"
                                    + " with the default native build enabled, or place natives/dfc/prebuilts/"
                                    + platform + "/" + libFile + ")");
                    return false;
                }
                Path tmp = Files.createTempFile("dfc_native_", "_" + libFile);
                tmp.toFile().deleteOnExit();
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                System.load(tmp.toAbsolutePath().toString());
            }
            loaded = true;
            return true;
        } catch (IOException | UnsatisfiedLinkError e) {
            loadError = Objects.requireNonNullElse(e, new RuntimeException("unknown"));
            return false;
        }
    }

    private static InputStream openBundledStream(String withSlash, String noSlash) {
        InputStream in = NativeLibraryLoader.class.getResourceAsStream(withSlash);
        if (in != null) {
            return in;
        }
        ClassLoader own = NativeLibraryLoader.class.getClassLoader();
        if (own != null) {
            in = own.getResourceAsStream(noSlash);
            if (in != null) {
                return in;
            }
        }
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null && ctx != own) {
            in = ctx.getResourceAsStream(noSlash);
            if (in != null) {
                return in;
            }
        }
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        if (sys != null && sys != own && sys != ctx) {
            in = sys.getResourceAsStream(noSlash);
            if (in != null) {
                return in;
            }
        }
        return null;
    }
}

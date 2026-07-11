package dev.sixik.generator_accelerator.api.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinPackageIsolationTest {
    private static final Pattern CONFIG_PACKAGE = Pattern.compile(
            "\\\"package\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
    );
    private static final Pattern SOURCE_PACKAGE = Pattern.compile(
            "(?m)^package\\s+([\\w.]+);"
    );

    @Test
    void declaredMixinPackagesContainOnlyMixinSources() throws IOException {
        Path root = findRepositoryRoot();
        List<String> offenders = new ArrayList<>();

        for (String module : List.of("common", "fabric", "neoforge")) {
            Path resources = root.resolve(module).resolve("src/main/resources");
            Path sources = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(resources) || !Files.isDirectory(sources)) {
                continue;
            }

            try (var configs = Files.list(resources)) {
                for (Path config : configs.filter(path -> path.getFileName().toString().endsWith(".mixins.json")).toList()) {
                    Matcher configPackage = CONFIG_PACKAGE.matcher(Files.readString(config));
                    if (!configPackage.find()) {
                        continue;
                    }
                    String mixinPackage = configPackage.group(1);
                    if (!mixinPackage.startsWith("dev.sixik.generator_accelerator")) {
                        continue;
                    }

                    Path packageDirectory = sources.resolve(mixinPackage.replace('.', '/'));
                    if (!Files.isDirectory(packageDirectory)) {
                        continue;
                    }
                    try (var javaSources = Files.walk(packageDirectory)) {
                        for (Path source : javaSources.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                            String text = Files.readString(source);
                            Matcher sourcePackage = SOURCE_PACKAGE.matcher(text);
                            if (!sourcePackage.find()) {
                                continue;
                            }
                            String declaredPackage = sourcePackage.group(1);
                            if ((declaredPackage.equals(mixinPackage) || declaredPackage.startsWith(mixinPackage + '.'))
                                    && !text.contains("@Mixin")) {
                                offenders.add(root.relativize(source).toString());
                            }
                        }
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(), () -> "Non-mixin classes in declared mixin packages: " + offenders);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("common/src/main/resources"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root");
    }
}

package dev.sixik.generator_accelerator.common.surface_compiler.frontend;

import dev.sixik.generator_accelerator.common.surface_compiler.cache.FingerprintCacheKey;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SurfaceFingerprint {
    private static final String GA_VERSION = "surface-compiler-2.0-clean";
    private static final int MAX_DEPTH = 64;
    private static final Set<String> RECORD_INFRASTRUCTURE_METHODS = Set.of("codec", "rule", "toString", "hashCode");

    private SurfaceFingerprint() {
    }

    public static FingerprintCacheKey keyFor(SurfaceRules.RuleSource source) {
        String mcVersion = SharedConstants.getCurrentVersion() == null
                ? "unknown"
                : SharedConstants.getCurrentVersion().getName();
        return new FingerprintCacheKey(
                structuralHash(source),
                mcVersion,
                GA_VERSION,
                0L,
                adapterRegistryHash(),
                SurfaceRuntime.RUNTIME_BINDING_VERSION,
                "conservative",
                safetyMode()
        );
    }

    public static String structuralHash(Object source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            appendObject(digest, source, new IdentityHashMap<>(), 0);
            byte[] bytes = digest.digest();
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                out.append(Character.forDigit((value >>> 4) & 0xF, 16));
                out.append(Character.forDigit(value & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void appendObject(MessageDigest digest, Object value, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (value == null) {
            append(digest, "null;");
            return;
        }
        if (depth > MAX_DEPTH) {
            append(digest, "depth-limit;");
            return;
        }
        if (value instanceof BlockState blockState) {
            append(digest, "BlockState=");
            append(digest, String.valueOf(Block.getId(blockState)));
            append(digest, ":");
            append(digest, blockState.toString());
            append(digest, ";");
            return;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof Number || value instanceof Boolean || value instanceof CharSequence || value instanceof Enum<?>) {
            append(digest, type.getName());
            append(digest, "=");
            append(digest, String.valueOf(value));
            append(digest, ";");
            return;
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            append(digest, "cycle:");
            append(digest, type.getName());
            append(digest, ";");
            return;
        }
        append(digest, "class:");
        append(digest, type.getName());
        append(digest, "{");
        if (type.isArray()) {
            int length = Array.getLength(value);
            append(digest, "len=" + length + ";");
            for (int i = 0; i < length; i++) {
                appendObject(digest, Array.get(value, i), seen, depth + 1);
            }
        } else if (value instanceof Collection<?> collection) {
            append(digest, "collection=" + collection.size() + ";");
            for (Object child : collection) {
                appendObject(digest, child, seen, depth + 1);
            }
        } else if (value instanceof Map<?, ?> map) {
            append(digest, "map=" + map.size() + ";");
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        appendObject(digest, entry.getKey(), seen, depth + 1);
                        appendObject(digest, entry.getValue(), seen, depth + 1);
                    });
        } else {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() == 0
                        && !method.getReturnType().equals(Void.TYPE)
                        && method.getDeclaringClass() != Object.class
                        && !method.isSynthetic()
                        && !RECORD_INFRASTRUCTURE_METHODS.contains(method.getName())) {
                    try {
                        method.setAccessible(true);
                        append(digest, method.getName());
                        append(digest, "=");
                        appendObject(digest, method.invoke(value), seen, depth + 1);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        append(digest, method.getName());
                        append(digest, "=<unreadable>;");
                    }
                }
            }
        }
        append(digest, "}");
        seen.remove(value);
    }

    private static void append(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String safetyMode() {
        if (Boolean.getBoolean("ga.surface.compiler.debug")) {
            return "debug";
        }
        if (Boolean.getBoolean("ga.surface.compiler.paranoid")) {
            return "paranoid";
        }
        return "production".toLowerCase(Locale.ROOT);
    }

    private static String adapterRegistryHash() {
        return "adapters=" + SurfaceRuntime.adapters().versionHash()
                + "|unsafe=" + SurfaceRuntime.unsafeRules().versionHash();
    }
}

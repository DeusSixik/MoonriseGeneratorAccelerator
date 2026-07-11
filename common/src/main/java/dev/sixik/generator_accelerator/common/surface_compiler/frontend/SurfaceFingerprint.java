package dev.sixik.generator_accelerator.common.surface_compiler.frontend;

import dev.sixik.generator_accelerator.common.surface_compiler.cache.FingerprintCacheKey;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SurfaceFingerprint {
    private static final String GA_VERSION = "surface-compiler-2.0-bounded-fingerprint";
    private static final int MAX_DEPTH = 32;
    private static final int MAX_OBJECTS = 4_096;
    private static final int MAX_EDGES = 16_384;
    private static final int MAX_SORTED_MAP_ENTRIES = 2_048;
    private static final ClassValue<AccessorSet> ACCESSORS = new ClassValue<>() {
        @Override
        protected AccessorSet computeValue(Class<?> type) {
            return discoverAccessors(type);
        }
    };

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
            appendObject(digest, source, new TraversalState(), 0);
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

    private static void appendObject(MessageDigest digest, Object value, TraversalState state, int depth) {
        if (value == null) {
            append(digest, "null;");
            return;
        }
        if (depth > MAX_DEPTH) {
            appendIdentityScoped(digest, "depth-limit", value);
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
        if (type.isPrimitive()
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof CharSequence
                || value instanceof Enum<?>) {
            append(digest, type.getName());
            append(digest, "=");
            append(digest, String.valueOf(value));
            append(digest, ";");
            return;
        }
        if (value instanceof Class<?> classValue) {
            append(digest, "Class=");
            append(digest, classValue.getName());
            append(digest, ";");
            return;
        }
        if (value instanceof ResourceLocation location) {
            append(digest, "ResourceLocation=");
            append(digest, location.toString());
            append(digest, ";");
            return;
        }
        if (value instanceof ResourceKey<?> key) {
            append(digest, "ResourceKey=");
            append(digest, key.registry().toString());
            append(digest, "/");
            append(digest, key.location().toString());
            append(digest, ";");
            return;
        }
        if (value instanceof TagKey<?> tag) {
            append(digest, "TagKey=");
            append(digest, tag.registry().toString());
            append(digest, "/");
            append(digest, tag.location().toString());
            append(digest, ";");
            return;
        }
        if (value instanceof Holder<?> holder) {
            Optional<? extends ResourceKey<?>> key = holder.unwrapKey();
            if (key.isPresent()) {
                append(digest, "HolderKey=");
                appendObject(digest, key.get(), state, depth + 1);
            } else {
                appendIdentityScoped(digest, "direct-holder", value);
            }
            return;
        }

        Integer previousId = state.seen.get(value);
        if (previousId != null) {
            append(digest, "ref:");
            append(digest, type.getName());
            append(digest, "#");
            append(digest, String.valueOf(previousId));
            append(digest, ";");
            return;
        }
        if (!state.enter(value)) {
            appendIdentityScoped(digest, "object-limit", value);
            return;
        }

        append(digest, "class:");
        append(digest, type.getName());
        append(digest, "{");
        if (type.isArray()) {
            int length = Array.getLength(value);
            append(digest, "len=" + length + ";");
            for (int i = 0; i < length; i++) {
                if (!appendChild(digest, Array.get(value, i), state, depth)) {
                    break;
                }
            }
        } else if (value instanceof Collection<?> collection) {
            append(digest, "collection=" + collection.size() + ";");
            for (Object child : collection) {
                if (!appendChild(digest, child, state, depth)) {
                    break;
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            append(digest, "map=" + map.size() + ";");
            List<Map.Entry<?, ?>> entries = new ArrayList<>(Math.min(map.size(), MAX_SORTED_MAP_ENTRIES));
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entries.size() >= MAX_SORTED_MAP_ENTRIES) {
                    appendIdentityScoped(digest, "map-entry-limit", value);
                    break;
                }
                entries.add(entry);
            }
            entries.sort(Comparator.comparing(entry -> scalarSortKey(entry.getKey())));
            for (Map.Entry<?, ?> entry : entries) {
                if (!appendChild(digest, entry.getKey(), state, depth)
                        || !appendChild(digest, entry.getValue(), state, depth)) {
                    break;
                }
            }
        } else if (value instanceof Optional<?> optional) {
            append(digest, optional.isPresent() ? "present;" : "empty;");
            if (optional.isPresent()) {
                appendChild(digest, optional.get(), state, depth);
            }
        } else {
            AccessorSet accessorSet = ACCESSORS.get(type);
            if (accessorSet.accessors().isEmpty()) {
                appendIdentityScoped(digest, "opaque", value);
            }
            for (ValueAccessor accessor : accessorSet.accessors()) {
                append(digest, accessor.name());
                append(digest, "=");
                try {
                    if (!appendChild(digest, accessor.read(value), state, depth)) {
                        break;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    appendIdentityScoped(digest, "unreadable:" + accessor.name(), value);
                }
            }
            if (!accessorSet.unreadable().isEmpty()) {
                append(digest, "unreadable-members=");
                append(digest, String.join(",", accessorSet.unreadable()));
                append(digest, ";");
                appendIdentityScoped(digest, "partial", value);
            }
        }
        append(digest, "}");
    }

    private static boolean appendChild(MessageDigest digest, Object child, TraversalState state, int parentDepth) {
        if (!state.consumeEdge()) {
            append(digest, "edge-limit;");
            if (child != null) {
                appendIdentityScoped(digest, "truncated", child);
            }
            return false;
        }
        appendObject(digest, child, state, parentDepth + 1);
        return true;
    }

    private static AccessorSet discoverAccessors(Class<?> type) {
        if (isJdkImplementation(type) || !type.isRecord()) {
            return AccessorSet.EMPTY;
        }

        List<ValueAccessor> accessors = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        try {
            Field[] fields = type.getDeclaredFields();
            Arrays.sort(fields, Comparator.comparing(Field::getName));
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                if (makeAccessible(field)) {
                    accessors.add(new FieldAccessor(field.getName(), field));
                } else {
                    unreadable.add(field.getName());
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            return new AccessorSet(List.of(), List.of("<discovery-failed>"));
        }
        return new AccessorSet(List.copyOf(accessors), List.copyOf(unreadable));
    }

    private static boolean makeAccessible(java.lang.reflect.AccessibleObject accessibleObject) {
        try {
            return accessibleObject.trySetAccessible();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isJdkImplementation(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.");
    }

    private static String scalarSortKey(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof CharSequence
                || value instanceof Enum<?>
                || value instanceof ResourceLocation
                || value instanceof ResourceKey<?>
                || value instanceof TagKey<?>) {
            return value.getClass().getName() + ":" + value;
        }
        return value.getClass().getName() + ":" + System.identityHashCode(value);
    }

    private static void appendIdentityScoped(MessageDigest digest, String reason, Object value) {
        append(digest, reason);
        append(digest, ":");
        append(digest, value.getClass().getName());
        append(digest, "@");
        append(digest, Integer.toUnsignedString(System.identityHashCode(value)));
        append(digest, ";");
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

    private interface ValueAccessor {
        String name();

        Object read(Object target) throws ReflectiveOperationException;
    }

    private record FieldAccessor(String name, Field field) implements ValueAccessor {
        @Override
        public Object read(Object target) throws ReflectiveOperationException {
            return this.field.get(target);
        }
    }

    private record AccessorSet(List<ValueAccessor> accessors, List<String> unreadable) {
        private static final AccessorSet EMPTY = new AccessorSet(List.of(), List.of());
    }

    private static final class TraversalState {
        private final IdentityHashMap<Object, Integer> seen = new IdentityHashMap<>();
        private int objects;
        private int edges;

        private boolean enter(Object value) {
            if (this.objects >= MAX_OBJECTS) {
                return false;
            }
            this.seen.put(value, this.objects++);
            return true;
        }

        private boolean consumeEdge() {
            return this.edges++ < MAX_EDGES;
        }
    }
}

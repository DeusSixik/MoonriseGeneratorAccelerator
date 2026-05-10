package dev.sixik.generator_accelerator.diagnostics;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.Map;

final class DiagnosticsJson {
    private static final int MAX_DEPTH = 32;

    private DiagnosticsJson() {
    }

    static String toJson(Object value) {
        StringBuilder builder = new StringBuilder(64 * 1024);
        writeValue(builder, value, 0);
        builder.append('\n');
        return builder.toString();
    }

    private static void writeValue(StringBuilder builder, Object value, int depth) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (depth > MAX_DEPTH) {
            writeString(builder, "<max-depth>");
            return;
        }
        if (value instanceof String string) {
            writeString(builder, string);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
            return;
        }
        if (value instanceof Enum<?> enumValue) {
            writeString(builder, enumValue.name());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writeMap(builder, map, depth + 1);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            writeIterable(builder, iterable, depth + 1);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            writeArray(builder, value, depth + 1);
            return;
        }
        if (type.isRecord()) {
            writeRecord(builder, value, depth + 1);
            return;
        }
        writeString(builder, value.toString());
    }

    private static void writeMap(StringBuilder builder, Map<?, ?> map, int depth) {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeString(builder, String.valueOf(entry.getKey()));
            builder.append(':');
            writeValue(builder, entry.getValue(), depth);
        }
        builder.append('}');
    }

    private static void writeIterable(StringBuilder builder, Iterable<?> iterable, int depth) {
        builder.append('[');
        Iterator<?> iterator = iterable.iterator();
        boolean first = true;
        while (iterator.hasNext()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeValue(builder, iterator.next(), depth);
        }
        builder.append(']');
    }

    private static void writeArray(StringBuilder builder, Object array, int depth) {
        builder.append('[');
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            writeValue(builder, Array.get(array, i), depth);
        }
        builder.append(']');
    }

    private static void writeRecord(StringBuilder builder, Object value, int depth) {
        builder.append('{');
        RecordComponent[] components = value.getClass().getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            RecordComponent component = components[i];
            writeString(builder, component.getName());
            builder.append(':');
            try {
                writeValue(builder, component.getAccessor().invoke(value), depth);
            } catch (ReflectiveOperationException e) {
                writeString(builder, "<reflection-error:" + e.getClass().getSimpleName() + ">");
            }
        }
        builder.append('}');
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int j = hex.length(); j < 4; j++) {
                            builder.append('0');
                        }
                        builder.append(hex);
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }
}

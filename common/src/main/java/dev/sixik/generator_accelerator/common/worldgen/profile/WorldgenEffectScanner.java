package dev.sixik.generator_accelerator.common.worldgen.profile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lightweight class-file scanner used when ASM is not available. */
public final class WorldgenEffectScanner {
    public static final int DEFAULT_MAX_BYTES = 256 * 1024;
    public static final int DEFAULT_MAX_CONSTANTS = 4096;
    public static final int DEFAULT_MAX_METHODS = 512;

    private final int maxBytes;
    private final int maxConstants;
    private final int maxMethods;

    public WorldgenEffectScanner() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_CONSTANTS, DEFAULT_MAX_METHODS);
    }

    public WorldgenEffectScanner(int maxBytes, int maxConstants, int maxMethods) {
        this.maxBytes = Math.max(1024, maxBytes);
        this.maxConstants = Math.max(16, maxConstants);
        this.maxMethods = Math.max(1, maxMethods);
    }

    public WorldgenEffectProfile scan(Class<?> unitClass, String methodHint) {
        if (unitClass == null) {
            return failClosed("", methodHint, "null class");
        }
        String className = unitClass.getName();
        try {
            byte[] classBytes = readClassBytes(unitClass);
            if (classBytes == null || classBytes.length == 0) {
                return failClosed(className, methodHint, "class resource unreadable");
            }
            boolean budgetExceeded = classBytes.length > this.maxBytes;
            if (budgetExceeded) {
                return failClosed(className, methodHint, fingerprint(classBytes), true, "classfile byte budget exceeded");
            }
            return scanBytes(className, methodHint, classBytes);
        } catch (RuntimeException failure) {
            return failClosed(className, methodHint, "effect scan failed");
        }
    }

    private WorldgenEffectProfile scanBytes(String className, String methodHint, byte[] bytes) {
        Cursor in = new Cursor(bytes);
        EnumSet<WorldgenEffectFlag> flags = EnumSet.noneOf(WorldgenEffectFlag.class);
        ArrayList<String> reasons = new ArrayList<>();
        try {
            if (in.u4() != 0xCAFEBABEL) {
                return failClosed(className, methodHint, fingerprint(bytes), false, "invalid classfile magic");
            }
            in.skip(4); // minor + major version
            int constantPoolCount = in.u2();
            boolean budgetExceeded = constantPoolCount > this.maxConstants;
            if (budgetExceeded) {
                return failClosed(className, methodHint, fingerprint(bytes), true, "constant-pool budget exceeded");
            }

            String[] utf8 = new String[constantPoolCount];
            int[] classNameIndexes = new int[constantPoolCount];
            for (int i = 1; i < constantPoolCount; i++) {
                int tag = in.u1();
                switch (tag) {
                    case 1 -> utf8[i] = in.utf8();
                    case 3, 4 -> in.skip(4);
                    case 5, 6 -> {
                        in.skip(8);
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> classNameIndexes[i] = in.u2();
                    case 9, 10, 11, 12, 18 -> in.skip(4);
                    case 15 -> in.skip(3);
                    default -> {
                        return failClosed(className, methodHint, fingerprint(bytes), false, "unsupported classfile constant");
                    }
                }
            }

            scanConstants(utf8, classNameIndexes, flags, reasons);
            in.skip(6); // access, this_class, super_class
            skipInterfaces(in);
            skipMembers(in); // fields
            int methodCount = in.u2();
            if (methodCount > this.maxMethods) {
                budgetExceeded = true;
                add(flags, reasons, WorldgenEffectFlag.CALLS_UNKNOWN_METHOD, "method budget exceeded");
            }
            int scannedMethods = Math.min(methodCount, this.maxMethods);
            for (int i = 0; i < methodCount; i++) {
                scanMethod(in, utf8, i < this.maxMethods, flags, reasons);
            }
            return new WorldgenEffectProfile(
                    className,
                    methodHint,
                    fingerprint(bytes),
                    flags,
                    reasons,
                    true,
                    budgetExceeded,
                    Math.min(constantPoolCount - 1, this.maxConstants),
                    scannedMethods
            );
        } catch (RuntimeException failure) {
            return failClosed(className, methodHint, fingerprint(bytes), false, "classfile parse failed");
        }
    }

    private void scanConstants(String[] utf8, int[] classNameIndexes, EnumSet<WorldgenEffectFlag> flags, List<String> reasons) {
        Set<String> seenReasons = new HashSet<>();
        for (String value : utf8) {
            scanText(value, flags, reasons, seenReasons);
        }
        for (int index : classNameIndexes) {
            if (index > 0 && index < utf8.length) {
                scanText(utf8[index], flags, reasons, seenReasons);
            }
        }
    }

    private void scanText(String value, EnumSet<WorldgenEffectFlag> flags, List<String> reasons, Set<String> seenReasons) {
        if (value == null || value.isBlank()) {
            return;
        }
        String text = value.toLowerCase(Locale.ROOT);
        if (text.contains("java/lang/reflect") || text.contains("methodhandles") || text.contains("classloader")) {
            addOnce(flags, reasons, seenReasons, WorldgenEffectFlag.USES_REFLECTION, "reflection constant");
        }
        if (text.contains("sun/misc/unsafe") || text.contains("jdk/internal/misc/unsafe") || text.contains("native")) {
            addOnce(flags, reasons, seenReasons, WorldgenEffectFlag.USES_NATIVE, "native/unsafe constant");
        }
        if (text.contains("java/io/") || text.contains("java/nio/file") || text.contains("filesystem")) {
            addOnce(flags, reasons, seenReasons, WorldgenEffectFlag.USES_IO, "io constant");
        }
        if (text.contains("java/lang/thread") || text.contains("executor") || text.contains("forkjoin") || text.contains("completablefuture")) {
            addOnce(flags, reasons, seenReasons, WorldgenEffectFlag.USES_THREADS, "threading constant");
        }
        if (text.contains("net/minecraft/server") || text.contains("global") || text.contains("staticmutable")) {
            addOnce(flags, reasons, seenReasons, WorldgenEffectFlag.USES_GLOBAL_MUTABLE_STATE, "global mutable constant");
        }
        if (text.contains("heightmap")) {
            flags.add(WorldgenEffectFlag.READS_HEIGHTMAP);
        }
        if (text.contains("biome")) {
            flags.add(WorldgenEffectFlag.READS_BIOMES);
        }
        if (text.contains("random")) {
            flags.add(WorldgenEffectFlag.USES_RANDOM);
        }
    }

    private void scanMethod(Cursor in, String[] utf8, boolean withinBudget, EnumSet<WorldgenEffectFlag> flags, List<String> reasons) {
        int access = in.u2();
        in.skip(4); // name_index + descriptor_index
        if ((access & 0x0100) != 0) {
            add(flags, reasons, WorldgenEffectFlag.USES_NATIVE, "native method");
        }
        if ((access & 0x0020) != 0) {
            add(flags, reasons, WorldgenEffectFlag.USES_SYNCHRONIZED, "synchronized method");
        }
        int attributes = in.u2();
        for (int i = 0; i < attributes; i++) {
            int nameIndex = in.u2();
            long length = in.u4();
            String name = nameIndex > 0 && nameIndex < utf8.length ? utf8[nameIndex] : "";
            if (withinBudget && "Code".equals(name)) {
                scanCode(in, length, flags, reasons);
            } else {
                in.skip(length);
            }
        }
    }

    private void scanCode(Cursor in, long attributeLength, EnumSet<WorldgenEffectFlag> flags, List<String> reasons) {
        int start = in.position();
        in.skip(4); // max_stack + max_locals
        int codeLength = (int) in.u4();
        int end = in.position() + codeLength;
        while (in.position() < end) {
            int opcode = in.u1();
            if (opcode == 0xC2 || opcode == 0xC3) {
                add(flags, reasons, WorldgenEffectFlag.USES_SYNCHRONIZED, "monitor instruction");
            }
        }
        int consumed = in.position() - start;
        in.skip(attributeLength - consumed);
    }

    private static void skipInterfaces(Cursor in) {
        int interfaces = in.u2();
        in.skip((long) interfaces * 2L);
    }

    private static void skipMembers(Cursor in) {
        int fields = in.u2();
        for (int i = 0; i < fields; i++) {
            in.skip(6);
            int attributes = in.u2();
            for (int a = 0; a < attributes; a++) {
                in.skip(2);
                in.skip(in.u4());
            }
        }
    }

    private byte[] readClassBytes(Class<?> unitClass) {
        String resourceName = unitClass.getName().replace('.', '/') + ".class";
        ClassLoader loader = unitClass.getClassLoader();
        try (InputStream in = loader == null
                ? ClassLoader.getSystemResourceAsStream(resourceName)
                : loader.getResourceAsStream(resourceName)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int remaining = this.maxBytes + 1;
            while (remaining > 0 && (read = in.read(buffer, 0, Math.min(buffer.length, remaining))) >= 0) {
                out.write(buffer, 0, read);
                remaining -= read;
            }
            return out.toByteArray();
        } catch (IOException failure) {
            return null;
        }
    }

    private static void add(EnumSet<WorldgenEffectFlag> flags, List<String> reasons, WorldgenEffectFlag flag, String reason) {
        flags.add(flag);
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    private static void addOnce(EnumSet<WorldgenEffectFlag> flags, List<String> reasons, Set<String> seenReasons, WorldgenEffectFlag flag, String reason) {
        flags.add(flag);
        if (seenReasons.add(reason)) {
            reasons.add(reason);
        }
    }

    private static WorldgenEffectProfile failClosed(String className, String methodHint, String reason) {
        return failClosed(className, methodHint, "", false, reason);
    }

    private static WorldgenEffectProfile failClosed(String className, String methodHint, String fingerprint, boolean budgetExceeded, String reason) {
        return new WorldgenEffectProfile(
                className,
                methodHint,
                fingerprint,
                EnumSet.of(WorldgenEffectFlag.CALLS_UNKNOWN_METHOD),
                List.of(reason),
                false,
                budgetExceeded,
                0,
                0
        );
    }

    private static String fingerprint(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder out = new StringBuilder(24);
            for (int i = 0; i < 12 && i < hash.length; i++) {
                out.append(Character.forDigit((hash[i] >>> 4) & 0xF, 16));
                out.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException failure) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
    }

    private static final class Cursor {
        private final byte[] data;
        private int offset;

        private Cursor(byte[] data) {
            this.data = data;
        }

        private int position() {
            return this.offset;
        }

        private int u1() {
            require(1);
            return this.data[this.offset++] & 0xFF;
        }

        private int u2() {
            require(2);
            int value = ((this.data[this.offset] & 0xFF) << 8) | (this.data[this.offset + 1] & 0xFF);
            this.offset += 2;
            return value;
        }

        private long u4() {
            require(4);
            long value = ((long) (this.data[this.offset] & 0xFF) << 24)
                    | ((long) (this.data[this.offset + 1] & 0xFF) << 16)
                    | ((long) (this.data[this.offset + 2] & 0xFF) << 8)
                    | (long) (this.data[this.offset + 3] & 0xFF);
            this.offset += 4;
            return value;
        }

        private String utf8() {
            int length = u2();
            require(length);
            String value = new String(this.data, this.offset, length, java.nio.charset.StandardCharsets.UTF_8);
            this.offset += length;
            return value;
        }

        private void skip(long bytes) {
            if (bytes < 0L || bytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid skip length");
            }
            require((int) bytes);
            this.offset += (int) bytes;
        }

        private void require(int bytes) {
            if (bytes < 0 || this.offset + bytes > this.data.length) {
                throw new IllegalArgumentException("truncated classfile");
            }
        }
    }
}

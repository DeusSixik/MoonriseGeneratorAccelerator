package dev.sixik.generator_accelerator.common.density.compiler.compiler.cache;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.vector.DfcVectorSupport;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.Codegen;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.RefCount;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.natives.CodegenNativeNoise;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * SHA-256 digests of the post-optimisation IR and constant-pool layout.
 * <p>Two compiles with identical digests are safe to share the same hidden
 * class + MethodHandle bundle, instantiating a fresh instance with
 * {@link ConstantPool#finishConstants()}-style snapshots from the new pool.
 */
public final class CompilationFingerprint {

    private CompilationFingerprint() {}

    private enum Mode {
        EXACT,
        SHAPE
    }

    /**
     * 32-byte SHA-256 digest. Structure + {@link ConstantPool} bindings
     * (identities, structured noise specs) must be identical.
     */
    public static byte[] sha256(
            IRNode root,
            ConstantPool pool,
            double minValue,
            double maxValue) {
        return digest(root, pool, minValue, maxValue, Mode.EXACT);
    }

    /**
     * 32-byte SHA-256 digest of the generated class shape. This key intentionally
     * excludes object identities that are supplied through constructor arrays
     * ({@code noises}, {@code externs}, spline blobs, per-octave samplers), but keeps
     * every value that can alter emitted bytecode: IR topology, op tags, literal raw
     * bits, constant-pool slot counts, marker direct-read flags, inlined-noise numeric
     * layouts, blended-noise numeric layouts, and JVM/codegen feature flags.
     */
    public static byte[] shapeSha256(
            IRNode root,
            ConstantPool pool,
            double minValue,
            double maxValue) {
        return digest(root, pool, minValue, maxValue, Mode.SHAPE);
    }

    private static byte[] digest(
            IRNode root,
            ConstantPool pool,
            double minValue,
            double maxValue,
            Mode mode) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        sha.update((byte) (mode == Mode.EXACT ? 0xE1 : 0xE2));
        hashIrStructure(root, pool, sha);
        hashPoolBindings(pool, minValue, maxValue, sha, mode);
        hashCodegenCapabilities(sha);
        return sha.digest();
    }

    /**
     * Embeds JVM-wide codegen capabilities so scalar and vector hidden classes never
     * share a {@link GlobalCompileCache} key (e.g. moving from a non-vector to a
     * vector launcher must miss cache and re-emit).
     */
    private static void hashCodegenCapabilities(MessageDigest d) {
        d.update((byte) 0xC0);
        d.update((byte) 10);
        putUtf8(d, DfcVectorSupport.MODE);
        d.update((byte) (DfcVectorSupport.AVAILABLE ? 1 : 0));
        putU32(d, DfcVectorSupport.AVAILABLE ? DfcVectorSupport.PREFERRED_LANES : 0);
        d.update((byte) (Codegen.BATCHED_FILL_ENABLED ? 1 : 0));
        d.update((byte) (Codegen.CELL_LATTICE_ENABLED ? 1 : 0));
        d.update((byte) (Codegen.CELL_FILL_ADD_EXTERN_OVERRIDE_ENABLED ? 1 : 0));
        d.update((byte) (Codegen.SPLINE_RUNTIME_STATS_ENABLED ? 1 : 0));
        d.update((byte) (Codegen.INLINE_SMALL_RUNTIME_HELPERS ? 1 : 0));
        d.update((byte) (CodegenNativeNoise.enabled() ? 1 : 0));
        d.update((byte) (CodegenNativeNoise.emitNativeOps() ? 1 : 0));
    }

    public static String stableClassSuffix(byte[] sha256) {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 10; i++) {
            int b = sha256[i] & 0xff;
            sb.append(Character.forDigit(b >> 4, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static void hashIrStructure(IRNode root, ConstantPool pool, MessageDigest sha) {
        IdentityHashMap<IRNode, Object> done = new IdentityHashMap<>();
        IdentityHashMap<IRNode, Integer> ids = new IdentityHashMap<>();
        int[] nextId = {0};
        postOrderHash(root, pool, sha, done, ids, nextId);
    }

    private static void postOrderHash(IRNode n, ConstantPool pool, MessageDigest d,
            IdentityHashMap<IRNode, Object> done, IdentityHashMap<IRNode, Integer> ids, int[] nextId) {
        if (n == null) return;
        if (done.containsKey(n)) return;
        List<IRNode> children = childrenOf(n);
        for (IRNode c : children) {
            postOrderHash(c, pool, d, done, ids, nextId);
        }
        done.put(n, Boolean.TRUE);
        ids.put(n, nextId[0]++);
        hashNode(n, pool, d);
        putU32(d, children.size());
        for (IRNode c : children) {
            Integer childId = ids.get(c);
            if (childId == null) {
                throw new IllegalStateException("Unassigned child id while fingerprinting " + n);
            }
            putU32(d, childId);
        }
    }

    private static List<IRNode> childrenOf(IRNode n) {
        ArrayList<IRNode> children = new ArrayList<>();
        for (IRNode c : RefCount.children(n)) {
            children.add(c);
        }
        return children;
    }

    private static void putTag(MessageDigest d, int tag) {
        d.update((byte) (tag & 0xff));
        d.update((byte) ((tag >> 8) & 0xff));
    }

    private static void putU32(MessageDigest d, int v) {
        d.update((byte) (v & 0xff));
        d.update((byte) ((v >> 8) & 0xff));
        d.update((byte) ((v >> 16) & 0xff));
        d.update((byte) ((v >> 24) & 0xff));
    }

    private static void putUtf8(MessageDigest d, String v) {
        byte[] bytes = v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        putU32(d, bytes.length);
        d.update(bytes);
    }

    private static void putF64(MessageDigest d, double v) {
        long bits = Double.doubleToRawLongBits(v);
        for (int i = 0; i < 8; i++) {
            d.update((byte) (bits & 0xff));
            bits >>>= 8;
        }
    }

    private static void putBool(MessageDigest d, boolean v) {
        d.update((byte) (v ? 1 : 0));
    }

    private static void hashNode(IRNode n, ConstantPool pool, MessageDigest d) {
        if (n instanceof IRNode.Const c) { putTag(d, 1); putF64(d, c.value()); return; }
        if (n instanceof IRNode.BlockX) { putTag(d, 2); return; }
        if (n instanceof IRNode.BlockY) { putTag(d, 3); return; }
        if (n instanceof IRNode.BlockZ) { putTag(d, 4); return; }
        if (n instanceof IRNode.Bin b) { putTag(d, 5); putTag(d, b.op().ordinal()); return; }
        if (n instanceof IRNode.Unary u) { putTag(d, 6); putTag(d, u.op().ordinal()); return; }
        if (n instanceof IRNode.Clamp c) { putTag(d, 7); putF64(d, c.min()); putF64(d, c.max()); return; }
        if (n instanceof IRNode.RangeChoice c) { putTag(d, 8); putF64(d, c.min()); putF64(d, c.max()); return; }
        if (n instanceof IRNode.YClampedGradient y) { putTag(d, 9); putU32(d, y.fromY()); putU32(d, y.toY());
            putF64(d, y.fromValue()); putF64(d, y.toValue()); return; }
        if (n instanceof IRNode.Noise n1) { putTag(d, 10); putU32(d, n1.noiseIndex()); putF64(d, n1.xzScale());
            putF64(d, n1.yScale()); putF64(d, n1.maxValue()); return; }
        if (n instanceof IRNode.ShiftedNoise s) { putTag(d, 11); putU32(d, s.noiseIndex()); putF64(d, s.xzScale());
            putF64(d, s.yScale()); putF64(d, s.maxValue()); return; }
        if (n instanceof IRNode.ShiftA a) { putTag(d, 12); putU32(d, a.noiseIndex()); putF64(d, a.maxValue()); return; }
        if (n instanceof IRNode.ShiftB a) { putTag(d, 13); putU32(d, a.noiseIndex()); putF64(d, a.maxValue()); return; }
        if (n instanceof IRNode.Shift a) { putTag(d, 14); putU32(d, a.noiseIndex()); putF64(d, a.maxValue()); return; }
        if (n instanceof IRNode.WeirdScaled w) { putTag(d, 15); putU32(d, w.noiseIndex());
            putU32(d, w.rarityValueMapperOrdinal()); putF64(d, w.maxValue()); return; }
        if (n instanceof IRNode.InlinedNoise in) { putTag(d, 16); putU32(d, in.specPoolIndex());
            putF64(d, in.maxValue()); return; }
        if (n instanceof IRNode.InlinedBlendedNoise b) { putTag(d, 17); putU32(d, b.blendedSpecIndex());
            putF64(d, b.maxValue()); return; }
        if (n instanceof IRNode.WeirdRarity w) { putTag(d, 18); putU32(d, w.rarityValueMapperOrdinal()); return; }
        if (n instanceof IRNode.EndIslands e) { putTag(d, 19); putU32(d, e.externIndex()); return; }
        if (n instanceof IRNode.Spline.Constant c) { putTag(d, 20); putF32(d, c.value()); return; }
        if (n instanceof IRNode.Spline.Multipoint mp) { putTag(d, 25); putF32(d, mp.minValue()); putF32(d, mp.maxValue());
            d.update(shaFloatArrayDigest(mp.locations(), mp.derivatives())); return; }
        if (n instanceof IRNode.Marker m) { putTag(d, 21); putU32(d, m.externIndex()); return; }
        if (n instanceof IRNode.Invoke in) { putTag(d, 22); putU32(d, in.externIndex()); return; }
        if (n instanceof IRNode.Beardifier b) { putTag(d, 26); putU32(d, b.externIndex()); return; }
        if (n instanceof IRNode.BlendDensity) { putTag(d, 23); return; }
        throw new IllegalStateException("Unhandled IR node for fingerprint: " + n);
    }

    private static void putF32(MessageDigest d, float v) {
        int bits = Float.floatToRawIntBits(v);
        putU32(d, bits);
    }

    private static byte[] shaFloatArrayDigest(float[] a, float[] b) {
        try {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeInt(a == null ? -1 : a.length);
            if (a != null) for (float v : a) dos.writeInt(Float.floatToRawIntBits(v));
            dos.writeInt(b == null ? -1 : b.length);
            if (b != null) for (float v : b) dos.writeInt(Float.floatToRawIntBits(v));
            dos.flush();
            return MessageDigest.getInstance("SHA-256").digest(baos.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static void hashPoolBindings(ConstantPool pool, double minV, double maxV, MessageDigest d, Mode mode) {
        if (mode == Mode.EXACT) {
            putF64(d, minV);
            putF64(d, maxV);
        }
        d.update((byte) 0xA1);
        putU32(d, pool.constantCount());
        for (int i = 0; i < pool.constantCount(); i++) {
            putF64(d, pool.constant(i));
        }
        d.update((byte) 0xA2);
        putU32(d, pool.noiseCount());
        for (int i = 0; i < pool.noiseCount(); i++) {
            NormalNoise n = pool.noise(i);
            if (mode == Mode.EXACT) {
                putJdkIdentity(n, d);
            }
        }
        d.update((byte) 0xA3);
        putU32(d, pool.externCount());
        for (int i = 0; i < pool.externCount(); i++) {
            DensityFunction f = pool.extern(i);
            putBool(d, pool.externHasCacheWrapperFastPath(i));
            if (mode == Mode.EXACT) {
                putJdkIdentity(f, d);
            }
        }
        d.update((byte) 0xA4);
        putU32(d, pool.splineCount());
        for (int i = 0; i < pool.splineCount(); i++) {
            if (mode == Mode.EXACT) {
                putJdkIdentity(pool.splineObject(i), d);
            }
        }
        d.update((byte) 0xA5);
        putU32(d, pool.noiseSpecCount());
        for (int i = 0; i < pool.noiseSpecCount(); i++) {
            hashNoiseSpec(pool.noiseSpec(i), d, mode);
        }
        d.update((byte) 0xA6);
        putU32(d, pool.blendedNoiseSpecCount());
        for (int i = 0; i < pool.blendedNoiseSpecCount(); i++) {
            hashBlendedSpec(pool.blendedNoiseSpec(i), d, mode);
        }
    }

    private static void putJdkIdentity(Object o, MessageDigest d) {
        if (o == null) {
            d.update((byte) 0x00);
            return;
        }
        d.update((byte) 0x01);
        int id = System.identityHashCode(o);
        putU32(d, id);
    }

    private static void hashNoiseSpec(NoiseSpec s, MessageDigest d, Mode mode) {
        if (s == null) { d.update((byte) 0x7f); return; }
        d.update((byte) 0x40);
        putF64(d, s.valueFactor());
        hashPerlin(s.first(), d, mode);
        hashPerlin(s.second(), d, mode);
    }

    private static void hashPerlin(NoiseSpec.PerlinSpec p, MessageDigest d, Mode mode) {
        d.update((byte) 0x50);
        putF64(d, p.inputCoordScale());
        putU32(d, p.inputFactors().length);
        for (int i = 0; i < p.inputFactors().length; i++) {
            putF64(d, p.inputFactors()[i]);
        }
        putU32(d, p.ampValueFactors().length);
        for (int i = 0; i < p.ampValueFactors().length; i++) {
            putF64(d, p.ampValueFactors()[i]);
        }
        putU32(d, p.activeOctaves().length);
        for (int i = 0; i < p.activeOctaves().length; i++) {
            if (mode == Mode.EXACT) {
                putJdkIdentity(p.activeOctaves()[i], d);
            } else {
                putBool(d, p.activeOctaves()[i] != null);
            }
        }
    }

    private static void hashBlendedSpec(BlendedNoiseSpec b, MessageDigest d, Mode mode) {
        d.update((byte) 0x60);
        putF64(d, b.xzMultiplier());
        putF64(d, b.yMultiplier());
        putF64(d, b.xzFactor());
        putF64(d, b.yFactor());
        putF64(d, b.smearScaleMultiplier());
        putF64(d, b.maxValue());
        putU32(d, b.mainOctaves().length);
        for (int i = 0; i < b.mainOctaves().length; i++) {
            putOctaveBinding(b.mainOctaves()[i], d, mode);
        }
        putU32(d, b.minLimitOctaves().length);
        for (int i = 0; i < b.minLimitOctaves().length; i++) {
            putOctaveBinding(b.minLimitOctaves()[i], d, mode);
        }
        putU32(d, b.maxLimitOctaves().length);
        for (int i = 0; i < b.maxLimitOctaves().length; i++) {
            putOctaveBinding(b.maxLimitOctaves()[i], d, mode);
        }
    }

    private static void putOctaveBinding(Object octave, MessageDigest d, Mode mode) {
        if (mode == Mode.EXACT) {
            putJdkIdentity(octave, d);
        } else {
            putBool(d, octave != null);
        }
    }
}

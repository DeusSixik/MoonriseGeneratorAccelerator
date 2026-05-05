package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import java.lang.invoke.MethodHandles;

/**
 * Thin wrapper around {@link java.lang.invoke.MethodHandles.Lookup#defineHiddenClass} that
 * always defines hidden classes nested inside {@link CompiledDensityFunction}. Hidden
 * classes are GC-reclaimable when their last instance is unreachable, which keeps a
 * datapack {@code /reload} from leaking metaspace.
 */
public final class HiddenClassLoader {

    /**
     * Lookup pinned to {@link CompiledDensityFunction}. Created once because every hidden
     * class is "in" that nest, and because {@link MethodHandles#lookup()} captures the
     * caller's lookup which we want to be {@code CompiledDensityFunction}'s own lookup
     * for nestmate access (so the generated subclass can read the protected fields
     * directly with {@code GETFIELD}).
     */
    private static final MethodHandles.Lookup HOST_LOOKUP;

    static {
        try {
            // Reach into CompiledDensityFunction's own private lookup. The lookup() factory
            // inside that class is package-private (see HostLookupAccess) so we can fetch
            // the host's full-power lookup once at load time.
            HOST_LOOKUP = HostLookupAccess.lookup();
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private HiddenClassLoader() {}

    /**
     * Define a new hidden class with the given bytes inside the
     * {@link CompiledDensityFunction} nest. The class is {@link
     * MethodHandles.Lookup.ClassOption#NESTMATE} so it shares access with its host, and
     * intentionally <em>not</em> {@link MethodHandles.Lookup.ClassOption#STRONG}: we want
     * the GC to reclaim it once no compiled instance references it (e.g. across {@code
     * /reload}).
     */
    public static Class<? extends CompiledDensityFunction> define(byte[] bytecode) {
        return defineWithLookup(bytecode).cls();
    }

    /**
     * Variant that returns both the {@link Class} and the post-define
     * {@link MethodHandles.Lookup}. Callers need the lookup to materialise
     * {@link java.lang.invoke.MethodHandle} references to private static
     * helper methods on the new hidden class, which is the only way to
     * invoke them from compiled bytecode (hidden classes can't appear in
     * their own constant pool, so {@code INVOKESTATIC SelfClass.helper$N}
     * isn't an option).
     */
    public static DefineResult defineWithLookup(byte[] bytecode) {
        try {
            MethodHandles.Lookup defined = HOST_LOOKUP.defineHiddenClass(
                    bytecode, true, MethodHandles.Lookup.ClassOption.NESTMATE);
            @SuppressWarnings("unchecked")
            Class<? extends CompiledDensityFunction> cls =
                    (Class<? extends CompiledDensityFunction>) defined.lookupClass();
            return new DefineResult(cls, defined);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("HiddenClassLoader: lookup access denied", e);
        }
    }

    /** Pair of (defined hidden class, lookup with full access to it). */
    public record DefineResult(Class<? extends CompiledDensityFunction> cls,
                               MethodHandles.Lookup lookup) {}
}

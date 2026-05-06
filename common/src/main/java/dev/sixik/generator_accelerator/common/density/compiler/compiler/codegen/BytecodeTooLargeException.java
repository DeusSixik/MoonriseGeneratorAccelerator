package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

/**
 * Thrown when {@link Splitter} or {@link Codegen} cannot make a single
 * generated method fit under the JVM's 64 KiB code-attribute limit, even
 * after splitting the tree as aggressively as the policy allows. The catch
 * site in {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler#compileWithDetail(net.minecraft.world.level.levelgen.DensityFunction)}
 * falls back to the original DensityFunction so worldgen still works.
 */
public final class BytecodeTooLargeException extends RuntimeException {
    public BytecodeTooLargeException(String msg) { super(msg); }
}

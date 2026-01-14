package dev.sixik.density_compiller.compiler.pipeline.context;

import dev.sixik.asm.AsmCtx;
import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.locals.DensityCompilerLocals;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.PUTFIELD;

public class PipelineAsmContext extends AsmCtx {

    public static final String DEFAULT_LEAF_FUNCTION_NAME = "leaf_function";

    protected final DensityCompilerPipeline pipeline;

    public PipelineAsmContext(
            DensityCompilerPipeline pipeline,
            MethodVisitor mv,
            String ownerInternalName,
            int firstFreeLocal,
            int currentContextVar
    ) {
        super(mv, ownerInternalName, firstFreeLocal, currentContextVar);
        this.pipeline = pipeline;
    }

    public void putField(int iVar) {
        mv.visitFieldInsn(PUTFIELD,
                pipeline.configurator.className(),
                DEFAULT_LEAF_FUNCTION_NAME + "_" + iVar,
                DescriptorBuilder.builder().type(DensityFunction.class).build());
    }

    public int getOrCreateLeafIndex(DensityFunction leaf) {
        final DensityCompilerLocals locals = pipeline.locals;
        return locals.leafToId.computeIfAbsent(leaf, (k) -> {
            locals.leaves.add(k);
            return locals.leaves.size() - 1;
        });
    }

    public void visitLeaf(DensityFunction leaf) {
        final int variable = getOrCreateLeafIndex(leaf);

        String fieldName = DEFAULT_LEAF_FUNCTION_NAME + "_" + variable;
        String fieldDescriptor = DescriptorBuilder.builder().array(DensityFunction.class).build();

        loadThis();
        getField(fieldName, fieldDescriptor);
    }
}

import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.objectweb.asm.Opcodes.*;

public class ASMTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DensityCompilerData.boot();
    }

    @Test
    public void generateClass() {
        DensityFunctions.Constant constant = new DensityFunctions.Constant(5);

        TestCompiler compiler = new TestCompiler(this::method);
        compiler.compile(constant);
    }

    private void method(DensityCompilerContext context) {
        final var ctx = context.getCtx();
        final var mv = context.mv();

        int fobosVar = ctx.locals.getOrCreateInt(ctx, "fobos", () -> {
            ctx.aload(1);
            ctx.insn(ARRAYLENGTH);
        });

        var fI = ctx.newLocalInt();
        ctx.forIntRange(fI, () -> ctx.iload(fobosVar), (i) -> {

        });

        final var g2 = ctx.locals.getOrCreateInt(ctx, "test", () -> {
            ctx.iload(fobosVar);
        });

        fI = ctx.newLocalInt();
        ctx.forIntRange(fI, () -> ctx.iload(g2), (i) -> {

        });
    }

}

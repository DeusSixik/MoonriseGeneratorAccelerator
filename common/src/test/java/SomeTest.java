import dev.sixik.density_compiler.DensityCompiler;
import dev.sixik.density_compiler.utils.stack.HtmlTreeStackMachine;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

public class SomeTest {

    @BeforeAll
    static void onStart() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void testFobos() {
        DensityCompiler.from(new DensityFunctions.Constant(5), true).compile();
    }
}

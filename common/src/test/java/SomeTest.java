import dev.sixik.density_compiler.utils.stack.HtmlTreeStackMachine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

public class SomeTest {

    @Test
    void testFobos() {
        HtmlTreeStackMachine stackMachine = new HtmlTreeStackMachine();


        try {
            stackMachine.exportToHtml(Path.of("Fobos.html"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

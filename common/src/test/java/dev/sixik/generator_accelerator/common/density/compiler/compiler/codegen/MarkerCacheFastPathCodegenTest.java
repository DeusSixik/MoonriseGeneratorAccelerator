package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheArrayIndexAccess;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerCacheFastPathCodegenTest {

    @Test
    void reboundMarkerExternUsesCachedArrayIndexAccessField() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        DensityFunction root = DensityFunctions.add(
                DensityFunctions.constant(2.0),
                DensityFunctions.cacheAllInCell(DensityFunctions.constant(3.0)));

        DensityFunction compiled = Compiler.compile(root);
        assertInstanceOf(CompiledDensityFunction.class, compiled);

        TestArrayIndexCacheExtern cacheExtern = new TestArrayIndexCacheExtern(11.0);
        AtomicBoolean replacedMarker = new AtomicBoolean();
        DensityFunction rebound = compiled.mapAll(df -> {
            if (df instanceof DensityFunctions.MarkerOrMarked) {
                replacedMarker.set(true);
                return cacheExtern;
            }
            return df;
        });

        assertTrue(replacedMarker.get(), "Expected mapAll to visit the preserved marker extern");
        assertInstanceOf(CompiledDensityFunction.class, rebound);

        double value = rebound.compute(new DensityFunction.SinglePointContext(1, 2, 3));
        assertEquals(13.0, value, 0.0);
        assertEquals(1, cacheExtern.arrayIndexReads);
        assertEquals(0, cacheExtern.genericReads);
        assertEquals(0, cacheExtern.computeCalls);
    }

    private static final class TestArrayIndexCacheExtern implements DensityFunction, DfcCellCacheArrayIndexAccess {
        private final double value;
        private int arrayIndexReads;
        private int genericReads;
        private int computeCalls;

        private TestArrayIndexCacheExtern(double value) {
            this.value = value;
        }

        @Override
        public double dfc$tryDirectReadByArrayIndex(FunctionContext context) {
            arrayIndexReads++;
            return value;
        }

        @Override
        public double dfc$tryDirectRead(FunctionContext context) {
            genericReads++;
            return value;
        }

        @Override
        public double compute(FunctionContext context) {
            computeCalls++;
            return -1000.0;
        }

        @Override
        public void fillArray(double[] array, ContextProvider contextProvider) {
            for (int i = 0; i < array.length; i++) {
                array[i] = compute(contextProvider.forIndex(i));
            }
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(this);
        }

        @Override
        public double minValue() {
            return -1000.0;
        }

        @Override
        public double maxValue() {
            return value;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("test-only density function");
        }
    }
}

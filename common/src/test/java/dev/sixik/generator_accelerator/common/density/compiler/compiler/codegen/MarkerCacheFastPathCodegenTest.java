package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheArrayIndexAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCompiledClassRegistry;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void scalarMarkerCellFillOverrideDefinesInlineArrayBytecode() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        String previous = System.getProperty("dfc.codegen.cellFillScalarMarkerOverride");
        System.setProperty("dfc.codegen.cellFillScalarMarkerOverride", "true");
        try {
            DensityFunction root = DensityFunctions.cacheAllInCell(DensityFunctions.constant(4.0));
            DensityFunction compiled = Compiler.compile(root);
            assertInstanceOf(CompiledDensityFunction.class, compiled);

            DfcCompiledClassRegistry.Entry entry = DfcCompiledClassRegistry.lookup(compiled.getClass().getName());
            assertNotNull(entry);
            assertTrue(entry.cellScalarMarkerSpecialized(), entry.cellScalarMarkerReason());
        } finally {
            if (previous == null) {
                System.clearProperty("dfc.codegen.cellFillScalarMarkerOverride");
            } else {
                System.setProperty("dfc.codegen.cellFillScalarMarkerOverride", previous);
            }
        }
    }

    @Test
    void scalarMarkerCellFillOverrideSpecializesSqueezeMulMarker() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        String previous = System.getProperty("dfc.codegen.cellFillScalarMarkerOverride");
        System.setProperty("dfc.codegen.cellFillScalarMarkerOverride", "true");
        try {
            DensityFunction marker = DensityFunctions.cacheAllInCell(new TestArrayIndexCacheExtern(4.0));
            DensityFunction root = DensityFunctions.map(
                    DensityFunctions.mul(DensityFunctions.constant(0.64), marker),
                    DensityFunctions.Mapped.Type.SQUEEZE);
            DensityFunction compiled = Compiler.compile(root);
            assertInstanceOf(CompiledDensityFunction.class, compiled);

            DfcCompiledClassRegistry.Entry entry = DfcCompiledClassRegistry.lookup(compiled.getClass().getName());
            assertNotNull(entry);
            assertTrue(entry.cellScalarMarkerSpecialized(), entry.cellScalarMarkerReason());
            assertEquals("emitted:squeeze-mul", entry.cellScalarMarkerReason());
        } finally {
            if (previous == null) {
                System.clearProperty("dfc.codegen.cellFillScalarMarkerOverride");
            } else {
                System.setProperty("dfc.codegen.cellFillScalarMarkerOverride", previous);
            }
        }
    }

    @Test
    void scalarMarkerCellFillOverrideSpecializesMinWithSqueezeMulMarkerBranch() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        String previous = System.getProperty("dfc.codegen.cellFillScalarMarkerOverride");
        System.setProperty("dfc.codegen.cellFillScalarMarkerOverride", "true");
        try {
            DensityFunction marker = DensityFunctions.cacheAllInCell(DensityFunctions.constant(4.0));
            DensityFunction squeezeMul = DensityFunctions.map(
                    DensityFunctions.mul(DensityFunctions.constant(0.64), marker),
                    DensityFunctions.Mapped.Type.SQUEEZE);
            DensityFunction rangeChoice = DensityFunctions.rangeChoice(
                    DensityFunctions.cacheAllInCell(new TestArrayIndexCacheExtern(0.0)),
                    -3.333333333333333,
                    0.0,
                    DensityFunctions.constant(64.0),
                    DensityFunctions.constant(-2.0));
            DensityFunction root = DensityFunctions.min(squeezeMul, rangeChoice);
            DensityFunction compiled = Compiler.compile(root);
            assertInstanceOf(CompiledDensityFunction.class, compiled);

            DfcCompiledClassRegistry.Entry entry = DfcCompiledClassRegistry.lookup(compiled.getClass().getName());
            assertNotNull(entry);
            assertTrue(entry.cellScalarMarkerSpecialized(), entry.cellScalarMarkerReason());
            assertEquals("emitted:min-squeeze-mul", entry.cellScalarMarkerReason());
        } finally {
            if (previous == null) {
                System.clearProperty("dfc.codegen.cellFillScalarMarkerOverride");
            } else {
                System.setProperty("dfc.codegen.cellFillScalarMarkerOverride", previous);
            }
        }
    }

    @Test
    void scalarMarkerCellFillOverrideDefersRangeChoiceOutBranchZInterpolation() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        String previous = System.getProperty("dfc.codegen.cellFillScalarMarkerOverride");
        String previousLazyZ = System.getProperty("dfc.codegen.cellFillScalarMarkerLazyRangeChoiceZ");
        System.setProperty("dfc.codegen.cellFillScalarMarkerOverride", "true");
        System.setProperty("dfc.codegen.cellFillScalarMarkerLazyRangeChoiceZ", "true");
        try {
            DensityFunction marker = DensityFunctions.cacheAllInCell(new TestArrayIndexCacheExtern(4.0));
            DensityFunction squeezeMul = DensityFunctions.map(
                    DensityFunctions.mul(DensityFunctions.constant(0.64), marker),
                    DensityFunctions.Mapped.Type.SQUEEZE);
            DensityFunction rangeChoice = DensityFunctions.rangeChoice(
                    DensityFunctions.cacheAllInCell(new TestArrayIndexCacheExtern(0.0)),
                    -3.333333333333333,
                    0.0,
                    DensityFunctions.constant(64.0),
                    DensityFunctions.add(
                            DensityFunctions.cacheAllInCell(new TestArrayIndexCacheExtern(1.0)),
                            DensityFunctions.cacheAllInCell(new TestArrayIndexCacheExtern(2.0))));
            DensityFunction root = DensityFunctions.min(squeezeMul, rangeChoice);
            DensityFunction compiled = Compiler.compile(root);
            assertInstanceOf(CompiledDensityFunction.class, compiled);

            DfcCompiledClassRegistry.Entry entry = DfcCompiledClassRegistry.lookup(compiled.getClass().getName());
            assertNotNull(entry);
            assertTrue(entry.cellScalarMarkerSpecialized(), entry.cellScalarMarkerReason());
            assertEquals("emitted:min-squeeze-mul-lazy-z", entry.cellScalarMarkerReason());
        } finally {
            if (previous == null) {
                System.clearProperty("dfc.codegen.cellFillScalarMarkerOverride");
            } else {
                System.setProperty("dfc.codegen.cellFillScalarMarkerOverride", previous);
            }
            if (previousLazyZ == null) {
                System.clearProperty("dfc.codegen.cellFillScalarMarkerLazyRangeChoiceZ");
            } else {
                System.setProperty("dfc.codegen.cellFillScalarMarkerLazyRangeChoiceZ", previousLazyZ);
            }
        }
    }

    @Test
    void scalarMarkerCellFillOverrideKeepsLazyRangeChoiceZOptIn() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        String previous = System.getProperty("dfc.codegen.cellFillScalarMarkerOverride");
        String previousLazyZ = System.getProperty("dfc.codegen.cellFillScalarMarkerLazyRangeChoiceZ");
        System.setProperty("dfc.codegen.cellFillScalarMarkerOverride", "true");
        System.setProperty("dfc.codegen.cellFillScalarMarkerLazyRangeChoiceZ", "false");
        try {
            DensityFunction marker = DensityFunctions.cacheAllInCell(new TestArrayIndexCacheExtern(4.0));
            DensityFunction squeezeMul = DensityFunctions.map(
                    DensityFunctions.mul(DensityFunctions.constant(0.64), marker),
                    DensityFunctions.Mapped.Type.SQUEEZE);
            DensityFunction rangeChoice = DensityFunctions.rangeChoice(
                    DensityFunctions.cacheAllInCell(new TestArrayIndexCacheExtern(0.0)),
                    -3.333333333333333,
                    0.0,
                    DensityFunctions.constant(64.0),
                    DensityFunctions.add(
                            DensityFunctions.cacheAllInCell(new TestArrayIndexCacheExtern(1.0)),
                            DensityFunctions.cacheAllInCell(new TestArrayIndexCacheExtern(2.0))));
            DensityFunction root = DensityFunctions.min(squeezeMul, rangeChoice);
            DensityFunction compiled = Compiler.compile(root);
            assertInstanceOf(CompiledDensityFunction.class, compiled);

            DfcCompiledClassRegistry.Entry entry = DfcCompiledClassRegistry.lookup(compiled.getClass().getName());
            assertNotNull(entry);
            assertTrue(entry.cellScalarMarkerSpecialized(), entry.cellScalarMarkerReason());
            assertEquals("emitted:min-squeeze-mul", entry.cellScalarMarkerReason());
        } finally {
            if (previous == null) {
                System.clearProperty("dfc.codegen.cellFillScalarMarkerOverride");
            } else {
                System.setProperty("dfc.codegen.cellFillScalarMarkerOverride", previous);
            }
            if (previousLazyZ == null) {
                System.clearProperty("dfc.codegen.cellFillScalarMarkerLazyRangeChoiceZ");
            } else {
                System.setProperty("dfc.codegen.cellFillScalarMarkerLazyRangeChoiceZ", previousLazyZ);
            }
        }
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

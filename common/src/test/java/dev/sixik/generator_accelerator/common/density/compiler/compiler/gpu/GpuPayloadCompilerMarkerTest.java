package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuPayloadCompilerMarkerTest {
    private static final String INLINE_MARKERS_PROPERTY = "ga.dfc.gpu.inlineRecomputableMarkers";

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void cacheOnceMarkerStaysExternInputByDefault() {
        String previous = setInlineMarkersProperty(null);
        String previousSelective = setInlineCacheOnceMulOrAddProperty(null);
        try {
            ConstantPool pool = new ConstantPool();
            int markerExtern = pool.internExtern(DensityFunctions.cacheOnce(DensityFunctions.constant(7.0D)));

            GpuPayloadCompiler.Result result = GpuPayloadCompiler.compile(new IRNode.Marker(markerExtern), pool);

            assertTrue(result.supported(), result.firstUnsupportedDetail());
            GpuIrPayload payload = result.payload();
            assertEquals(1, payload.externInputCount());
            assertEquals(GpuIrPayload.EXTERN_INPUT, payload.opcodes()[payload.rootIndex()]);
        } finally {
            restoreInlineMarkersProperty(previous);
            restoreInlineCacheOnceMulOrAddProperty(previousSelective);
        }
    }

    @Test
    void cacheOnceMulOrAddMarkerInlinesWrappedPayloadByDefault() {
        String previous = setInlineMarkersProperty(null);
        String previousSelective = setInlineCacheOnceMulOrAddProperty(null);
        String previousAp2 = setInlineCacheOnceAp2MarkerExternIndicesProperty(null);
        try {
            ConstantPool pool = new ConstantPool();
            DensityFunction wrapped = DensityFunctions.add(
                    DensityFunctions.constant(2.0D),
                    DensityFunctions.constant(5.0D));
            int markerExtern = pool.internExtern(DensityFunctions.cacheOnce(wrapped));

            GpuPayloadCompiler.Result result = GpuPayloadCompiler.compile(new IRNode.Marker(markerExtern), pool);

            assertTrue(result.supported(), result.firstUnsupportedDetail());
            GpuIrPayload payload = result.payload();
            assertEquals(0, payload.externInputCount());
            assertTrue(payload.opcodes()[payload.rootIndex()] != GpuIrPayload.EXTERN_INPUT);
        } finally {
            restoreInlineMarkersProperty(previous);
            restoreInlineCacheOnceMulOrAddProperty(previousSelective);
            restoreInlineCacheOnceAp2MarkerExternIndicesProperty(previousAp2);
        }
    }

    @Test
    void cacheOnceAp2MarkerStaysExternInputWithoutIndexOptIn() {
        String previous = setInlineMarkersProperty(null);
        String previousSelective = setInlineCacheOnceMulOrAddProperty(null);
        String previousAp2 = setInlineCacheOnceAp2MarkerExternIndicesProperty(null);
        try {
            ConstantPool pool = new ConstantPool();
            DensityFunction wrapped = DensityFunctions.min(
                    DensityFunctions.constant(2.0D),
                    DensityFunctions.constant(5.0D));
            int markerExtern = pool.internExtern(DensityFunctions.cacheOnce(wrapped));

            GpuPayloadCompiler.Result result = GpuPayloadCompiler.compile(new IRNode.Marker(markerExtern), pool);

            assertTrue(result.supported(), result.firstUnsupportedDetail());
            GpuIrPayload payload = result.payload();
            assertEquals(1, payload.externInputCount());
            assertEquals(GpuIrPayload.EXTERN_INPUT, payload.opcodes()[payload.rootIndex()]);
        } finally {
            restoreInlineMarkersProperty(previous);
            restoreInlineCacheOnceMulOrAddProperty(previousSelective);
            restoreInlineCacheOnceAp2MarkerExternIndicesProperty(previousAp2);
        }
    }

    @Test
    void cacheOnceAp2MarkerInlinesWhenExternIndexOptedIn() {
        String previous = setInlineMarkersProperty(null);
        String previousSelective = setInlineCacheOnceMulOrAddProperty(null);
        String previousAp2 = setInlineCacheOnceAp2MarkerExternIndicesProperty("0");
        try {
            ConstantPool pool = new ConstantPool();
            DensityFunction wrapped = DensityFunctions.min(
                    DensityFunctions.constant(2.0D),
                    DensityFunctions.constant(5.0D));
            int markerExtern = pool.internExtern(DensityFunctions.cacheOnce(wrapped));

            GpuPayloadCompiler.Result result = GpuPayloadCompiler.compile(new IRNode.Marker(markerExtern), pool);

            assertTrue(result.supported(), result.firstUnsupportedDetail());
            GpuIrPayload payload = result.payload();
            assertEquals(0, payload.externInputCount());
            assertTrue(payload.opcodes()[payload.rootIndex()] != GpuIrPayload.EXTERN_INPUT);
        } finally {
            restoreInlineMarkersProperty(previous);
            restoreInlineCacheOnceMulOrAddProperty(previousSelective);
            restoreInlineCacheOnceAp2MarkerExternIndicesProperty(previousAp2);
        }
    }

    @Test
    void cacheOnceAp2MarkerInlinesOuterMarkerAndKeepsNestedExternInputsWhenOptedIn() {
        String previous = setInlineMarkersProperty(null);
        String previousSelective = setInlineCacheOnceMulOrAddProperty(null);
        String previousAp2 = setInlineCacheOnceAp2MarkerExternIndicesProperty("0");
        String previousAllowNested = setInlineCacheOnceAp2AllowNestedExternInputsProperty(true);
        try {
            ConstantPool pool = new ConstantPool();
            DensityFunction nestedMarker = DensityFunctions.flatCache(DensityFunctions.constant(3.0D));
            DensityFunction wrapped = DensityFunctions.min(
                    nestedMarker,
                    DensityFunctions.yClampedGradient(0, 1, 0.0D, 6.0D));
            int markerExtern = pool.internExtern(DensityFunctions.cacheOnce(wrapped));

            GpuPayloadCompiler.Result result = GpuPayloadCompiler.compile(new IRNode.Marker(markerExtern), pool);

            assertTrue(result.supported(), result.firstUnsupportedDetail());
            GpuIrPayload payload = result.payload();
            assertEquals(1, payload.externInputCount());
            assertTrue(payload.opcodes()[payload.rootIndex()] != GpuIrPayload.EXTERN_INPUT);
            assertEquals(0, payload.externInputPathLengths()[0]);
            assertEquals(1, payload.externInputLeafExternIndices()[0]);
        } finally {
            restoreInlineMarkersProperty(previous);
            restoreInlineCacheOnceMulOrAddProperty(previousSelective);
            restoreInlineCacheOnceAp2MarkerExternIndicesProperty(previousAp2);
            restoreInlineCacheOnceAp2AllowNestedExternInputsProperty(previousAllowNested);
        }
    }

    @Test
    void cacheOnceAp2MarkerWithNestedExternInputsStaysExternWithoutNestedOptIn() {
        String previous = setInlineMarkersProperty(null);
        String previousSelective = setInlineCacheOnceMulOrAddProperty(null);
        String previousAp2 = setInlineCacheOnceAp2MarkerExternIndicesProperty("0");
        String previousAllowNested = setInlineCacheOnceAp2AllowNestedExternInputsProperty(false);
        try {
            ConstantPool pool = new ConstantPool();
            DensityFunction nestedMarker = DensityFunctions.flatCache(DensityFunctions.constant(3.0D));
            DensityFunction wrapped = DensityFunctions.min(
                    nestedMarker,
                    DensityFunctions.yClampedGradient(0, 1, 0.0D, 6.0D));
            int markerExtern = pool.internExtern(DensityFunctions.cacheOnce(wrapped));

            GpuPayloadCompiler.Result result = GpuPayloadCompiler.compile(new IRNode.Marker(markerExtern), pool);

            assertTrue(result.supported(), result.firstUnsupportedDetail());
            GpuIrPayload payload = result.payload();
            assertEquals(1, payload.externInputCount());
            assertEquals(GpuIrPayload.EXTERN_INPUT, payload.opcodes()[payload.rootIndex()]);
            assertEquals(0, payload.externInputPathLengths()[0]);
            assertEquals(0, payload.externInputLeafExternIndices()[0]);
        } finally {
            restoreInlineMarkersProperty(previous);
            restoreInlineCacheOnceMulOrAddProperty(previousSelective);
            restoreInlineCacheOnceAp2MarkerExternIndicesProperty(previousAp2);
            restoreInlineCacheOnceAp2AllowNestedExternInputsProperty(previousAllowNested);
        }
    }

    @Test
    void pureCacheOnceMarkerInlinesWrappedPayloadWithoutExternInput() {
        String previous = setInlineMarkersProperty(true);
        try {
            ConstantPool pool = new ConstantPool();
            int markerExtern = pool.internExtern(DensityFunctions.cacheOnce(DensityFunctions.constant(7.0D)));

            GpuPayloadCompiler.Result result = GpuPayloadCompiler.compile(new IRNode.Marker(markerExtern), pool);

            assertTrue(result.supported(), result.firstUnsupportedDetail());
            GpuIrPayload payload = result.payload();
            assertEquals(0, payload.externInputCount());
            assertEquals(GpuIrPayload.CONST, payload.opcodes()[payload.rootIndex()]);
            assertEquals(7.0D, payload.value0()[payload.rootIndex()]);
        } finally {
            restoreInlineMarkersProperty(previous);
        }
    }

    @Test
    void flatCacheMarkerStaysExternInput() {
        String previous = setInlineMarkersProperty(true);
        try {
            ConstantPool pool = new ConstantPool();
            int markerExtern = pool.internExtern(DensityFunctions.flatCache(DensityFunctions.constant(7.0D)));

            GpuPayloadCompiler.Result result = GpuPayloadCompiler.compile(new IRNode.Marker(markerExtern), pool);

            assertTrue(result.supported(), result.firstUnsupportedDetail());
            GpuIrPayload payload = result.payload();
            assertEquals(1, payload.externInputCount());
            assertEquals(GpuIrPayload.EXTERN_INPUT, payload.opcodes()[payload.rootIndex()]);
        } finally {
            restoreInlineMarkersProperty(previous);
        }
    }

    @Test
    void cacheOnceDoesNotInlineWrappedPayloadWithExternInputs() {
        String previous = setInlineMarkersProperty(true);
        try {
            DensityFunction wrappedWithMarkerExtern = DensityFunctions.flatCache(DensityFunctions.constant(7.0D));
            ConstantPool pool = new ConstantPool();
            int markerExtern = pool.internExtern(DensityFunctions.cacheOnce(wrappedWithMarkerExtern));

            GpuPayloadCompiler.Result result = GpuPayloadCompiler.compile(new IRNode.Marker(markerExtern), pool);

            assertTrue(result.supported(), result.firstUnsupportedDetail());
            GpuIrPayload payload = result.payload();
            assertEquals(1, payload.externInputCount());
            assertEquals(GpuIrPayload.EXTERN_INPUT, payload.opcodes()[payload.rootIndex()]);
        } finally {
            restoreInlineMarkersProperty(previous);
        }
    }

    private static String setInlineMarkersProperty(Boolean enabled) {
        String previous = System.getProperty(INLINE_MARKERS_PROPERTY);
        if (enabled == null) {
            System.clearProperty(INLINE_MARKERS_PROPERTY);
        } else {
            System.setProperty(INLINE_MARKERS_PROPERTY, enabled.toString());
        }
        return previous;
    }

    private static String setInlineCacheOnceMulOrAddProperty(Boolean enabled) {
        String previous = System.getProperty("ga.dfc.gpu.inlineCacheOnceMulOrAddMarkers");
        if (enabled == null) {
            System.clearProperty("ga.dfc.gpu.inlineCacheOnceMulOrAddMarkers");
        } else {
            System.setProperty("ga.dfc.gpu.inlineCacheOnceMulOrAddMarkers", enabled.toString());
        }
        return previous;
    }

    private static String setInlineCacheOnceAp2MarkerExternIndicesProperty(String value) {
        String previous = System.getProperty("ga.dfc.gpu.inlineCacheOnceAp2MarkerExternIndices");
        if (value == null) {
            System.clearProperty("ga.dfc.gpu.inlineCacheOnceAp2MarkerExternIndices");
        } else {
            System.setProperty("ga.dfc.gpu.inlineCacheOnceAp2MarkerExternIndices", value);
        }
        return previous;
    }

    private static String setInlineCacheOnceAp2AllowNestedExternInputsProperty(Boolean enabled) {
        String key = "ga.dfc.gpu.inlineCacheOnceAp2AllowNestedExternInputs";
        String previous = System.getProperty(key);
        if (enabled == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, enabled.toString());
        }
        return previous;
    }

    private static void restoreInlineMarkersProperty(String previous) {
        if (previous == null) {
            System.clearProperty(INLINE_MARKERS_PROPERTY);
        } else {
            System.setProperty(INLINE_MARKERS_PROPERTY, previous);
        }
    }

    private static void restoreInlineCacheOnceMulOrAddProperty(String previous) {
        if (previous == null) {
            System.clearProperty("ga.dfc.gpu.inlineCacheOnceMulOrAddMarkers");
        } else {
            System.setProperty("ga.dfc.gpu.inlineCacheOnceMulOrAddMarkers", previous);
        }
    }

    private static void restoreInlineCacheOnceAp2MarkerExternIndicesProperty(String previous) {
        if (previous == null) {
            System.clearProperty("ga.dfc.gpu.inlineCacheOnceAp2MarkerExternIndices");
        } else {
            System.setProperty("ga.dfc.gpu.inlineCacheOnceAp2MarkerExternIndices", previous);
        }
    }

    private static void restoreInlineCacheOnceAp2AllowNestedExternInputsProperty(String previous) {
        String key = "ga.dfc.gpu.inlineCacheOnceAp2AllowNestedExternInputs";
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}

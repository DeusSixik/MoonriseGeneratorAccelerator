package dev.sixik.generator_accelerator.common.worldgen.parallel;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class GACustomChunkGraphSchedulerTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void tearDown() {
        GACustomChunkGraphScheduler.resetMetrics();
    }

    @Test
    void snapshotReportsEnabledStateAndResettableCounters() {
        Map<String, Object> snapshot = GACustomChunkGraphScheduler.snapshot();

        assertEquals(true, snapshot.get("enabled"));
        assertTrue(snapshot.containsKey("eagerEmptyRadius"));
        assertEquals(0L, snapshot.get("tasksSubmitted"));
        assertEquals(0L, snapshot.get("tasksCompleted"));
        assertEquals(0L, snapshot.get("tasksFailed"));
        assertEquals(0L, snapshot.get("emptyNodesSubmitted"));
        assertEquals(0L, snapshot.get("graphNodesSubmitted"));
        assertEquals(0L, snapshot.get("generationGraphs"));
        assertEquals(0L, snapshot.get("loadingGraphs"));
    }

    @Test
    void generationGraphForEveryTargetStatusIsTopologicallyDrained() {
        for (ChunkStatus targetStatus : ChunkStatus.getStatusList()) {
            if (targetStatus == ChunkStatus.EMPTY) {
                continue;
            }
            assertGraphDrainsCompletely(ChunkPyramid.GENERATION_PYRAMID, targetStatus);
        }
    }

    @Test
    void inFlightCoalescingDoesNotTreatDifferentChunkStepsAsEquivalent() throws Exception {
        ChunkStatus status = ChunkStatus.SPAWN;
        ChunkStep generationStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
        ChunkStep loadingStep = ChunkPyramid.LOADING_PYRAMID.getStepTo(status);

        assertNotSame(generationStep, loadingStep);
        assertFalse(haveSameDirectDependencies(generationStep.directDependencies(), loadingStep.directDependencies()));

        Class<?> inFlightStepClass = null;
        Class<?> callbackClass = null;
        for (Class<?> declaredClass : GACustomChunkGraphScheduler.class.getDeclaredClasses()) {
            if ("InFlightStep".equals(declaredClass.getSimpleName())) {
                inFlightStepClass = declaredClass;
            } else if ("StepCompletionCallback".equals(declaredClass.getSimpleName())) {
                callbackClass = declaredClass;
            }
        }

        assertNotNull(inFlightStepClass);
        assertNotNull(callbackClass);

        Object callback = Proxy.newProxyInstance(
                callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass},
                noOpInvocationHandler()
        );

        Constructor<?> constructor = inFlightStepClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);

        Object inFlightStep;
        if (constructor.getParameterCount() == 5) {
            inFlightStep = constructor.newInstance(null, generationStep, status.getIndex(), 0, callback);
        } else if (constructor.getParameterCount() == 4) {
            inFlightStep = constructor.newInstance(null, status.getIndex(), 0, callback);
        } else {
            fail("Unexpected InFlightStep constructor shape: " + constructor);
            return;
        }

        Method matches = null;
        for (Method method : inFlightStepClass.getDeclaredMethods()) {
            if ("matches".equals(method.getName())) {
                matches = method;
                break;
            }
        }

        assertNotNull(matches);
        matches.setAccessible(true);

        boolean equivalent;
        if (matches.getParameterCount() == 3) {
            equivalent = (boolean) matches.invoke(inFlightStep, null, loadingStep, status.getIndex());
        } else if (matches.getParameterCount() == 2) {
            equivalent = (boolean) matches.invoke(inFlightStep, null, status.getIndex());
        } else {
            fail("Unexpected matches() shape: " + matches);
            return;
        }

        assertFalse(equivalent, "In-flight coalescing must distinguish generation and loading ChunkStep for the same holder/status");
    }

    private static void assertGraphDrainsCompletely(ChunkPyramid pyramid, ChunkStatus targetStatus) {
        ChunkStep targetStep = pyramid.getStepTo(targetStatus);
        int emptyRadius = targetStep.getAccumulatedRadiusOf(ChunkStatus.EMPTY);
        Map<NodeKey, TestNode> nodes = new LinkedHashMap<>();

        for (ChunkStatus status : ChunkStatus.getStatusList()) {
            if (status == ChunkStatus.EMPTY || status.isAfter(targetStatus)) {
                continue;
            }

            int radius = targetStep.getAccumulatedRadiusOf(status);
            ChunkStep step = pyramid.getStepTo(status);
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    NodeKey key = new NodeKey(x, z, status);
                    TestNode previous = nodes.put(key, new TestNode(key, step));
                    if (previous != null) {
                        fail("Duplicate node for " + key);
                    }
                }
            }
        }

        for (TestNode node : nodes.values()) {
            ChunkDependencies dependencies = node.step.directDependencies();
            ChunkPos pos = node.key.pos();
            for (int x = pos.x - dependencies.getRadius(); x <= pos.x + dependencies.getRadius(); x++) {
                for (int z = pos.z - dependencies.getRadius(); z <= pos.z + dependencies.getRadius(); z++) {
                    int distance = pos.getChessboardDistance(x, z);
                    ChunkStatus required = dependencies.get(distance);
                    if (required == ChunkStatus.EMPTY) {
                        continue;
                    }

                    NodeKey dependencyKey = new NodeKey(x, z, required);
                    TestNode dependency = nodes.get(dependencyKey);
                    if (dependency == null) {
                        if (Math.max(Math.abs(x), Math.abs(z)) <= emptyRadius) {
                            fail("Missing non-empty dependency " + dependencyKey + " for " + node.key + " within empty radius " + emptyRadius);
                        }
                        continue;
                    }

                    dependency.dependents.add(node);
                    node.pendingDependencies++;
                }
            }
        }

        ArrayDeque<TestNode> ready = new ArrayDeque<>();
        for (TestNode node : nodes.values()) {
            if (node.pendingDependencies == 0) {
                ready.add(node);
            }
        }

        int completed = 0;
        while (!ready.isEmpty()) {
            TestNode node = ready.removeFirst();
            completed++;
            for (TestNode dependent : node.dependents) {
                dependent.pendingDependencies--;
                if (dependent.pendingDependencies == 0) {
                    ready.addLast(dependent);
                }
            }
        }

        if (completed != nodes.size()) {
            List<String> unresolved = new ArrayList<>();
            for (TestNode node : nodes.values()) {
                if (node.pendingDependencies > 0) {
                    unresolved.add(node.key + " pending=" + node.pendingDependencies);
                }
            }
            fail("Graph did not drain for " + targetStatus + ": completed=" + completed + "/" + nodes.size() + ", unresolved=" + unresolved);
        }
    }

    private static boolean haveSameDirectDependencies(ChunkDependencies left, ChunkDependencies right) {
        if (left.getRadius() != right.getRadius()) {
            return false;
        }
        for (int distance = 0; distance <= left.getRadius(); distance++) {
            if (left.get(distance) != right.get(distance)) {
                return false;
            }
        }
        return true;
    }

    private static InvocationHandler noOpInvocationHandler() {
        return (proxy, method, args) -> null;
    }

    private record NodeKey(int x, int z, ChunkStatus status) {
        private ChunkPos pos() {
            return new ChunkPos(this.x, this.z);
        }

        @Override
        public String toString() {
            return "[" + this.x + "," + this.z + "]@" + this.status;
        }
    }

    private static final class TestNode {
        private final NodeKey key;
        private final ChunkStep step;
        private final List<TestNode> dependents = new ArrayList<>();
        private int pendingDependencies;

        private TestNode(NodeKey key, ChunkStep step) {
            this.key = Objects.requireNonNull(key);
            this.step = Objects.requireNonNull(step);
        }
    }
}

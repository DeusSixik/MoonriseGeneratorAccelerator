package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.CellLatticeOption;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.SlabInnerNativeProgram;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.SlabNativeBatchPlan;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.mixin.noise.ImprovedNoiseAccessor;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Per-instance OpenCL diagnostic metadata for compiled density functions.
 *
 * <p>The command path cannot reliably recover the original source tree from the
 * visitor cache after the router has been compiled. Register the compact OpenCL
 * plan while the compiler still has the IR, then keep it only as long as the
 * compiled density function instance is alive.
 */
public final class DfcOpenClCompiledPlanRegistry {
    private static final Map<CompiledDensityFunction, Entry> PLANS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final String NOISE_CHUNK_DENSITY_FUNCTION =
            "net.minecraft.world.level.levelgen.NoiseChunk$NoiseChunkDensityFunction";
    private static final String BEARDIFIER_OR_MARKER =
            "net.minecraft.world.level.levelgen.DensityFunctions$BeardifierOrMarker";
    private static final String MARKER_OR_MARKED =
            "net.minecraft.world.level.levelgen.DensityFunctions$MarkerOrMarked";
    private static final String IMMUTABLE_MARKER =
            "net.minecraft.world.level.levelgen.DensityFunctions$Marker";
    private static final double[] FLAT_SIMPLEX_GRAD = new double[]{
            1.0D, 1.0D, 0.0D, 0.0D,
            -1.0D, 1.0D, 0.0D, 0.0D,
            1.0D, -1.0D, 0.0D, 0.0D,
            -1.0D, -1.0D, 0.0D, 0.0D,
            1.0D, 0.0D, 1.0D, 0.0D,
            -1.0D, 0.0D, 1.0D, 0.0D,
            1.0D, 0.0D, -1.0D, 0.0D,
            -1.0D, 0.0D, -1.0D, 0.0D,
            0.0D, 1.0D, 1.0D, 0.0D,
            0.0D, -1.0D, 1.0D, 0.0D,
            0.0D, 1.0D, -1.0D, 0.0D,
            0.0D, -1.0D, -1.0D, 0.0D,
            1.0D, 1.0D, 0.0D, 0.0D,
            0.0D, -1.0D, 1.0D, 0.0D,
            -1.0D, 1.0D, 0.0D, 0.0D,
            0.0D, -1.0D, -1.0D, 0.0D
    };

    private DfcOpenClCompiledPlanRegistry() {
    }

    public static void register(CompiledDensityFunction compiled, IRNode root, ConstantPool pool,
                                Set<IRNode> extracted) {
        if (compiled == null || root == null || pool == null || extracted == null) {
            return;
        }
        Entry entry;
        try {
            DfcOpenClRuntime.OpenClCompiledPlan plan = build("compiled", root, pool, extracted);
            entry = Entry.available(planExternsRetainable(plan.externs()) ? plan : withoutExterns(plan));
        } catch (Throwable throwable) {
            entry = Entry.unavailable(errorMessage(throwable));
        }
        PLANS.put(compiled, entry);
    }

    public static Entry lookup(CompiledDensityFunction compiled) {
        if (compiled == null) {
            return Entry.unavailable("compiled density function is null");
        }
        Entry entry = PLANS.get(compiled);
        if (entry == null) {
            return Entry.unavailable(
                    "compiled density function has no registered OpenCL plan; it may have been compiled before "
                            + "this diagnostic registry was installed");
        }
        if (!entry.available() || entry.plan().externs() != null) {
            return entry;
        }
        return Entry.available(withExterns(entry.plan(), compiled.dfc$openClRuntimeExterns()));
    }

    public static void registerRebind(CompiledDensityFunction source, CompiledDensityFunction rebound,
                                      DensityFunction[] reboundExterns) {
        if (source == null || rebound == null || source == rebound) {
            return;
        }
        Entry entry = PLANS.get(source);
        if (entry == null) {
            return;
        }
        if (!entry.available()) {
            PLANS.put(rebound, entry);
            return;
        }
        DfcOpenClRuntime.OpenClCompiledPlan plan = entry.plan();
        DensityFunction[] externs = reboundExterns == null ? plan.externs() : reboundExterns.clone();
        PLANS.put(rebound, Entry.available(planExternsRetainable(externs)
                ? withExterns(plan, externs)
                : withoutExterns(plan)));
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan withoutExterns(DfcOpenClRuntime.OpenClCompiledPlan plan) {
        return withExterns(plan, null);
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan withExterns(DfcOpenClRuntime.OpenClCompiledPlan plan,
                                                                   DensityFunction[] externs) {
        return new DfcOpenClRuntime.OpenClCompiledPlan(
                plan.label(),
                plan.specs(),
                plan.slabProgram(),
                plan.slabConstants(),
                plan.hoistExpression(),
                plan.hoistEvaluator(),
                plan.slotCoordXExpressions(),
                plan.slotCoordYExpressions(),
                plan.slotCoordZExpressions(),
                plan.slotCoordXEvaluators(),
                plan.slotCoordYEvaluators(),
                plan.slotCoordZEvaluators(),
                plan.blendedSpecs(),
                plan.externalSlots(),
                plan.markerExternIndices(),
                externs == null ? null : externs.clone(),
                plan.computedSlots());
    }

    static boolean planExternsRetainable(DensityFunction[] externs) {
        if (externs == null) {
            return true;
        }
        for (DensityFunction extern : externs) {
            if (!reboundExternRetainable(extern)) {
                return false;
            }
        }
        return true;
    }

    private static boolean reboundExternRetainable(DensityFunction extern) {
        if (extern == null) {
            return true;
        }
        Class<?> externClass = extern.getClass();
        return reboundExternTypeRetainable(externClass.getName(), interfaceNames(externClass));
    }

    static boolean reboundExternTypeRetainable(String className, List<String> interfaceNames) {
        if (interfaceNames != null) {
            if (interfaceNames.contains(NOISE_CHUNK_DENSITY_FUNCTION)) {
                return false;
            }
            if (interfaceNames.contains(BEARDIFIER_OR_MARKER)) {
                return false;
            }
            if (interfaceNames.contains(MARKER_OR_MARKED) && !IMMUTABLE_MARKER.equals(className)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> interfaceNames(Class<?> type) {
        List<String> out = new ArrayList<>();
        collectInterfaceNames(type, out);
        return out;
    }

    private static void collectInterfaceNames(Class<?> type, List<String> out) {
        if (type == null) {
            return;
        }
        for (Class<?> iface : type.getInterfaces()) {
            out.add(iface.getName());
            collectInterfaceNames(iface, out);
        }
        collectInterfaceNames(type.getSuperclass(), out);
    }

    public static void clear() {
        PLANS.clear();
    }

    public static DfcOpenClRuntime.OpenClCompiledPlan expandMarkerSlots(DfcOpenClRuntime.OpenClCompiledPlan plan,
                                                                        int maxDepth) {
        if (plan == null || maxDepth <= 0) {
            return plan;
        }
        return expandMarkerSlots(plan, maxDepth, new IdentityHashMap<>());
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan expandMarkerSlots(DfcOpenClRuntime.OpenClCompiledPlan plan,
                                                                         int remainingDepth,
                                                                         IdentityHashMap<DensityFunction, Boolean> seen) {
        if (plan == null || remainingDepth <= 0 || plan.externalSlots() == null) {
            return plan;
        }

        List<NoiseSpec> specs = noiseSpecs(plan.specs());
        List<BlendedNoiseSpec> blendedSpecs = blendedNoiseSpecs(plan.blendedSpecs(), plan.specs().length);
        List<Boolean> externalSlots = booleans(plan.externalSlots());
        List<Integer> markerExternIndices = ints(plan.markerExternIndices());
        List<DfcOpenClRuntime.ComputedSlot> computedSlots = computed(plan.computedSlots(), plan.specs().length);
        List<String> coordXExpressions = strings(plan.slotCoordXExpressions());
        List<String> coordYExpressions = strings(plan.slotCoordYExpressions());
        List<String> coordZExpressions = strings(plan.slotCoordZExpressions());
        List<DfcOpenClRuntime.HoistEvaluator> coordXEvaluators = evaluators(plan.slotCoordXEvaluators());
        List<DfcOpenClRuntime.HoistEvaluator> coordYEvaluators = evaluators(plan.slotCoordYEvaluators());
        List<DfcOpenClRuntime.HoistEvaluator> coordZEvaluators = evaluators(plan.slotCoordZEvaluators());
        List<DensityFunction> externs = densityFunctions(plan.externs());

        boolean changed = false;
        int originalSlots = plan.specs().length;
        for (int slot = 0; slot < originalSlots; slot++) {
            if (!externalSlots.get(slot)) {
                continue;
            }
            DfcOpenClRuntime.OpenClCompiledPlan child = compileMarkerWrappedPlan(plan, slot, remainingDepth, seen);
            if (child == null) {
                continue;
            }

            int offset = specs.size();
            int externOffset = externs.size();
            appendExterns(child.externs(), externs);
            appendRemappedChild(child, offset, externOffset, specs, blendedSpecs,
                    externalSlots, markerExternIndices, computedSlots,
                    coordXExpressions, coordYExpressions, coordZExpressions,
                    coordXEvaluators, coordYEvaluators, coordZEvaluators);
            computedSlots.set(slot, new DfcOpenClRuntime.ComputedSlot(
                    remapSlotOperands(child.slabProgram(), offset),
                    child.slabConstants(),
                    remapSlotExpression(child.hoistExpression(), offset),
                    child.hoistEvaluator(),
                    child.label()));
            externalSlots.set(slot, false);
            markerExternIndices.set(slot, -1);
            changed = true;
        }

        if (!changed) {
            return plan;
        }
        return new DfcOpenClRuntime.OpenClCompiledPlan(
                plan.label(),
                specs.toArray(new NoiseSpec[0]),
                plan.slabProgram(),
                plan.slabConstants(),
                plan.hoistExpression(),
                plan.hoistEvaluator(),
                coordXExpressions.toArray(new String[0]),
                coordYExpressions.toArray(new String[0]),
                coordZExpressions.toArray(new String[0]),
                coordXEvaluators.toArray(new DfcOpenClRuntime.HoistEvaluator[0]),
                coordYEvaluators.toArray(new DfcOpenClRuntime.HoistEvaluator[0]),
                coordZEvaluators.toArray(new DfcOpenClRuntime.HoistEvaluator[0]),
                blendedSpecs.toArray(new BlendedNoiseSpec[0]),
                toBooleanArray(externalSlots),
                toIntArray(markerExternIndices),
                externs.toArray(new DensityFunction[0]),
                computedSlots.toArray(new DfcOpenClRuntime.ComputedSlot[0]));
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan build(String labelPrefix, IRNode root, ConstantPool pool,
                                                            Set<IRNode> extracted) {
        CellLatticeOption.LatticePlan latticePlan = CellLatticeOption.analyze(root).orElse(null);
        if (latticePlan == null) {
            return buildFullRoot(labelPrefix, root, pool, extracted);
        }
        try {
            return buildLattice(labelPrefix, root, pool, extracted, latticePlan);
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            return buildFullRoot(labelPrefix, root, pool, extracted);
        }
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan buildLattice(String labelPrefix, IRNode root, ConstantPool pool,
                                                                    Set<IRNode> extracted,
                                                                    CellLatticeOption.LatticePlan latticePlan) {
        SlabNativeBatchPlan slabPlan = SlabNativeBatchPlan.analyze(
                        root, latticePlan, pool.noiseSpecCount(), pool.blendedNoiseSpecCount())
                .orElseThrow(() -> new IllegalStateException("compiled root has no native slab slot plan"));
        SlabInnerNativeProgram.Result slabProgram = SlabInnerNativeProgram.tryCompile(
                        root, latticePlan, slabPlan, extracted)
                .orElseThrow(() -> new IllegalStateException("compiled root has no slab inner VM program"));

        SlotPlan slotPlan = collectSlotPlan(slabPlan, pool, true);
        IRNode hoisted = latticePlan.hoistedSubtree();
        String hoistExpression = openClExpression(hoisted);
        DfcOpenClRuntime.HoistEvaluator hoistEvaluator = (bx, by, bz) ->
                evalOpenClExpression(hoisted, bx, by, bz);
        return new DfcOpenClRuntime.OpenClCompiledPlan(
                labelPrefix + "/" + latticePlan.hoistAxis() + "/slots=" + slotPlan.specs().length
                        + (slabProgram.applyBlendDensity() ? "/blendPass" : ""),
                slotPlan.specs(),
                slabProgram.bytecode(),
                slabProgram.constants(),
                hoistExpression,
                hoistEvaluator,
                slotPlan.coordXExpressions(),
                slotPlan.coordYExpressions(),
                slotPlan.coordZExpressions(),
                slotPlan.coordXEvaluators(),
                slotPlan.coordYEvaluators(),
                slotPlan.coordZEvaluators(),
                slotPlan.blendedSpecs(),
                slotPlan.externalSlots(),
                slotPlan.markerExternIndices(),
                pool.finishExterns(),
                slotPlan.computedSlots());
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan compileMarkerWrappedPlan(
            DfcOpenClRuntime.OpenClCompiledPlan plan, int slot, int remainingDepth,
            IdentityHashMap<DensityFunction, Boolean> seen) {
        int[] markerExternIndices = plan.markerExternIndices();
        DensityFunction[] externs = plan.externs();
        if (markerExternIndices == null || externs == null || slot < 0 || slot >= markerExternIndices.length) {
            return null;
        }
        int externIndex = markerExternIndices[slot];
        if (externIndex < 0 || externIndex >= externs.length) {
            return null;
        }
        DensityFunction extern = externs[externIndex];
        if (!(extern instanceof DensityFunctions.MarkerOrMarked marker)) {
            return null;
        }
        DensityFunction wrapped = marker.wrapped();
        if (wrapped == null || seen.put(wrapped, Boolean.TRUE) != null) {
            return null;
        }
        try {
            DfcOpenClRuntime.OpenClCompiledPlan childPlan = null;
            if (wrapped instanceof CompiledDensityFunction compiled) {
                Entry entry = lookup(compiled);
                if (entry.available()) {
                    childPlan = entry.plan();
                }
            } else {
                Compiler.Result result = Compiler.compileWithDetail(wrapped);
                if (result != null) {
                    Entry entry = lookup(result.compiled());
                    if (entry.available()) {
                        childPlan = entry.plan();
                    }
                }
            }
            return expandMarkerSlots(childPlan, remainingDepth - 1, seen);
        } catch (Throwable ignored) {
            return null;
        } finally {
            seen.remove(wrapped);
        }
    }

    private static void appendRemappedChild(DfcOpenClRuntime.OpenClCompiledPlan child, int offset, int externOffset,
                                            List<NoiseSpec> specs,
                                            List<BlendedNoiseSpec> blendedSpecs,
                                            List<Boolean> externalSlots,
                                            List<Integer> markerExternIndices,
                                            List<DfcOpenClRuntime.ComputedSlot> computedSlots,
                                            List<String> coordXExpressions,
                                            List<String> coordYExpressions,
                                            List<String> coordZExpressions,
                                            List<DfcOpenClRuntime.HoistEvaluator> coordXEvaluators,
                                            List<DfcOpenClRuntime.HoistEvaluator> coordYEvaluators,
                                            List<DfcOpenClRuntime.HoistEvaluator> coordZEvaluators) {
        for (int slot = 0; slot < child.specs().length; slot++) {
            specs.add(child.specs()[slot]);
            blendedSpecs.add(arrayValue(child.blendedSpecs(), slot, null));
            boolean external = isExternalSlot(child.externalSlots(), slot);
            externalSlots.add(external);
            markerExternIndices.add(external ? remappedExternIndex(child.markerExternIndices(), slot, externOffset) : -1);
            DfcOpenClRuntime.ComputedSlot computed = computedSlot(child.computedSlots(), slot);
            computedSlots.add(computed == null ? null : new DfcOpenClRuntime.ComputedSlot(
                    remapSlotOperands(computed.slabProgram(), offset),
                    computed.slabConstants(),
                    remapSlotExpression(computed.hoistExpression(), offset),
                    computed.hoistEvaluator(),
                    computed.label()));
            coordXExpressions.add(remapSlotExpression(arrayValue(child.slotCoordXExpressions(), slot, "bx"), offset));
            coordYExpressions.add(remapSlotExpression(arrayValue(child.slotCoordYExpressions(), slot, "by"), offset));
            coordZExpressions.add(remapSlotExpression(arrayValue(child.slotCoordZExpressions(), slot, "bz"), offset));
            coordXEvaluators.add(arrayValue(child.slotCoordXEvaluators(), slot, (bx, by, bz) -> bx));
            coordYEvaluators.add(arrayValue(child.slotCoordYEvaluators(), slot, (bx, by, bz) -> by));
            coordZEvaluators.add(arrayValue(child.slotCoordZEvaluators(), slot, (bx, by, bz) -> bz));
        }
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan buildFullRoot(String labelPrefix, IRNode root,
                                                                    ConstantPool pool, Set<IRNode> extracted) {
        SlabNativeBatchPlan slabPlan = SlabNativeBatchPlan.analyzeFullRoot(
                        root, pool.noiseSpecCount(), pool.blendedNoiseSpecCount())
                .orElseThrow(() -> new IllegalStateException("compiled root has no full-root native slot plan: "
                        + SlabNativeBatchPlan.diagnoseFullRoot(
                        root, pool.noiseSpecCount(), pool.blendedNoiseSpecCount())));
        SlabInnerNativeProgram.Result slabProgram = SlabInnerNativeProgram.tryCompileFull(
                        root, slabPlan, Collections.emptySet())
                .orElseThrow(() -> new IllegalStateException("compiled root has no full-root slab VM program: "
                        + SlabInnerNativeProgram.diagnoseFullRoot(root, slabPlan, Collections.emptySet())));

        SlotPlan slotPlan = collectSlotPlan(slabPlan, pool, true);
        return new DfcOpenClRuntime.OpenClCompiledPlan(
                labelPrefix + "/FULL/slots=" + slotPlan.specs().length
                        + (slabProgram.applyBlendDensity() ? "/blendPass" : ""),
                slotPlan.specs(),
                slabProgram.bytecode(),
                slabProgram.constants(),
                "0.0",
                (bx, by, bz) -> 0.0D,
                slotPlan.coordXExpressions(),
                slotPlan.coordYExpressions(),
                slotPlan.coordZExpressions(),
                slotPlan.coordXEvaluators(),
                slotPlan.coordYEvaluators(),
                slotPlan.coordZEvaluators(),
                slotPlan.blendedSpecs(),
                slotPlan.externalSlots(),
                slotPlan.markerExternIndices(),
                pool.finishExterns(),
                slotPlan.computedSlots());
    }

    private static SlotPlan collectSlotPlan(SlabNativeBatchPlan slabPlan, ConstantPool pool,
                                            boolean allowMarkerSlots) {
        NoiseSpec[] specs = new NoiseSpec[slabPlan.slots().size()];
        BlendedNoiseSpec[] blendedSpecs = new BlendedNoiseSpec[specs.length];
        boolean[] externalSlots = new boolean[specs.length];
        int[] markerExternIndices = new int[specs.length];
        DfcOpenClRuntime.ComputedSlot[] computedSlots = new DfcOpenClRuntime.ComputedSlot[specs.length];
        java.util.Arrays.fill(markerExternIndices, -1);
        String[] coordXExpressions = new String[specs.length];
        String[] coordYExpressions = new String[specs.length];
        String[] coordZExpressions = new String[specs.length];
        DfcOpenClRuntime.HoistEvaluator[] coordXEvaluators = new DfcOpenClRuntime.HoistEvaluator[specs.length];
        DfcOpenClRuntime.HoistEvaluator[] coordYEvaluators = new DfcOpenClRuntime.HoistEvaluator[specs.length];
        DfcOpenClRuntime.HoistEvaluator[] coordZEvaluators = new DfcOpenClRuntime.HoistEvaluator[specs.length];
        IdentityHashMap<IRNode, Integer> slotIndices = new IdentityHashMap<>();
        SlabNativeBatchPlan.NormalSlot[] normalSlots = new SlabNativeBatchPlan.NormalSlot[specs.length];
        SlabNativeBatchPlan.BlendedSlot[] blendedSlots = new SlabNativeBatchPlan.BlendedSlot[specs.length];
        SlabNativeBatchPlan.MarkerSlot[] markerSlots = new SlabNativeBatchPlan.MarkerSlot[specs.length];
        SlabNativeBatchPlan.ExternalSlot[] externalIrSlots = new SlabNativeBatchPlan.ExternalSlot[specs.length];
        for (SlabNativeBatchPlan.Slot slot : slabPlan.slots()) {
            if (slot instanceof SlabNativeBatchPlan.NormalSlot normalSlot) {
                normalSlots[slot.slotIndex()] = normalSlot;
                slotIndices.put(normalSlot.noise(), slot.slotIndex());
            } else if (slot instanceof SlabNativeBatchPlan.BlendedSlot blendedSlot) {
                blendedSlots[slot.slotIndex()] = blendedSlot;
                slotIndices.put(blendedSlot.noise(), slot.slotIndex());
            } else if (slot instanceof SlabNativeBatchPlan.MarkerSlot markerSlot) {
                markerSlots[slot.slotIndex()] = markerSlot;
                slotIndices.put(markerSlot.marker(), slot.slotIndex());
            } else if (slot instanceof SlabNativeBatchPlan.ExternalSlot externalSlot) {
                externalIrSlots[slot.slotIndex()] = externalSlot;
                slotIndices.put(externalSlot.node(), slot.slotIndex());
            }
        }
        for (SlabNativeBatchPlan.Slot slot : slabPlan.slots()) {
            if (slot instanceof SlabNativeBatchPlan.MarkerSlot markerSlot && allowMarkerSlots) {
                externalSlots[slot.slotIndex()] = true;
                markerExternIndices[slot.slotIndex()] = markerSlot.marker().externIndex();
                coordXExpressions[slot.slotIndex()] = "bx";
                coordYExpressions[slot.slotIndex()] = "by";
                coordZExpressions[slot.slotIndex()] = "bz";
                coordXEvaluators[slot.slotIndex()] = (bx, by, bz) -> bx;
                coordYEvaluators[slot.slotIndex()] = (bx, by, bz) -> by;
                coordZEvaluators[slot.slotIndex()] = (bx, by, bz) -> bz;
                continue;
            }
            if (slot instanceof SlabNativeBatchPlan.ExternalSlot externalSlot) {
                externalSlots[slot.slotIndex()] = true;
                markerExternIndices[slot.slotIndex()] = externalSlot.externIndex();
                coordXExpressions[slot.slotIndex()] = "bx";
                coordYExpressions[slot.slotIndex()] = "by";
                coordZExpressions[slot.slotIndex()] = "bz";
                coordXEvaluators[slot.slotIndex()] = (bx, by, bz) -> bx;
                coordYEvaluators[slot.slotIndex()] = (bx, by, bz) -> by;
                coordZEvaluators[slot.slotIndex()] = (bx, by, bz) -> bz;
                continue;
            }
            if (slot instanceof SlabNativeBatchPlan.BlendedSlot blendedSlot) {
                int specIndex = blendedSlot.noise().blendedSpecIndex();
                if (specIndex < 0 || specIndex >= pool.blendedNoiseSpecCount()) {
                    throw new IllegalStateException("compiled root slab slot " + slot.slotIndex()
                            + " has invalid blended noise spec index " + specIndex);
                }
                blendedSpecs[slot.slotIndex()] = pool.blendedNoiseSpec(specIndex);
                coordXExpressions[slot.slotIndex()] = "bx";
                coordYExpressions[slot.slotIndex()] = "by";
                coordZExpressions[slot.slotIndex()] = "bz";
                coordXEvaluators[slot.slotIndex()] = (bx, by, bz) -> bx;
                coordYEvaluators[slot.slotIndex()] = (bx, by, bz) -> by;
                coordZEvaluators[slot.slotIndex()] = (bx, by, bz) -> bz;
                continue;
            }
            if (!(slot instanceof SlabNativeBatchPlan.NormalSlot normalSlot)) {
                throw new IllegalStateException("compiled root slab slot " + slot.slotIndex()
                        + " is " + slot.getClass().getSimpleName()
                        + ", only normal/blended noise slots are supported");
            }
            IRNode.InlinedNoise noise = normalSlot.noise();
            int specIndex = normalSlot.noise().specPoolIndex();
            if (specIndex < 0 || specIndex >= pool.noiseSpecCount()) {
                throw new IllegalStateException("compiled root slab slot " + slot.slotIndex()
                        + " has invalid noise spec index " + specIndex);
            }
            specs[slot.slotIndex()] = pool.noiseSpec(specIndex);
            coordXExpressions[slot.slotIndex()] = openClCoordinateExpression(
                    noise.coordX(), slotIndices, slot.slotIndex());
            coordYExpressions[slot.slotIndex()] = openClCoordinateExpression(
                    noise.coordY(), slotIndices, slot.slotIndex());
            coordZExpressions[slot.slotIndex()] = openClCoordinateExpression(
                    noise.coordZ(), slotIndices, slot.slotIndex());
            coordXEvaluators[slot.slotIndex()] = coordinateEvaluator(
                    noise.coordX(), pool, normalSlots, blendedSlots, markerSlots, externalIrSlots, slotIndices);
            coordYEvaluators[slot.slotIndex()] = coordinateEvaluator(
                    noise.coordY(), pool, normalSlots, blendedSlots, markerSlots, externalIrSlots, slotIndices);
            coordZEvaluators[slot.slotIndex()] = coordinateEvaluator(
                    noise.coordZ(), pool, normalSlots, blendedSlots, markerSlots, externalIrSlots, slotIndices);
        }
        for (int i = 0; i < specs.length; i++) {
            if (!externalSlots[i] && specs[i] == null && blendedSpecs[i] == null) {
                throw new IllegalStateException("compiled root slab slot " + i + " is missing");
            }
            if (coordXExpressions[i] == null || coordYExpressions[i] == null || coordZExpressions[i] == null) {
                throw new IllegalStateException("compiled root slab slot " + i + " is missing coordinate source");
            }
        }
        return new SlotPlan(specs, blendedSpecs, externalSlots, markerExternIndices, computedSlots,
                coordXExpressions, coordYExpressions, coordZExpressions,
                coordXEvaluators, coordYEvaluators, coordZEvaluators);
    }

    private static DfcOpenClRuntime.HoistEvaluator evaluator(IRNode node) {
        return (bx, by, bz) -> evalOpenClExpression(node, bx, by, bz);
    }

    private static DfcOpenClRuntime.HoistEvaluator coordinateEvaluator(
            IRNode node, ConstantPool pool,
            SlabNativeBatchPlan.NormalSlot[] normalSlots,
            SlabNativeBatchPlan.BlendedSlot[] blendedSlots,
            SlabNativeBatchPlan.MarkerSlot[] markerSlots,
            SlabNativeBatchPlan.ExternalSlot[] externalIrSlots,
            IdentityHashMap<IRNode, Integer> slotIndices) {
        return (bx, by, bz) -> evalCoordinateExpression(node, bx, by, bz, pool, normalSlots, blendedSlots,
                markerSlots, externalIrSlots, slotIndices,
                new double[normalSlots.length], new boolean[normalSlots.length]);
    }

    private static String openClCoordinateExpression(IRNode node,
                                                     IdentityHashMap<IRNode, Integer> slotIndices,
                                                     int currentSlot) {
        Integer slot = slotIndices.get(node);
        if ((node instanceof IRNode.InlinedNoise
                || node instanceof IRNode.InlinedBlendedNoise
                || node instanceof IRNode.Marker
                || node instanceof IRNode.Invoke
                || node instanceof IRNode.Beardifier
                || node instanceof IRNode.EndIslands) && slot != null) {
            if (slot >= currentSlot) {
                throw new UnsupportedOperationException("OpenCL coordinate slot " + currentSlot
                        + " depends on slot " + slot + " before it is emitted");
            }
            return "slot" + slot;
        }
        if (node instanceof IRNode.Const c) {
            return openClDouble(c.value());
        }
        if (node instanceof IRNode.BlockX) {
            return "bx";
        }
        if (node instanceof IRNode.BlockY) {
            return "by";
        }
        if (node instanceof IRNode.BlockZ) {
            return "bz";
        }
        if (node instanceof IRNode.YClampedGradient g) {
            return "dfc_clamped_map(by, " + openClDouble(g.fromY()) + ", " + openClDouble(g.toY())
                    + ", " + openClDouble(g.fromValue()) + ", " + openClDouble(g.toValue()) + ")";
        }
        if (node instanceof IRNode.Bin bin) {
            String left = openClCoordinateExpression(bin.left(), slotIndices, currentSlot);
            String right = openClCoordinateExpression(bin.right(), slotIndices, currentSlot);
            return switch (bin.op()) {
                case ADD -> "(" + left + " + " + right + ")";
                case SUB -> "(" + left + " - " + right + ")";
                case MUL -> "(" + left + " * " + right + ")";
                case DIV -> "(" + left + " / " + right + ")";
                case MIN -> "dfc_java_min(" + left + ", " + right + ")";
                case MAX -> "dfc_java_max(" + left + ", " + right + ")";
            };
        }
        if (node instanceof IRNode.Unary unary) {
            String input = openClCoordinateExpression(unary.input(), slotIndices, currentSlot);
            return switch (unary.op()) {
                case ABS -> "fabs(" + input + ")";
                case NEG -> "(-" + input + ")";
                case SQUARE -> "(" + input + " * " + input + ")";
                case CUBE -> "(" + input + " * " + input + " * " + input + ")";
                case HALF_NEGATIVE -> "((" + input + ") > 0.0 ? (" + input + ") : (" + input + ") * 0.5)";
                case QUARTER_NEGATIVE -> "((" + input + ") > 0.0 ? (" + input + ") : (" + input + ") * 0.25)";
                case SQUEEZE -> "dfc_squeeze(" + input + ")";
            };
        }
        if (node instanceof IRNode.Clamp clamp) {
            String input = openClCoordinateExpression(clamp.input(), slotIndices, currentSlot);
            return "dfc_java_max(" + openClDouble(clamp.min()) + ", dfc_java_min("
                    + openClDouble(clamp.max()) + ", " + input + "))";
        }
        if (node instanceof IRNode.RangeChoice range) {
            String input = openClCoordinateExpression(range.input(), slotIndices, currentSlot);
            return "((" + input + ") >= " + openClDouble(range.min())
                    + " && (" + input + ") < " + openClDouble(range.max())
                    + " ? (" + openClCoordinateExpression(range.whenInRange(), slotIndices, currentSlot)
                    + ") : (" + openClCoordinateExpression(range.whenOutOfRange(), slotIndices, currentSlot) + "))";
        }
        if (node instanceof IRNode.WeirdRarity rarity) {
            return openClWeirdRarityExpression(
                    openClCoordinateExpression(rarity.input(), slotIndices, currentSlot),
                    rarity.rarityValueMapperOrdinal());
        }
        if (node instanceof IRNode.Spline.Constant constant) {
            return openClDouble(constant.value());
        }
        if (node instanceof IRNode.Spline.Multipoint multipoint) {
            return openClSplineExpression(multipoint, slotIndices, currentSlot);
        }
        throw new UnsupportedOperationException("OpenCL coordinate source does not support "
                + node.getClass().getSimpleName());
    }

    private static String openClExpression(IRNode node) {
        if (node instanceof IRNode.Const c) {
            return openClDouble(c.value());
        }
        if (node instanceof IRNode.BlockX) {
            return "bx";
        }
        if (node instanceof IRNode.BlockY) {
            return "by";
        }
        if (node instanceof IRNode.BlockZ) {
            return "bz";
        }
        if (node instanceof IRNode.YClampedGradient g) {
            return "dfc_clamped_map(by, " + openClDouble(g.fromY()) + ", " + openClDouble(g.toY())
                    + ", " + openClDouble(g.fromValue()) + ", " + openClDouble(g.toValue()) + ")";
        }
        if (node instanceof IRNode.Bin bin) {
            String left = openClExpression(bin.left());
            String right = openClExpression(bin.right());
            return switch (bin.op()) {
                case ADD -> "(" + left + " + " + right + ")";
                case SUB -> "(" + left + " - " + right + ")";
                case MUL -> "(" + left + " * " + right + ")";
                case DIV -> "(" + left + " / " + right + ")";
                case MIN -> "dfc_java_min(" + left + ", " + right + ")";
                case MAX -> "dfc_java_max(" + left + ", " + right + ")";
            };
        }
        if (node instanceof IRNode.Unary unary) {
            String input = openClExpression(unary.input());
            return switch (unary.op()) {
                case ABS -> "fabs(" + input + ")";
                case NEG -> "(-" + input + ")";
                case SQUARE -> "(" + input + " * " + input + ")";
                case CUBE -> "(" + input + " * " + input + " * " + input + ")";
                case HALF_NEGATIVE -> "((" + input + ") > 0.0 ? (" + input + ") : (" + input + ") * 0.5)";
                case QUARTER_NEGATIVE -> "((" + input + ") > 0.0 ? (" + input + ") : (" + input + ") * 0.25)";
                case SQUEEZE -> "dfc_squeeze(" + input + ")";
            };
        }
        if (node instanceof IRNode.Clamp clamp) {
            String input = openClExpression(clamp.input());
            return "dfc_java_max(" + openClDouble(clamp.min()) + ", dfc_java_min("
                    + openClDouble(clamp.max()) + ", " + input + "))";
        }
        if (node instanceof IRNode.RangeChoice range) {
            String input = openClExpression(range.input());
            return "((" + input + ") >= " + openClDouble(range.min())
                    + " && (" + input + ") < " + openClDouble(range.max())
                    + " ? (" + openClExpression(range.whenInRange()) + ") : ("
                    + openClExpression(range.whenOutOfRange()) + "))";
        }
        if (node instanceof IRNode.WeirdRarity rarity) {
            return openClWeirdRarityExpression(openClExpression(rarity.input()), rarity.rarityValueMapperOrdinal());
        }
        if (node instanceof IRNode.Spline.Constant constant) {
            return openClDouble(constant.value());
        }
        if (node instanceof IRNode.Spline.Multipoint multipoint) {
            return openClSplineExpression(multipoint, null, 0);
        }
        throw new UnsupportedOperationException("OpenCL hoist source does not support "
                + node.getClass().getSimpleName());
    }

    private static String openClSplineExpression(IRNode.Spline spline,
                                                 IdentityHashMap<IRNode, Integer> slotIndices,
                                                 int currentSlot) {
        if (spline instanceof IRNode.Spline.Constant constant) {
            return openClDouble(constant.value());
        }
        IRNode.Spline.Multipoint multipoint = (IRNode.Spline.Multipoint) spline;
        float[] locations = multipoint.locations();
        float[] derivatives = multipoint.derivatives();
        List<IRNode.Spline> values = multipoint.values();
        int n = locations.length;
        if (n <= 0 || derivatives.length != n || values.size() != n) {
            throw new IllegalArgumentException("invalid spline multipoint");
        }
        String coord = "(" + openClChildExpression(multipoint.coordinate(), slotIndices, currentSlot) + ")";
        if (n == 1) {
            return openClSplineLinearExtension(multipoint, coord, 0, slotIndices, currentSlot);
        }
        String expression = openClSplineLinearExtension(multipoint, coord, n - 1, slotIndices, currentSlot);
        for (int segment = n - 2; segment >= 0; segment--) {
            expression = "(" + coord + " < " + openClDouble(locations[segment + 1])
                    + " ? (" + openClSplineInterpolatedSegment(
                    multipoint, coord, segment, slotIndices, currentSlot)
                    + ") : (" + expression + "))";
        }
        return "(" + coord + " < " + openClDouble(locations[0])
                + " ? (" + openClSplineLinearExtension(multipoint, coord, 0, slotIndices, currentSlot)
                + ") : (" + expression + "))";
    }

    private static String openClChildExpression(IRNode node,
                                                IdentityHashMap<IRNode, Integer> slotIndices,
                                                int currentSlot) {
        return slotIndices == null
                ? openClExpression(node)
                : openClCoordinateExpression(node, slotIndices, currentSlot);
    }

    private static String openClSplineLinearExtension(IRNode.Spline.Multipoint multipoint, String coord, int index,
                                                      IdentityHashMap<IRNode, Integer> slotIndices,
                                                      int currentSlot) {
        String value = openClSplineExpression(multipoint.values().get(index), slotIndices, currentSlot);
        float derivative = multipoint.derivatives()[index];
        if (derivative == 0.0F) {
            return value;
        }
        return "(" + value + " + (" + coord + " - " + openClDouble(multipoint.locations()[index])
                + ") * " + openClDouble(derivative) + ")";
    }

    private static String openClSplineInterpolatedSegment(IRNode.Spline.Multipoint multipoint, String coord,
                                                          int index,
                                                          IdentityHashMap<IRNode, Integer> slotIndices,
                                                          int currentSlot) {
        float l0 = multipoint.locations()[index];
        float l1 = multipoint.locations()[index + 1];
        float d0 = multipoint.derivatives()[index];
        float d1 = multipoint.derivatives()[index + 1];
        String y0 = openClSplineExpression(multipoint.values().get(index), slotIndices, currentSlot);
        String y1 = openClSplineExpression(multipoint.values().get(index + 1), slotIndices, currentSlot);
        String t = "((" + coord + " - " + openClDouble(l0) + ") / " + openClDouble(l1 - l0) + ")";
        String f8 = "(" + openClDouble(d0 * (l1 - l0)) + " - ((" + y1 + ") - (" + y0 + ")))";
        String f9 = "(" + openClDouble(-d1 * (l1 - l0)) + " + ((" + y1 + ") - (" + y0 + ")))";
        return "((" + y0 + ") + (" + t + ") * ((" + y1 + ") - (" + y0 + "))"
                + " + (" + t + ") * (1.0 - (" + t + ")) * ((" + f8 + ") + (" + t + ") * (("
                + f9 + ") - (" + f8 + "))))";
    }

    private static String openClWeirdRarityExpression(String input, int ordinal) {
        if (ordinal == 0) {
            return "((" + input + ") >= " + openClDouble(-Double.MAX_VALUE) + " && (" + input + ") < -0.5 ? 0.75"
                    + " : ((" + input + ") >= -0.5 && (" + input + ") < 0.0 ? 1.0"
                    + " : ((" + input + ") >= 0.0 && (" + input + ") < 0.5 ? 1.5 : 2.0)))";
        }
        return "((" + input + ") >= " + openClDouble(-Double.MAX_VALUE) + " && (" + input + ") < -0.75 ? 0.5"
                + " : ((" + input + ") >= -0.75 && (" + input + ") < -0.5 ? 0.75"
                + " : ((" + input + ") >= -0.5 && (" + input + ") < 0.5 ? 1.0"
                + " : ((" + input + ") >= 0.5 && (" + input + ") < 0.75 ? 2.0 : 3.0))))";
    }

    private static double evalOpenClExpression(IRNode node, double bx, double by, double bz) {
        if (node instanceof IRNode.Const c) {
            return c.value();
        }
        if (node instanceof IRNode.BlockX) {
            return bx;
        }
        if (node instanceof IRNode.BlockY) {
            return by;
        }
        if (node instanceof IRNode.BlockZ) {
            return bz;
        }
        if (node instanceof IRNode.YClampedGradient g) {
            return clampedMap(by, g.fromY(), g.toY(), g.fromValue(), g.toValue());
        }
        if (node instanceof IRNode.Bin bin) {
            double left = evalOpenClExpression(bin.left(), bx, by, bz);
            double right = evalOpenClExpression(bin.right(), bx, by, bz);
            return switch (bin.op()) {
                case ADD -> left + right;
                case SUB -> left - right;
                case MUL -> left * right;
                case DIV -> left / right;
                case MIN -> Math.min(left, right);
                case MAX -> Math.max(left, right);
            };
        }
        if (node instanceof IRNode.Unary unary) {
            double input = evalOpenClExpression(unary.input(), bx, by, bz);
            return switch (unary.op()) {
                case ABS -> Math.abs(input);
                case NEG -> -input;
                case SQUARE -> input * input;
                case CUBE -> input * input * input;
                case HALF_NEGATIVE -> input > 0.0D ? input : input * 0.5D;
                case QUARTER_NEGATIVE -> input > 0.0D ? input : input * 0.25D;
                case SQUEEZE -> squeeze(input);
            };
        }
        if (node instanceof IRNode.Clamp clamp) {
            return Math.max(clamp.min(), Math.min(clamp.max(),
                    evalOpenClExpression(clamp.input(), bx, by, bz)));
        }
        if (node instanceof IRNode.RangeChoice range) {
            double input = evalOpenClExpression(range.input(), bx, by, bz);
            return input >= range.min() && input < range.max()
                    ? evalOpenClExpression(range.whenInRange(), bx, by, bz)
                    : evalOpenClExpression(range.whenOutOfRange(), bx, by, bz);
        }
        if (node instanceof IRNode.WeirdRarity rarity) {
            return evalWeirdRarity(evalOpenClExpression(rarity.input(), bx, by, bz),
                    rarity.rarityValueMapperOrdinal());
        }
        if (node instanceof IRNode.Spline.Constant constant) {
            return constant.value();
        }
        if (node instanceof IRNode.Spline.Multipoint multipoint) {
            return evalOpenClSplineExpression(multipoint, bx, by, bz);
        }
        throw new UnsupportedOperationException("OpenCL hoist evaluator does not support "
                + node.getClass().getSimpleName());
    }

    private static double evalCoordinateExpression(
            IRNode node, double bx, double by, double bz, ConstantPool pool,
            SlabNativeBatchPlan.NormalSlot[] normalSlots,
            SlabNativeBatchPlan.BlendedSlot[] blendedSlots,
            SlabNativeBatchPlan.MarkerSlot[] markerSlots,
            SlabNativeBatchPlan.ExternalSlot[] externalIrSlots,
            IdentityHashMap<IRNode, Integer> slotIndices,
            double[] slotValues,
            boolean[] resolvedSlots) {
        Integer slot = slotIndices.get(node);
        if ((node instanceof IRNode.InlinedNoise
                || node instanceof IRNode.InlinedBlendedNoise
                || node instanceof IRNode.Marker
                || node instanceof IRNode.Invoke
                || node instanceof IRNode.Beardifier
                || node instanceof IRNode.EndIslands) && slot != null) {
            return evalPlannedSlot(slot, bx, by, bz, pool, normalSlots, blendedSlots, markerSlots, externalIrSlots, slotIndices,
                    slotValues, resolvedSlots);
        }
        if (node instanceof IRNode.Const c) {
            return c.value();
        }
        if (node instanceof IRNode.BlockX) {
            return bx;
        }
        if (node instanceof IRNode.BlockY) {
            return by;
        }
        if (node instanceof IRNode.BlockZ) {
            return bz;
        }
        if (node instanceof IRNode.YClampedGradient g) {
            return clampedMap(by, g.fromY(), g.toY(), g.fromValue(), g.toValue());
        }
        if (node instanceof IRNode.Bin bin) {
            double left = evalCoordinateExpression(bin.left(), bx, by, bz, pool, normalSlots, blendedSlots, markerSlots, externalIrSlots,
                    slotIndices, slotValues, resolvedSlots);
            double right = evalCoordinateExpression(bin.right(), bx, by, bz, pool, normalSlots, blendedSlots, markerSlots, externalIrSlots,
                    slotIndices, slotValues, resolvedSlots);
            return switch (bin.op()) {
                case ADD -> left + right;
                case SUB -> left - right;
                case MUL -> left * right;
                case DIV -> left / right;
                case MIN -> Math.min(left, right);
                case MAX -> Math.max(left, right);
            };
        }
        if (node instanceof IRNode.Unary unary) {
            double input = evalCoordinateExpression(unary.input(), bx, by, bz, pool, normalSlots, blendedSlots, markerSlots, externalIrSlots,
                    slotIndices, slotValues, resolvedSlots);
            return switch (unary.op()) {
                case ABS -> Math.abs(input);
                case NEG -> -input;
                case SQUARE -> input * input;
                case CUBE -> input * input * input;
                case HALF_NEGATIVE -> input > 0.0D ? input : input * 0.5D;
                case QUARTER_NEGATIVE -> input > 0.0D ? input : input * 0.25D;
                case SQUEEZE -> squeeze(input);
            };
        }
        if (node instanceof IRNode.Clamp clamp) {
            return Math.max(clamp.min(), Math.min(clamp.max(),
                    evalCoordinateExpression(clamp.input(), bx, by, bz, pool, normalSlots, blendedSlots, markerSlots, externalIrSlots,
                            slotIndices, slotValues, resolvedSlots)));
        }
        if (node instanceof IRNode.RangeChoice range) {
            double input = evalCoordinateExpression(range.input(), bx, by, bz, pool, normalSlots, blendedSlots, markerSlots, externalIrSlots,
                    slotIndices, slotValues, resolvedSlots);
            return input >= range.min() && input < range.max()
                    ? evalCoordinateExpression(range.whenInRange(), bx, by, bz, pool, normalSlots, blendedSlots, markerSlots, externalIrSlots,
                    slotIndices, slotValues, resolvedSlots)
                    : evalCoordinateExpression(range.whenOutOfRange(), bx, by, bz, pool, normalSlots, blendedSlots, markerSlots, externalIrSlots,
                    slotIndices, slotValues, resolvedSlots);
        }
        if (node instanceof IRNode.WeirdRarity rarity) {
            return evalWeirdRarity(evalCoordinateExpression(rarity.input(), bx, by, bz, pool, normalSlots,
                    blendedSlots, markerSlots, externalIrSlots, slotIndices, slotValues, resolvedSlots),
                    rarity.rarityValueMapperOrdinal());
        }
        if (node instanceof IRNode.Spline.Constant constant) {
            return constant.value();
        }
        if (node instanceof IRNode.Spline.Multipoint multipoint) {
            return evalCoordinateSplineExpression(
                    multipoint, bx, by, bz, pool, normalSlots, blendedSlots, markerSlots, externalIrSlots,
                    slotIndices, slotValues, resolvedSlots);
        }
        throw new UnsupportedOperationException("OpenCL coordinate evaluator does not support "
                + node.getClass().getSimpleName());
    }

    private static double evalOpenClSplineExpression(IRNode.Spline spline, double bx, double by, double bz) {
        if (spline instanceof IRNode.Spline.Constant constant) {
            return constant.value();
        }
        IRNode.Spline.Multipoint multipoint = (IRNode.Spline.Multipoint) spline;
        return evalSplineAt(multipoint, evalOpenClExpression(multipoint.coordinate(), bx, by, bz),
                value -> evalOpenClSplineExpression(value, bx, by, bz));
    }

    private static double evalCoordinateSplineExpression(
            IRNode.Spline spline, double bx, double by, double bz, ConstantPool pool,
            SlabNativeBatchPlan.NormalSlot[] normalSlots,
            SlabNativeBatchPlan.BlendedSlot[] blendedSlots,
            SlabNativeBatchPlan.MarkerSlot[] markerSlots,
            SlabNativeBatchPlan.ExternalSlot[] externalIrSlots,
            IdentityHashMap<IRNode, Integer> slotIndices,
            double[] slotValues,
            boolean[] resolvedSlots) {
        if (spline instanceof IRNode.Spline.Constant constant) {
            return constant.value();
        }
        IRNode.Spline.Multipoint multipoint = (IRNode.Spline.Multipoint) spline;
        double coordinate = evalCoordinateExpression(multipoint.coordinate(), bx, by, bz, pool, normalSlots,
                blendedSlots, markerSlots, externalIrSlots, slotIndices, slotValues, resolvedSlots);
        return evalSplineAt(multipoint, coordinate, value -> evalCoordinateSplineExpression(
                value, bx, by, bz, pool, normalSlots, blendedSlots, markerSlots, externalIrSlots,
                slotIndices, slotValues, resolvedSlots));
    }

    private static double evalSplineAt(IRNode.Spline.Multipoint multipoint, double coordinate,
                                       java.util.function.ToDoubleFunction<IRNode.Spline> evaluator) {
        float[] locations = multipoint.locations();
        int n = locations.length;
        if (n <= 0 || multipoint.derivatives().length != n || multipoint.values().size() != n) {
            throw new IllegalArgumentException("invalid spline multipoint");
        }
        if (n == 1 || coordinate < locations[0]) {
            return evalSplineLinearExtension(multipoint, coordinate, 0, evaluator);
        }
        if (coordinate >= locations[n - 1]) {
            return evalSplineLinearExtension(multipoint, coordinate, n - 1, evaluator);
        }
        int segment = 0;
        while (segment + 1 < n && coordinate >= locations[segment + 1]) {
            segment++;
        }
        return evalSplineInterpolatedSegment(multipoint, coordinate, segment, evaluator);
    }

    private static double evalSplineLinearExtension(IRNode.Spline.Multipoint multipoint, double coordinate, int index,
                                                    java.util.function.ToDoubleFunction<IRNode.Spline> evaluator) {
        double value = evaluator.applyAsDouble(multipoint.values().get(index));
        double derivative = multipoint.derivatives()[index];
        return derivative == 0.0D ? value : value + (coordinate - multipoint.locations()[index]) * derivative;
    }

    private static double evalSplineInterpolatedSegment(IRNode.Spline.Multipoint multipoint, double coordinate,
                                                        int index,
                                                        java.util.function.ToDoubleFunction<IRNode.Spline> evaluator) {
        float l0 = multipoint.locations()[index];
        float l1 = multipoint.locations()[index + 1];
        float d0 = multipoint.derivatives()[index];
        float d1 = multipoint.derivatives()[index + 1];
        double t = (coordinate - l0) / (l1 - l0);
        double y0 = evaluator.applyAsDouble(multipoint.values().get(index));
        double y1 = evaluator.applyAsDouble(multipoint.values().get(index + 1));
        double f8 = d0 * (l1 - l0) - (y1 - y0);
        double f9 = -d1 * (l1 - l0) + (y1 - y0);
        return y0 + t * (y1 - y0) + t * (1.0D - t) * (f8 + t * (f9 - f8));
    }

    private static double evalPlannedSlot(
            int slot, double bx, double by, double bz, ConstantPool pool,
            SlabNativeBatchPlan.NormalSlot[] normalSlots,
            SlabNativeBatchPlan.BlendedSlot[] blendedSlots,
            SlabNativeBatchPlan.MarkerSlot[] markerSlots,
            SlabNativeBatchPlan.ExternalSlot[] externalIrSlots,
            IdentityHashMap<IRNode, Integer> slotIndices,
            double[] slotValues,
            boolean[] resolvedSlots) {
        if (slot < 0 || slot >= resolvedSlots.length) {
            throw new IllegalStateException("coordinate expression references missing slot " + slot);
        }
        if (resolvedSlots[slot]) {
            return slotValues[slot];
        }
        if (normalSlots[slot] != null) {
            IRNode.InlinedNoise noise = normalSlots[slot].noise();
            double sx = evalCoordinateExpression(noise.coordX(), bx, by, bz, pool, normalSlots, blendedSlots,
                    markerSlots, externalIrSlots,
                    slotIndices, slotValues, resolvedSlots);
            double sy = evalCoordinateExpression(noise.coordY(), bx, by, bz, pool, normalSlots, blendedSlots,
                    markerSlots, externalIrSlots,
                    slotIndices, slotValues, resolvedSlots);
            double sz = evalCoordinateExpression(noise.coordZ(), bx, by, bz, pool, normalSlots, blendedSlots,
                    markerSlots, externalIrSlots,
                    slotIndices, slotValues, resolvedSlots);
            slotValues[slot] = sampleNoiseSpec(pool.noiseSpec(noise.specPoolIndex()), sx, sy, sz);
        } else if (blendedSlots[slot] != null) {
            slotValues[slot] = sampleBlendedNoiseSpec(
                    pool.blendedNoiseSpec(blendedSlots[slot].noise().blendedSpecIndex()), bx, by, bz);
        } else if (markerSlots[slot] != null) {
            DensityFunction extern = pool.extern(markerSlots[slot].marker().externIndex());
            slotValues[slot] = extern.compute(new DensityFunction.SinglePointContext((int) bx, (int) by, (int) bz));
        } else if (externalIrSlots[slot] != null) {
            DensityFunction extern = pool.extern(externalIrSlots[slot].externIndex());
            slotValues[slot] = extern.compute(new DensityFunction.SinglePointContext((int) bx, (int) by, (int) bz));
        } else {
            throw new UnsupportedOperationException("coordinate expression references external slot " + slot);
        }
        resolvedSlots[slot] = true;
        return slotValues[slot];
    }

    private static double sampleNoiseSpec(NoiseSpec spec, double bx, double by, double bz) {
        return spec.valueFactor() * (samplePerlinSpec(spec.first(), bx, by, bz)
                + samplePerlinSpec(spec.second(), bx, by, bz));
    }

    private static double samplePerlinSpec(NoiseSpec.PerlinSpec spec, double bx, double by, double bz) {
        double value = 0.0D;
        double sx = bx * spec.inputCoordScale();
        double sy = by * spec.inputCoordScale();
        double sz = bz * spec.inputCoordScale();
        ImprovedNoise[] octaves = spec.activeOctaves();
        for (int i = 0; i < octaves.length; i++) {
            value += spec.ampValueFactors()[i] * sampleImprovedNoise(
                    octaves[i],
                    wrapAxis(sx * spec.inputFactors()[i]),
                    wrapAxis(sy * spec.inputFactors()[i]),
                    wrapAxis(sz * spec.inputFactors()[i]),
                    0.0D,
                    0.0D);
        }
        return value;
    }

    private static double sampleBlendedNoiseSpec(BlendedNoiseSpec spec, double bx, double by, double bz) {
        double x = bx * spec.xzMultiplier();
        double y = by * spec.yMultiplier();
        double z = bz * spec.xzMultiplier();
        double mainX = x / spec.xzFactor();
        double mainY = y / spec.yFactor();
        double mainZ = z / spec.xzFactor();
        double smearY = spec.yMultiplier() * spec.smearScaleMultiplier();
        double mainYScale = smearY / spec.yFactor();

        double main = 0.0D;
        for (int octave = 0; octave < BlendedNoiseSpec.MAIN_OCTAVES; octave++) {
            ImprovedNoise noise = spec.mainOctaves()[octave];
            if (noise != null) {
                double scale = 1.0D / (1L << octave);
                main += sampleImprovedNoise(noise,
                        wrapAxis(mainX * scale),
                        wrapAxis(mainY * scale),
                        wrapAxis(mainZ * scale),
                        mainYScale * scale,
                        mainY * scale) / scale;
            }
        }

        double blend = (main / 10.0D + 1.0D) / 2.0D;
        boolean skipMin = blend >= 1.0D;
        boolean skipMax = blend <= 0.0D;
        double min = 0.0D;
        double max = 0.0D;
        for (int octave = 0; octave < BlendedNoiseSpec.LIMIT_OCTAVES; octave++) {
            double scale = 1.0D / (1L << octave);
            double sx = wrapAxis(x * scale);
            double sy = wrapAxis(y * scale);
            double sz = wrapAxis(z * scale);
            double yScale = smearY * scale;
            double yMax = y * scale;
            if (!skipMin && spec.minLimitOctaves()[octave] != null) {
                min += sampleImprovedNoise(spec.minLimitOctaves()[octave],
                        sx, sy, sz, yScale, yMax) / scale;
            }
            if (!skipMax && spec.maxLimitOctaves()[octave] != null) {
                max += sampleImprovedNoise(spec.maxLimitOctaves()[octave],
                        sx, sy, sz, yScale, yMax) / scale;
            }
        }
        return clampedLerp(min / 512.0D, max / 512.0D, blend) / 128.0D;
    }

    private static double sampleImprovedNoise(ImprovedNoise noise, double x, double y, double z,
                                              double yScale, double yMax) {
        ImprovedNoiseAccessor acc = (ImprovedNoiseAccessor) (Object) noise;
        double inputX = x + acc.dfc$getXo();
        double inputY = y + acc.dfc$getYo();
        double inputZ = z + acc.dfc$getZo();
        int gridX = javaFloor(inputX);
        int gridY = javaFloor(inputY);
        int gridZ = javaFloor(inputZ);
        double deltaX = inputX - gridX;
        double deltaY = inputY - gridY;
        double deltaZ = inputZ - gridZ;
        double shiftedDeltaY = deltaY;
        if (yScale != 0.0D) {
            double maxShift = yMax >= 0.0D && yMax < deltaY ? yMax : deltaY;
            shiftedDeltaY = deltaY - Math.floor(maxShift / yScale + 1.0E-7D) * yScale;
        }
        double x1 = deltaX - 1.0D;
        double y1 = shiftedDeltaY - 1.0D;
        double z1 = deltaZ - 1.0D;
        byte[] permutations = acc.dfc$getPermutation();

        double n000 = perlinGrad(permutations, gridX, gridY, gridZ, deltaX, shiftedDeltaY, deltaZ);
        double n100 = perlinGrad(permutations, gridX + 1, gridY, gridZ, x1, shiftedDeltaY, deltaZ);
        double n010 = perlinGrad(permutations, gridX, gridY + 1, gridZ, deltaX, y1, deltaZ);
        double n110 = perlinGrad(permutations, gridX + 1, gridY + 1, gridZ, x1, y1, deltaZ);
        double n001 = perlinGrad(permutations, gridX, gridY, gridZ + 1, deltaX, shiftedDeltaY, z1);
        double n101 = perlinGrad(permutations, gridX + 1, gridY, gridZ + 1, x1, shiftedDeltaY, z1);
        double n011 = perlinGrad(permutations, gridX, gridY + 1, gridZ + 1, deltaX, y1, z1);
        double n111 = perlinGrad(permutations, gridX + 1, gridY + 1, gridZ + 1, x1, y1, z1);

        return lerp3(perlinFade(deltaX), perlinFade(deltaY), perlinFade(deltaZ),
                n000, n100, n010, n110, n001, n101, n011, n111);
    }

    private static double perlinGrad(byte[] permutations, int px, int py, int pz, double fx, double fy, double fz) {
        int hash = perm(permutations, perm(permutations, perm(permutations, px) + py) + pz) & 15;
        int grad = hash << 2;
        return FLAT_SIMPLEX_GRAD[grad] * fx
                + FLAT_SIMPLEX_GRAD[grad | 1] * fy
                + FLAT_SIMPLEX_GRAD[grad | 2] * fz;
    }

    private static int perm(byte[] permutations, int index) {
        return permutations[index & 255] & 0xFF;
    }

    private static double perlinFade(double value) {
        return value * value * value * (value * (value * 6.0D - 15.0D) + 10.0D);
    }

    private static double lerp3(double dx, double dy, double dz,
                                double x0y0z0, double x1y0z0,
                                double x0y1z0, double x1y1z0,
                                double x0y0z1, double x1y0z1,
                                double x0y1z1, double x1y1z1) {
        double x00 = lerp(dx, x0y0z0, x1y0z0);
        double x10 = lerp(dx, x0y1z0, x1y1z0);
        double x01 = lerp(dx, x0y0z1, x1y0z1);
        double x11 = lerp(dx, x0y1z1, x1y1z1);
        return lerp(dz, lerp(dy, x00, x10), lerp(dy, x01, x11));
    }

    private static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    private static double clampedLerp(double start, double end, double delta) {
        if (delta < 0.0D) {
            return start;
        }
        if (delta > 1.0D) {
            return end;
        }
        return lerp(delta, start, end);
    }

    private static int javaFloor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    private static double wrapAxis(double value) {
        if (value >= -16777216.0D && value < 16777216.0D) {
            return value;
        }
        return value - Math.floor(value / 33554432.0D + 0.5D) * 33554432.0D;
    }

    private static double evalWeirdRarity(double input, int ordinal) {
        if (ordinal == 0) {
            if (input >= -Double.MAX_VALUE && input < -0.5D) return 0.75D;
            if (input >= -0.5D && input < 0.0D) return 1.0D;
            if (input >= 0.0D && input < 0.5D) return 1.5D;
            return 2.0D;
        }
        if (input >= -Double.MAX_VALUE && input < -0.75D) return 0.5D;
        if (input >= -0.75D && input < -0.5D) return 0.75D;
        if (input >= -0.5D && input < 0.5D) return 1.0D;
        if (input >= 0.5D && input < 0.75D) return 2.0D;
        return 3.0D;
    }

    private static double clampedMap(double value, double oldMin, double oldMax, double newMin, double newMax) {
        double delta = (value - oldMin) / (oldMax - oldMin);
        if (delta < 0.0D) {
            return newMin;
        }
        if (delta > 1.0D) {
            return newMax;
        }
        return newMin + delta * (newMax - newMin);
    }

    private static double squeeze(double value) {
        double clamped = Math.max(-1.0D, Math.min(1.0D, value));
        return clamped / 2.0D - clamped * clamped * clamped / 24.0D;
    }

    private static String openClDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite OpenCL literal: " + value);
        }
        return Double.toString(value);
    }

    private static String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        String type = throwable.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    private static byte[] remapSlotOperands(byte[] program, int slotOffset) {
        byte[] remapped = program.clone();
        for (int pc = 0; pc < remapped.length;) {
            int op = remapped[pc++] & 0xFF;
            switch (op) {
                case 1, 3 -> pc += 2;
                case 2 -> {
                    int slot = remapped[pc] & 0xFF;
                    int mapped = slot + slotOffset;
                    if (mapped > 255) {
                        throw new IllegalStateException("compiled OpenCL diagnostic plan has more than 255 slots");
                    }
                    remapped[pc++] = (byte) mapped;
                }
                case 4 -> pc += 8;
                case 5 -> pc += 4;
                case 16, 17, 18, 19, 32, 33, 34, 35, 36, 37, 48, 49, 50, 51 -> {
                }
                default -> throw new IllegalStateException("unsupported compiled plan opcode " + op);
            }
        }
        return remapped;
    }

    private static String remapSlotExpression(String expression, int slotOffset) {
        if (expression == null || slotOffset == 0 || expression.indexOf("slot") < 0) {
            return expression;
        }
        StringBuilder out = new StringBuilder(expression.length() + 16);
        for (int i = 0; i < expression.length();) {
            if (i + 4 <= expression.length()
                    && expression.charAt(i) == 's'
                    && expression.charAt(i + 1) == 'l'
                    && expression.charAt(i + 2) == 'o'
                    && expression.charAt(i + 3) == 't'
                    && i + 4 < expression.length()
                    && Character.isDigit(expression.charAt(i + 4))) {
                int end = i + 4;
                int slot = 0;
                while (end < expression.length() && Character.isDigit(expression.charAt(end))) {
                    slot = slot * 10 + (expression.charAt(end) - '0');
                    end++;
                }
                out.append("slot").append(slot + slotOffset);
                i = end;
            } else {
                out.append(expression.charAt(i++));
            }
        }
        return out.toString();
    }

    private static int externalSlotCount(boolean[] externalSlots) {
        int count = 0;
        if (externalSlots != null) {
            for (boolean externalSlot : externalSlots) {
                if (externalSlot) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isExternalSlot(boolean[] externalSlots, int slot) {
        return externalSlots != null && slot >= 0 && slot < externalSlots.length && externalSlots[slot];
    }

    private static DfcOpenClRuntime.ComputedSlot computedSlot(DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                                              int slot) {
        return computedSlots != null && slot >= 0 && slot < computedSlots.length ? computedSlots[slot] : null;
    }

    private static int remappedExternIndex(int[] markerExternIndices, int slot, int externOffset) {
        if (markerExternIndices == null || slot < 0 || slot >= markerExternIndices.length) {
            throw new IllegalStateException("child external slot " + slot + " has no marker extern index");
        }
        int externIndex = markerExternIndices[slot];
        if (externIndex < 0) {
            throw new IllegalStateException("child external slot " + slot + " has invalid marker extern index "
                    + externIndex);
        }
        return externOffset + externIndex;
    }

    private static List<DensityFunction> densityFunctions(DensityFunction[] values) {
        List<DensityFunction> out = new ArrayList<>(values == null ? 0 : values.length);
        if (values != null) {
            Collections.addAll(out, values);
        }
        return out;
    }

    private static void appendExterns(DensityFunction[] values, List<DensityFunction> out) {
        if (values != null) {
            Collections.addAll(out, values);
        }
    }

    private static List<Boolean> booleans(boolean[] values) {
        List<Boolean> out = new ArrayList<>(values == null ? 0 : values.length);
        if (values != null) {
            for (boolean value : values) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<Integer> ints(int[] values) {
        List<Integer> out = new ArrayList<>(values == null ? 0 : values.length);
        if (values != null) {
            for (int value : values) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<DfcOpenClRuntime.ComputedSlot> computed(DfcOpenClRuntime.ComputedSlot[] values, int length) {
        List<DfcOpenClRuntime.ComputedSlot> out = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            out.add(values != null && i < values.length ? values[i] : null);
        }
        return out;
    }

    private static List<NoiseSpec> noiseSpecs(NoiseSpec[] values) {
        List<NoiseSpec> out = new ArrayList<>(values == null ? 0 : values.length);
        if (values != null) {
            for (NoiseSpec value : values) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<BlendedNoiseSpec> blendedNoiseSpecs(BlendedNoiseSpec[] values, int minLength) {
        int length = values == null ? 0 : values.length;
        List<BlendedNoiseSpec> out = new ArrayList<>(Math.max(length, minLength));
        if (values != null) {
            Collections.addAll(out, values);
        }
        while (out.size() < minLength) {
            out.add(null);
        }
        return out;
    }

    private static List<String> strings(String[] values) {
        List<String> out = new ArrayList<>(values == null ? 0 : values.length);
        if (values != null) {
            Collections.addAll(out, values);
        }
        return out;
    }

    private static List<DfcOpenClRuntime.HoistEvaluator> evaluators(DfcOpenClRuntime.HoistEvaluator[] values) {
        List<DfcOpenClRuntime.HoistEvaluator> out = new ArrayList<>(values == null ? 0 : values.length);
        if (values != null) {
            Collections.addAll(out, values);
        }
        return out;
    }

    private static boolean[] toBooleanArray(List<Boolean> values) {
        boolean[] out = new boolean[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static String arrayValue(String[] values, int index, String fallback) {
        return values != null && index >= 0 && index < values.length && values[index] != null
                ? values[index]
                : fallback;
    }

    private static DfcOpenClRuntime.HoistEvaluator arrayValue(DfcOpenClRuntime.HoistEvaluator[] values, int index,
                                                              DfcOpenClRuntime.HoistEvaluator fallback) {
        return values != null && index >= 0 && index < values.length && values[index] != null
                ? values[index]
                : fallback;
    }

    private static BlendedNoiseSpec arrayValue(BlendedNoiseSpec[] values, int index, BlendedNoiseSpec fallback) {
        return values != null && index >= 0 && index < values.length && values[index] != null
                ? values[index]
                : fallback;
    }

    private record SlotPlan(
            NoiseSpec[] specs,
            BlendedNoiseSpec[] blendedSpecs,
            boolean[] externalSlots,
            int[] markerExternIndices,
            DfcOpenClRuntime.ComputedSlot[] computedSlots,
            String[] coordXExpressions,
            String[] coordYExpressions,
            String[] coordZExpressions,
            DfcOpenClRuntime.HoistEvaluator[] coordXEvaluators,
            DfcOpenClRuntime.HoistEvaluator[] coordYEvaluators,
            DfcOpenClRuntime.HoistEvaluator[] coordZEvaluators) {
    }

    public record Entry(DfcOpenClRuntime.OpenClCompiledPlan plan, String unavailableReason) {
        public static Entry available(DfcOpenClRuntime.OpenClCompiledPlan plan) {
            return new Entry(plan, null);
        }

        public static Entry unavailable(String reason) {
            return new Entry(null, reason);
        }

        public boolean available() {
            return plan != null;
        }
    }
}

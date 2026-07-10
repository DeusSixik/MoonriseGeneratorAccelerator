package dev.sixik.generator_accelerator.common.surface_compiler.validate;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;

import java.util.HashSet;
import java.util.Set;

public final class TranslationValidator {
    private final StateTraceValidator traceValidator = new StateTraceValidator();

    public boolean validate(SurfaceProgramIr before, SurfaceProgramIr after) {
        if (before == null || after == null) {
            return false;
        }
        if (before.root().kind() != after.root().kind()) {
            return false;
        }
        return this.traceValidator.sameStateTrace(before, after)
                && preservesOrderedOps(before, after)
                && onlyRemovesDuplicateReorderableOps(before, after);
    }

    private static boolean preservesOrderedOps(SurfaceProgramIr before, SurfaceProgramIr after) {
        return before.ops().stream().filter(SurfaceOp::isStateful).toList()
                .equals(after.ops().stream().filter(SurfaceOp::isStateful).toList());
    }

    private static boolean onlyRemovesDuplicateReorderableOps(SurfaceProgramIr before, SurfaceProgramIr after) {
        int cursor = 0;
        Set<String> removedReorderable = new HashSet<>();
        for (SurfaceOp beforeOp : before.ops()) {
            if (cursor < after.ops().size() && beforeOp.equals(after.ops().get(cursor))) {
                cursor++;
                continue;
            }
            if (!isReorderableValue(beforeOp)) {
                return false;
            }
            String key = reorderableKey(beforeOp);
            if (!removedReorderable.add(key)) {
                continue;
            }
            if (after.ops().stream().noneMatch(op -> op.equals(beforeOp))) {
                return false;
            }
        }
        return cursor == after.ops().size();
    }

    private static boolean isReorderableValue(SurfaceOp op) {
        return !op.isStateful() && (op.effect() == SurfaceEffect.PURE || op.effect() == SurfaceEffect.READ_ONLY_STABLE);
    }

    private static String reorderableKey(SurfaceOp op) {
        return op.effect() + "|" + op.opcode() + '|' + op.domain() + '|' + op.detail();
    }
}

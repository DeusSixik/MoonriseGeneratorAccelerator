package dev.sixik.generator_accelerator.common.surface_compiler.validate;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceStateToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class StateTraceValidator {
    public boolean validateTokenChain(SurfaceProgramIr ir) {
        if (ir == null || !ir.tokenChainIsLinear()) {
            return false;
        }

        SurfaceStateToken previousOut = null;
        for (SurfaceOp op : ir.ops()) {
            if (!op.isStateful()) {
                continue;
            }
            if (op.stateIn() == null || op.stateOut() == null) {
                return false;
            }
            if (op.stateOut().ordinal() != op.stateIn().ordinal() + 1) {
                return false;
            }
            if (previousOut != null && !previousOut.equals(op.stateIn())) {
                return false;
            }
            previousOut = op.stateOut();
        }
        return true;
    }

    public boolean sameStateTrace(SurfaceProgramIr expected, SurfaceProgramIr actual) {
        if (!validateTokenChain(expected) || !validateTokenChain(actual)) {
            return false;
        }
        List<TraceEvent> expectedEvents = expected.ops().stream()
                .filter(SurfaceOp::isStateful)
                .map(TraceEvent::fromOp)
                .toList();
        List<TraceEvent> actualEvents = actual.ops().stream()
                .filter(SurfaceOp::isStateful)
                .map(TraceEvent::fromOp)
                .toList();
        return expectedEvents.equals(actualEvents);
    }

    public record TraceEvent(String opcode, String domain, int tokenIn, int tokenOut) {
        static TraceEvent fromOp(SurfaceOp op) {
            return new TraceEvent(
                    op.opcode(),
                    op.domain().name(),
                    op.stateIn().ordinal(),
                    op.stateOut().ordinal()
            );
        }
    }

    public static final class Trace {
        private final List<TraceEvent> events = new ArrayList<>();

        public void record(String opcode, String domain, SurfaceStateToken in, SurfaceStateToken out) {
            this.events.add(new TraceEvent(
                    Objects.requireNonNull(opcode, "opcode"),
                    Objects.requireNonNull(domain, "domain"),
                    Objects.requireNonNull(in, "in").ordinal(),
                    Objects.requireNonNull(out, "out").ordinal()
            ));
        }

        public List<TraceEvent> events() {
            return Collections.unmodifiableList(this.events);
        }

        public void clear() {
            this.events.clear();
        }
    }
}

package dev.sixik.generator_accelerator.common.features.pipeline;

/**
 * Expected bailout from a detached parallel decoration task.
 *
 * <p>The executor catches this as a normal "retry this batch sequentially"
 * signal, not as a kernel compatibility failure.</p>
 */
final class DecorationParallelJournalFallback extends RuntimeException {
    DecorationParallelJournalFallback(String message) {
        super(message, null, false, false);
    }
}

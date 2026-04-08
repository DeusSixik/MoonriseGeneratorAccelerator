package dev.sixik.generator_accelerator.common.surface.vector;

import java.util.BitSet;

public class VectorSurfaceRules {

    // Корневое правило генерации (Скомпилированное из JSON)
    private final VectorRule rootRule;

    public VectorSurfaceRules(VectorRule rootRule) {
        this.rootRule = rootRule;
    }

    /**
     * Вызывается из твоего переписанного `buildSurface` один раз для каждой секции
     */
    public void applyToSection(int[] rawBlockData, BitSet stoneMask, VectorChunkContext ctx) {
        // Мы клонируем маску камня, потому что rootRule будет её "съедать",
        // а оригинал может еще пригодиться.
        BitSet processingMask = (BitSet) stoneMask.clone();

        // ЗАПУСК РАКЕТЫ:
        this.rootRule.apply(rawBlockData, processingMask, ctx);
    }
}
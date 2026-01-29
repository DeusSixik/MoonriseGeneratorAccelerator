package dev.sixik.density_interpreter;

public enum DensityOpcode {

    OP_FINISH,

    /**
     * [const_idx, out_reg]
     */
    OP_CONST,

    /**
     * [reg_a, reg_b, out_reg]
     */
    OP_ADD,
    OP_MINUS,
    OP_MUL,
    OP_DIV,
    OP_MIN,
    OP_MAX,

    /**
     * [reg_a, out_reg]
     */
    OP_ABS,
    OP_SQUARE,
    OP_CUBE,
    OP_HALF_NEGATIVE,
    OP_QUARTER_NEGATIVE,
    OP_SQUEEZE,

    OP_CACHE_2D_CHECK,
    OP_CACHE_2D_STORE,

    OP_BLENDER,

    /**
     * [reg_a, const_has_noise, (if [noise_type, noise_a] or const_idx), rarity_mapper_type]
     */
    OP_WEIRD_SAMPLER,

    /**
     * [reg_a, const_idx_min, const_idx_max]
     */
    OP_CLAMP,

    OP_SHIFTED_NOISE,

    OP_SHIFT,
    OP_SHIFT_A,
    OP_SHIFT_B,

    OP_BLEND,

    OP_Y_CLAMPED,

    /**
     * [const_has_noise, (if [noise_type, noise_a] or const_idx), out_reg]
     */
    OP_NOISE,

    /**
     * [noise_idx]
     */
    OP_NORMAL_NOISE,
    OP_SIMPLEX_NOISE,


    /**
     * [simplex_noise, noise_idx]
     */
    OP_END_ISLAND,

    /**
     * [reg_input, const_min, const_max, OFFSET_FALSE] <br>
     * Если (reg < min || reg >= max) -> PC += OFFSET_FALSE <br>
     * Иначе -> идем дальше (выполняем True ветку) <br>
     */
    OP_RANGE_JUMP,

    /**
     * [OFFSET_END] <br>
     * PC += OFFSET_END
     */
    OP_JUMP,


}

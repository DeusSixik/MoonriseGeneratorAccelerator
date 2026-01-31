//
// Created by sixik on 29.01.2026.
//

#ifndef DENSITYOPERATION_DENSITYVM_H
#define DENSITYOPERATION_DENSITYVM_H
#include <vector>

struct VMContext {
    std::vector<int> program;
    std::vector<double> constants;
};

enum Opcode : int {
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
     * [reg_input, const_min, const_max, OFFSET_FALSE] 
     * Если (reg < min || reg >= max) -> PC += OFFSET_FALSE 
     * Иначе -> идем дальше (выполняем True ветку) 
     */
    OP_RANGE_JUMP,

    /**
     * [OFFSET_END] 
     * PC += OFFSET_END
     */
    OP_JUMP,
};

struct DensityVM {

    static double run_vm(const VMContext* ctx, const double x, const double y, const double z) {
        const std::vector<int> program = ctx->program;
        const std::vector<double> constants = ctx->constants;

        /*
         * Виртуальные регистры (быстрая память на стеке)
         * 32 регистра обычно хватает для самых сложных графов Minecraft
         */
        double regs[32];

        int pc = 0; // Program Counter (указатель на текущую команду)

        while (true) {
            const int op = program[pc++]; // read opcode

            switch (op) {
                case OP_FINISH:
                    return regs[0];

                case OP_CONST: {
                    const int constIdx = program[pc++];
                    const int outReg = program[pc++];
                    regs[outReg] = constants[constIdx];
                    break;
                }

                case OP_ADD: {
                    const int regA   = program[pc++];
                    const int regB   = program[pc++];
                    const int outReg = program[pc++];
                    regs[outReg] = regs[regA] + regs[regB];
                    break;
                }
            }
        }
    }
};

#endif //DENSITYOPERATION_DENSITYVM_H
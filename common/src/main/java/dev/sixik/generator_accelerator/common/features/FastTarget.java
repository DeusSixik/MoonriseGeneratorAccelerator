package dev.sixik.generator_accelerator.common.features;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;

/**
 * @param validBlocks    Плоский массив разрешенных блоков
 * @param fallbackRule   Для сложных правил (оставляем ваниллу)
 * @param placementState Блок, который нужно поставить (руда)
 */
public record FastTarget(Block[] validBlocks, RuleTest fallbackRule, BlockState placementState) {
}

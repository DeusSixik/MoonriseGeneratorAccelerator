package dev.sixik.generator_accelerator.common.features.compat.natures_spirit;

import net.hibiscus.naturespirit.blocks.BranchingTrunkBlock;
import net.hibiscus.naturespirit.blocks.GrowingBranchingTrunkBlock;
import net.hibiscus.naturespirit.blocks.OliveBranchBlock;
import net.hibiscus.naturespirit.blocks.PolyporeBlock;
import net.hibiscus.naturespirit.blocks.ShiitakeMushroomPlantBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LeavesBlock;

public final class NaturesSpiritBlocks {
    private static final String MOD_ID = "natures_spirit";

    private static volatile GrowingBranchingTrunkBlock alluaudia;
    private static volatile BranchingTrunkBlock joshuaLog;
    private static volatile LeavesBlock joshuaLeaves;
    private static volatile Block coconut;
    private static volatile OliveBranchBlock oliveBranch;
    private static volatile ShiitakeMushroomPlantBlock shiitakeMushroom;
    private static volatile PolyporeBlock grayPolypore;
    private static volatile HugeMushroomBlock grayPolyporeBlock;

    private NaturesSpiritBlocks() {
    }

    public static GrowingBranchingTrunkBlock alluaudia() {
        GrowingBranchingTrunkBlock block = alluaudia;
        if (block == null) {
            block = (GrowingBranchingTrunkBlock) block("alluaudia");
            alluaudia = block;
        }
        return block;
    }

    public static BranchingTrunkBlock joshuaLog() {
        BranchingTrunkBlock block = joshuaLog;
        if (block == null) {
            block = (BranchingTrunkBlock) block("joshua_log");
            joshuaLog = block;
        }
        return block;
    }

    public static LeavesBlock joshuaLeaves() {
        LeavesBlock block = joshuaLeaves;
        if (block == null) {
            block = (LeavesBlock) block("joshua_leaves");
            joshuaLeaves = block;
        }
        return block;
    }

    public static Block coconut() {
        Block block = coconut;
        if (block == null) {
            block = block("coconut");
            coconut = block;
        }
        return block;
    }

    public static OliveBranchBlock oliveBranch() {
        OliveBranchBlock block = oliveBranch;
        if (block == null) {
            block = (OliveBranchBlock) block("olive_branch");
            oliveBranch = block;
        }
        return block;
    }

    public static ShiitakeMushroomPlantBlock shiitakeMushroom() {
        ShiitakeMushroomPlantBlock block = shiitakeMushroom;
        if (block == null) {
            block = (ShiitakeMushroomPlantBlock) block("shiitake_mushroom");
            shiitakeMushroom = block;
        }
        return block;
    }

    public static PolyporeBlock grayPolypore() {
        PolyporeBlock block = grayPolypore;
        if (block == null) {
            block = (PolyporeBlock) block("gray_polypore");
            grayPolypore = block;
        }
        return block;
    }

    public static HugeMushroomBlock grayPolyporeBlock() {
        HugeMushroomBlock block = grayPolyporeBlock;
        if (block == null) {
            block = (HugeMushroomBlock) block("gray_polypore_block");
            grayPolyporeBlock = block;
        }
        return block;
    }

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(MOD_ID, path));
    }
}

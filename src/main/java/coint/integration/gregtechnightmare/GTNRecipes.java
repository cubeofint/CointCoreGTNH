package coint.integration.gregtechnightmare;

import static gregtech.api.enums.TierEU.RECIPE_IV;

import net.minecraft.item.ItemStack;

import coint.CointCore;
import cpw.mods.fml.common.Loader;
import goodgenerator.util.ItemRefer;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.Mods;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTOreDictUnificator;
import thaumcraft.common.config.ConfigBlocks;

/**
 * Server-specific recipes for GregTech Nightmare machines. GTN items are referenced by their machine IDs, so GTN is
 * not a compile dependency.
 */
public final class GTNRecipes {

    private static final String GTN_MOD_ID = "GregTechNightmare";
    private static final int LASER_METEOR_MINER_ID = 21_018;

    private GTNRecipes() {}

    public static void init() {
        if (!Loader.isModLoaded(GTN_MOD_ID)) return;
        addLaserMeteorMiner();
    }

    /**
     * Same as the GTN recipe, but Low Power Void Miners are disabled on the server, so 16 HV Miners are used instead.
     */
    private static void addLaserMeteorMiner() {
        if (GregTechAPI.METATILEENTITIES[LASER_METEOR_MINER_ID] == null) {
            CointCore.LOG
                .warn("Laser Meteor Miner recipe skipped: machine {} is not registered", LASER_METEOR_MINER_ID);
            return;
        }
        ItemStack[] inputs = { ItemList.Machine_HV_Miner.get(16), new ItemStack(ConfigBlocks.blockWoodenDevice, 48, 5),
            ItemList.Robot_Arm_IV.get(4), ItemList.Conveyor_Module_IV.get(4), ItemRefer.HiC_T1.get(8),
            GTOreDictUnificator.get(OrePrefixes.wireGt16, Materials.TungstenSteel, 8),
            GTModHandler.getModItem(Mods.TaintedMagic.ID, "ItemFocusTime", 1),
            GTModHandler.getModItem(Mods.ThaumicTinkerer.ID, "focusSmelt", 1),
            GTModHandler.getModItem(Mods.ThaumicHorizons.ID, "focusDisintegration", 1) };
        for (ItemStack input : inputs) {
            if (input == null) {
                CointCore.LOG.warn("Laser Meteor Miner recipe skipped: one of the ingredients is missing");
                return;
            }
        }

        GTValues.RA.stdBuilder()
            .itemInputs(inputs)
            .itemOutputs(new ItemStack(GregTechAPI.sBlockMachines, 1, LASER_METEOR_MINER_ID))
            .eut(RECIPE_IV)
            .duration(20 * 60)
            .addTo(RecipeMaps.assemblerRecipes);
    }
}

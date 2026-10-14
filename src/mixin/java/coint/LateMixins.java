package coint;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

public enum LateMixins implements IMixins {

    APPLIEDENERGISTICS2(new MixinBuilder("Applied Energistics 2").addRequiredMod(TargetMod.APPLIEDENERGISTICS2)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "appliedenergistics2.MixinAEBaseTileChunkUnload",
            "appliedenergistics2.MixinGridNodeBatchDestroy",
            "appliedenergistics2.MixinGridConnectionBatchDestroy",
            "appliedenergistics2.MixinTickHandlerUnloadFlush",
            "appliedenergistics2.MixinSlotCraftingTermBatchRefresh",
            "appliedenergistics2.AccessorNetworkMonitor",
            "appliedenergistics2.MixinPartExportBusOreCache",
            "appliedenergistics2.MixinMultiCraftingTrackerRetry")),

    BACKPACK(new MixinBuilder("Backpacks").addRequiredMod(TargetMod.BACKPACK)
        .setPhase(Phase.LATE)
        .addCommonMixins("backpackmod.MixinContainerAdvancedAudit")),

    BETTERQUESTING(new MixinBuilder("BetterQuesting").addRequiredMod(TargetMod.BETTERQUESTING)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "betterquesting.MixinPartyInstance",
            "betterquesting.MixinRewardItemTeamReward",
            "betterquesting.MixinQuestCacheUpdateMemo",
            "betterquesting.MixinQuestInstanceStateMemo",
            "betterquesting.MixinBQStandardInventoryThrottle",
            "betterquesting.MixinBQPeriodicCacheRefresh",
            "betterquesting.MixinQuestInstanceProgressSave",
            "betterquesting.MixinTaskBaseProgressSave",
            "betterquesting.MixinTaskProgressableBaseProgressSave")),

    BLOODMAGIC(new MixinBuilder("BloodMagic").addRequiredMod(TargetMod.BLOODMAGIC)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "bloodmagic.MixinMeteor",
            "bloodmagic.MixinBoundToolsClaimGuard",
            "bloodmagic.MixinSacrificialDagger",
            "bloodmagic.MixinPlayerSacrificeHandler")),

    BOTANIA(new MixinBuilder("Botania").addRequiredMod(TargetMod.BOTANIA)
        .setPhase(Phase.LATE)
        .addCommonMixins("botania.MixinTileSpreaderChunkGuard", "botania.MixinEntityManaBurstChunkGuard")),

    CROPSNH(new MixinBuilder("CropsNH").addRequiredMod(TargetMod.CROPSNH)
        .setPhase(Phase.LATE)
        .addCommonMixins("cropsnh.MixinCropStickPhase")),

    ENDERIO(new MixinBuilder("EnderIO").addRequiredMod(TargetMod.ENDERIO)
        .setPhase(Phase.LATE)
        .addCommonMixins("enderio.MixinRedstoneConduitRebuild", "enderio.MixinDarkSteelNightVisionKeep")),

    FORESTRY(new MixinBuilder("Forestry").addRequiredMod(TargetMod.FORESTRY)
        .setPhase(Phase.LATE)
        .addCommonMixins("forestry.MixinItemInventoryUidFix")),

    GALACTICRAFT(new MixinBuilder("GalactiCraft").addRequiredMod(TargetMod.GALACTICRAFT)
        .setPhase(Phase.LATE)
        .addCommonMixins("galacticraft.MixinGCPlayerHandler")),

    GREGTECH(new MixinBuilder("GregTech").addRequiredMod(TargetMod.GREGTECH)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "gregtech.AccessorNodeList",
            "gregtech.MixinNodePowerRoutingCache",
            "gregtech.MixinMTECablePowerRouting",
            "gregtech.MixinBaseMetaTileEntityEUIOFaces")),

    HARVESTCRAFT(new MixinBuilder("Pam's HarvestCraft").addRequiredMod(TargetMod.HARVESTCRAFT)
        .setPhase(Phase.LATE)
        .addCommonMixins("harvestcraft.MixinPamFishTrapChunkGuard")),

    HEE(new MixinBuilder("Hardcore Ender Expansion").addRequiredMod(TargetMod.HEE)
        .setPhase(Phase.LATE)
        .addCommonMixins("hee.MixinHEEWorldDataAsyncSave")),

    JABBA(new MixinBuilder("JABBA").addRequiredMod(TargetMod.JABBA)
        .setPhase(Phase.LATE)
        .addCommonMixins("jabba.MixinJabbaDollySpawnerGuard")),

    MATTERMANIPULATOR(new MixinBuilder("MatterManipulator").addRequiredMod(TargetMod.MATTERMANIPULATOR)
        .setPhase(Phase.LATE)
        .addCommonMixins("mattermanipulator.MixinAbstractBuildable")),

    PERSONALSPACE(new MixinBuilder("PersonalSpace").addRequiredMod(TargetMod.PERSONALSPACE)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "personalspace.MixinPortalTileEntityTeamBinding",
            "personalspace.MixinDimensionConfigRetiredIds",
            "personalspace.MixinPortalTileEntityStaleLink")),

    SERVERUTILITIES(new MixinBuilder("ServerUtilities").addRequiredMod(TargetMod.SERVERUTILITIES)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "serverutilities.MixinCmdHome",
            "serverutilities.MixinCmdVanishSameDimension",
            "serverutilities.MixinCmdSetHome",
            "serverutilities.MixinServerUtilitiesTeamData",
            "serverutilities.MixinUniverseDottedPlayerName",
            "serverutilities.MixinForgeTeamPDimReward",
            "serverutilities.MixinClaimedChunksDisabledRightClick")
        .addClientMixins("serverutilities.MixinMessageUpdateTabName")),

    THAUMICEXPLORATION(new MixinBuilder("Thaumic Exploration").addRequiredMod(TargetMod.THAUMICEXPLORATION)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "thaumicexploration.MixinTXBootsEventHandler",
            "thaumicexploration.MixinEverfullUrnChunkGuard")),

    THAUMCRAFT(new MixinBuilder("Thaumcraft").addRequiredMod(TargetMod.THAUMCRAFT)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "thaumcraft.MixinItemFocusBasic",
            "thaumcraft.MixinItemFocusPortableHole",
            "thaumcraft.MixinItemFocusTrade",
            "thaumcraft.MixinItemFocusWarding",
            "thaumcraft.MixinTileArcaneBoreSleep",
            "thaumcraft.MixinTileArcaneBorePowerCache",
            "thaumcraft.MixinTileInfusionMatrixScan",
            "thaumcraft.MixinEssentiaHandlerSearch"));

    private final MixinBuilder builder;

    LateMixins(MixinBuilder builder) {
        this.builder = builder;
    }

    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}

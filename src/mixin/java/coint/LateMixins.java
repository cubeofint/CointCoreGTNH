package coint;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

public enum LateMixins implements IMixins {

    BACKPACK(new MixinBuilder("Backpacks").addRequiredMod(TargetMod.BACKPACK)
        .setPhase(Phase.LATE)
        .addCommonMixins("backpackmod.MixinContainerAdvancedAudit")),

    BETTERQUESTING(new MixinBuilder("BetterQuesting").addRequiredMod(TargetMod.BETTERQUESTING)
        .setPhase(Phase.LATE)
        .addCommonMixins("betterquesting.MixinPartyInstance")),

    BLOODMAGIC(new MixinBuilder("BloodMagic").addRequiredMod(TargetMod.BLOODMAGIC)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "bloodmagic.MixinMeteor",
            "bloodmagic.MixinBoundToolsClaimGuard",
            "bloodmagic.MixinSacrificialDagger",
            "bloodmagic.MixinPlayerSacrificeHandler")),

    FORESTRY(new MixinBuilder("Forestry").addRequiredMod(TargetMod.FORESTRY)
        .setPhase(Phase.LATE)
        .addCommonMixins("forestry.MixinItemInventoryUidFix")),

    GALACTICRAFT(new MixinBuilder("GalactiCraft").addRequiredMod(TargetMod.GALACTICRAFT)
        .setPhase(Phase.LATE)
        .addCommonMixins("galacticraft.MixinGCPlayerHandler")),

    MATTERMANIPULATOR(new MixinBuilder("MatterManipulator").addRequiredMod(TargetMod.MATTERMANIPULATOR)
        .setPhase(Phase.LATE)
        .addCommonMixins("mattermanipulator.MixinAbstractBuildable")),

    SERVERUTILITIES(new MixinBuilder("ServerUtilities").addRequiredMod(TargetMod.SERVERUTILITIES)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "serverutilities.MixinCmdHome",
            "serverutilities.MixinCmdSetHome",
            "serverutilities.MixinServerUtilitiesTeamData")
        .addClientMixins("serverutilities.MixinMessageUpdateTabName")),

    THAUMCRAFT(new MixinBuilder("Thaumcraft").addRequiredMod(TargetMod.THAUMCRAFT)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "thaumcraft.MixinItemFocusBasic",
            "thaumcraft.MixinItemFocusPortableHole",
            "thaumcraft.MixinItemFocusTrade",
            "thaumcraft.MixinItemFocusWarding",
            "thaumcraft.MixinTileArcaneBoreSleep"));

    private final MixinBuilder builder;

    LateMixins(MixinBuilder builder) {
        this.builder = builder;
    }

    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}

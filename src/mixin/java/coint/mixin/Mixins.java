package coint.mixin;

import com.gtnewhorizon.gtnhmixins.builders.IBaseTransformer.Phase;
import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

/**
 * Vanilla/Forge targets are resolvable as soon as the game starts, so they stay on {@code phase = null} — loaded
 * through {@link CointMixinPlugin#getMixins()} via the regular {@code mixins.cointcore.json}.
 *
 * <p>
 * Everything else targets another mod's classes, which may not be on the classpath yet when
 * {@code mixins.cointcore.json} is processed. Those are {@link Phase#LATE}, loaded through
 * {@link CointLateMixinLoader} once FML has added all mod jars to the classpath.
 */
public enum Mixins implements IMixins {

    VANILLA(new MixinBuilder("vanilla/Forge classes, always resolvable").addCommonMixins(
        "minecraft.MixinCommandMessage",
        "minecraft.MixinEntityLivingBase",
        "minecraft.MixinEntityPlayer",
        "minecraft.MixinEntityPlayerForestryDupTrace",
        "minecraft.MixinNetHandlerPlayServerForestryBackpackClickBlock",
        "minecraft.MixinServerConfigurationManager")),

    BACKPACK(new MixinBuilder("Backpack mod").addRequiredMod(TargetMod.BACKPACK)
        .setPhase(Phase.LATE)
        .addCommonMixins(
            "backpackmod.MixinContainerAdvancedAudit",
            "backpackmod.MixinEntityPlayerBackpackAudit",
            "backpackmod.MixinNetHandlerPlayServerBackpackAudit")),

    BETTERQUESTING(new MixinBuilder("BetterQuesting").addRequiredMod(TargetMod.BETTERQUESTING)
        .setPhase(Phase.LATE)
        .addCommonMixins("betterquesting.MixinPartyInstance")),

    BLOODMAGIC(new MixinBuilder("Blood Magic").addRequiredMod(TargetMod.BLOODMAGIC)
        .setPhase(Phase.LATE)
        .addCommonMixins("bloodmagic.MixinMeteor", "bloodmagic.MixinBoundToolsClaimGuard")),

    FORESTRY(new MixinBuilder("Forestry").addRequiredMod(TargetMod.FORESTRY)
        .setPhase(Phase.LATE)
        .addCommonMixins("forestry.MixinItemInventoryUidFix")),

    GALACTICRAFT(new MixinBuilder("Galacticraft").addRequiredMod(TargetMod.GALACTICRAFT)
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
            "thaumcraft.MixinItemFocusWarding"));

    private final MixinBuilder builder;

    Mixins(MixinBuilder builder) {
        this.builder = builder;
    }

    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}

package coint.mixin;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

public enum Mixins implements IMixins {

    VANILLA(new MixinBuilder("Vanilla/Forge").addCommonMixins(
        "minecraft.MixinCommandMessage",
        "minecraft.MixinEntityItemMeteorBoots",
        "minecraft.MixinEntityMeteorBootsSetDead",
        "minecraft.MixinWorldMeteorBoots",
        "minecraft.MixinEntityLivingBase",
        "minecraft.MixinEntityLivingBaseActivation",
        "minecraft.MixinEntityLivingActivation",
        "minecraft.MixinTileEntityChestChunkGuard",
        "minecraft.MixinBlockChestChunkGuard",
        "ic2.MixinIc2WaterKineticChunkGuard",
        "magicbees.MixinTileEntityMagicApiaryChunkGuard",
        "minecraft.MixinEntityPlayer",
        "minecraft.MixinEntityPlayerForestryDupTrace",
        "minecraft.MixinNetHandlerPlayServerForestryBackpackClickBlock",
        "minecraft.MixinServerConfigurationManager",
        "minecraft.MixinSpawnerAnimals",
        "minecraft.MixinWorldServerHodgepodgeFastPath",
        "backpackmod.MixinEntityPlayerBackpackAudit",
        "backpackmod.MixinNetHandlerPlayServerBackpackAudit"));

    private final MixinBuilder builder;

    Mixins(MixinBuilder builder) {
        this.builder = builder;
    }

    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}

package coint.mixin;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

public enum Mixins implements IMixins {

    VANILLA(new MixinBuilder("Vanilla/Forge").addCommonMixins(
        "minecraft.MixinCommandMessage",
        "minecraft.MixinEntityLivingBase",
        "minecraft.MixinEntityPlayer",
        "minecraft.MixinEntityPlayerForestryDupTrace",
        "minecraft.MixinNetHandlerPlayServerForestryBackpackClickBlock",
        "minecraft.MixinServerConfigurationManager",
        "minecraft.MixinSpawnerAnimals",
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

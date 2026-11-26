package coint.mixin;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

public enum Mixins implements IMixins {

    SPOTIFY(new MixinBuilder("Optional Spotify chat widget").addClientMixins("minecraft.MixinGuiChatSpotify")),

    CHAT(new MixinBuilder("Chat channel tabs").addClientMixins(
        "minecraft.MixinGuiNewChatChannels",
        "minecraft.MixinGuiChatChannels",
        "hodgepodge.MixinChatCompactionChannels")),

    // NEI is a coremod: NEIServerUtils is loaded before late mixins are prepared.
    // The pseudo target also permits running without NEI.
    NEI(new MixinBuilder("NEI integration").addCommonMixins("nei.MixinNEIServerUtilsLinks")
        .addClientMixins("nei.MixinNEIClientConfig")),

    VANILLA(new MixinBuilder("Vanilla/Forge").addCommonMixins(
        "minecraft.MixinCommandMessage",
        "minecraft.MixinDimensionManagerMissingWorldTrace",
        "minecraft.MixinChunkProviderMigrationFreeze",
        "minecraft.AccessorQueuedMigrationChunk",
        "minecraft.MixinChunkIOMigrationFreeze",
        "minecraft.MixinWorldMigrationFreeze",
        "minecraft.MixinWorldServerMigrationFreeze",
        "minecraft.MixinForgeMigrationFreeze",
        "minecraft.MixinItemStackKitOrigin",
        "minecraft.MixinPacketBufferKitOrigin",
        "minecraft.MixinEntityItemMeteorBoots",
        "minecraft.MixinEntityItemDropLog",
        "minecraft.MixinForgeHooksDropLog",
        "minecraft.MixinEntityMeteorBootsSetDead",
        "minecraft.MixinWorldMeteorBoots",
        "minecraft.MixinEntityLivingBase",
        "minecraft.MixinEntityLivingBaseActivation",
        "minecraft.MixinEntityLivingActivation",
        "minecraft.MixinTileEntityChestChunkGuard",
        "minecraft.MixinBlockChestChunkGuard",
        "minecraft.MixinPlayerInstanceEmptyUpdates",
        "ic2.MixinIc2WaterKineticChunkGuard",
        "magicbees.MixinTileEntityMagicApiaryChunkGuard",
        "minecraft.MixinEntityPlayer",
        "minecraft.MixinEntityPlayerForestryDupTrace",
        "minecraft.MixinNetHandlerPlayServerForestryBackpackClickBlock",
        "minecraft.MixinServerConfigurationManager",
        "minecraft.MixinNetHandlerLoginServerClusterSession",
        "minecraft.MixinNetworkManagerClusterSession",
        "minecraft.MixinFMLHandshakeCompletion",
        "minecraft.MixinNetHandlerStatusServerLocalCounts",
        "minecraft.AccessorUserListEntry",
        "minecraft.MixinBanEntryClusterView",
        "minecraft.MixinUserListClusterSync",
        "minecraft.MixinCommandListPlayersCluster",
        "minecraft.MixinMinecraftServerClusterNames",
        "minecraft.MixinServerConfigurationManagerClusterNames",
        "minecraft.MixinSpawnerAnimals",
        "minecraft.MixinWorldServerHodgepodgeFastPath",
        "minecraft.MixinWorldRandomTickRadius",
        "minecraft.MixinItemInWorldManagerVanishPatch",
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

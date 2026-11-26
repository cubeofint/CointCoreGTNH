package coint;

import net.minecraft.network.NetworkManager;
import net.minecraftforge.common.MinecraftForge;

import coint.client.ClientEventNei;
import coint.client.ClientRestartOverlay;
import coint.client.ClientTransfer;
import coint.client.ClientWorldTravelEvents;
import coint.client.ClientWorldTravelState;
import coint.client.SrgbGuard;
import coint.client.battlepass.CommandBattlePassPreview;
import coint.network.PacketOpenWorlds;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    private volatile boolean pdimRewardBlocked;

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        coint.client.spotify.ClientSpotify.init();
        coint.client.CommandKitManifest kitManifest = new coint.client.CommandKitManifest();
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(kitManifest);
        FMLCommonHandler.instance()
            .bus()
            .register(kitManifest);
        FMLCommonHandler.instance()
            .bus()
            .register(new coint.client.chat.ClientChatChannels());
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientWorldTravelEvents());
        MinecraftForge.EVENT_BUS.register(new ClientRestartOverlay());
        MinecraftForge.EVENT_BUS.register(new coint.client.ClientCardsOverlay());
        cpw.mods.fml.client.registry.ClientRegistry
            .bindTileEntitySpecialRenderer(coint.cards.TileCardTable.class, new coint.client.RenderCardTable());
        FMLCommonHandler.instance()
            .bus()
            .register(new coint.client.ClientCards.Disconnect());
        if (Loader.isModLoaded("NotEnoughItems")) {
            coint.supply.SupplyNeiHandler.register();
            ClientEventNei eventNei = new ClientEventNei();
            MinecraftForge.EVENT_BUS.register(eventNei);
            FMLCommonHandler.instance()
                .bus()
                .register(eventNei);
        }
        FMLCommonHandler.instance()
            .bus()
            .register(new SrgbGuard());
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientTransfer.Ticker());
        if (CointConfig.general.battlePassPreview) {
            FMLCommonHandler.instance()
                .bus()
                .register(new CommandBattlePassPreview.Opener());
            net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new CommandBattlePassPreview());
        }
    }

    @Override
    public void queueWorldTravelGui(PacketOpenWorlds packet) {
        ClientWorldTravelState.queue(packet);
    }

    @Override
    public void receiveChatChannel(coint.chat.PacketChatChannel packet) {
        coint.client.chat.ClientChatChannels.receive(packet);
    }

    @Override
    public void receiveAdminChatAccess(boolean allowed) {
        coint.client.chat.ClientChatChannels.receiveAdminAccess(allowed);
    }

    @Override
    public void receiveEventNei(NetworkManager connection, boolean hidden) {
        if (Loader.isModLoaded("NotEnoughItems")) ClientEventNei.receive(connection, hidden);
    }

    @Override
    public void receiveTeamHistory(coint.cluster.PacketTeamHistory packet) {
        coint.client.GuiTeamHistory.receive(packet);
    }

    @Override
    public void setPDimRewardBlockedClient(boolean blocked) {
        pdimRewardBlocked = blocked;
    }

    @Override
    public boolean isPDimRewardBlockedClient() {
        return pdimRewardBlocked;
    }
}

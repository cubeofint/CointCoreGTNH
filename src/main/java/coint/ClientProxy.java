package coint;

import coint.client.ClientWorldTravelEvents;
import coint.client.ClientWorldTravelState;
import coint.network.PacketOpenWorlds;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientWorldTravelEvents());
    }

    @Override
    public void queueWorldTravelGui(PacketOpenWorlds packet) {
        ClientWorldTravelState.queue(packet);
    }
}

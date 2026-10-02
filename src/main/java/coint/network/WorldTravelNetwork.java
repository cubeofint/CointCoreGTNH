package coint.network;

import net.minecraft.entity.player.EntityPlayerMP;

import coint.CointCore;
import coint.worldtravel.WorldTravelManager;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class WorldTravelNetwork {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("cointworlds");
    private static boolean initialized;

    private WorldTravelNetwork() {}

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        CHANNEL.registerMessage(PacketOpenWorlds.Handler.class, PacketOpenWorlds.class, 0, Side.CLIENT);
        CHANNEL.registerMessage(PacketTravelRequest.Handler.class, PacketTravelRequest.class, 1, Side.SERVER);
        CHANNEL.registerMessage(PacketOpenWorldsRequest.Handler.class, PacketOpenWorldsRequest.class, 2, Side.SERVER);
        CHANNEL.registerMessage(PacketPDimRewardState.Handler.class, PacketPDimRewardState.class, 3, Side.CLIENT);
        CHANNEL.registerMessage(PacketRestartState.Handler.class, PacketRestartState.class, 4, Side.CLIENT);
        CointCore.LOG.info("[WorldTravel] Network initialized");
    }

    public static void openFor(EntityPlayerMP player) {
        CHANNEL.sendTo(
            new PacketOpenWorlds(WorldTravelManager.getTitle(), WorldTravelManager.getViewEntries(player)),
            player);
    }

    public static void requestOpen() {
        CHANNEL.sendToServer(new PacketOpenWorldsRequest());
    }

    public static void syncPDimRewardState(EntityPlayerMP player, boolean blocked) {
        if (player != null) {
            CHANNEL.sendTo(new PacketPDimRewardState(blocked), player);
        }
    }

    public static void syncRestartState(EntityPlayerMP player, boolean active, int secondsRemaining, String phase) {
        if (player != null) {
            CHANNEL.sendTo(new PacketRestartState(active, secondsRemaining, phase), player);
        }
    }

    public static void broadcastRestartState(boolean active, int secondsRemaining, String phase) {
        CHANNEL.sendToAll(new PacketRestartState(active, secondsRemaining, phase));
    }
}

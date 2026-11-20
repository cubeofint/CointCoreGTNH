package coint.events;

import net.minecraft.network.ServerStatusResponse;
import net.minecraft.server.MinecraftServer;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.cluster.ClusterService;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Changes the advertised count only; admission limits remain local to each server. */
@EventBusSubscriber
public final class ClusterPingOnline {

    private static int ticks;
    private static volatile ServerStatusResponse.PlayerCountData localPlayers;
    private static volatile ServerStatusResponse.PlayerCountData clusterPlayers;

    private ClusterPingOnline() {}

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClusterService.isRunning()) return;
        if (++ticks < 20 && clusterPlayers != null) return;
        ticks = 0;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return;
        localPlayers = new ServerStatusResponse.PlayerCountData(server.getMaxPlayers(), server.getCurrentPlayerCount());
        ServerStatusResponse status = server.func_147134_at();
        com.mojang.authlib.GameProfile[] players = ClusterService.visibleProfiles();
        ServerStatusResponse.PlayerCountData data = new ServerStatusResponse.PlayerCountData(
            ClusterService.totalCapacity(),
            players.length);
        data.func_151330_a(players);
        clusterPlayers = data;
        status.func_151319_a(data);
    }

    /**
     * The local panel connects over loopback; public and proxy pings use the captured cluster total. Vanilla
     * replaces its shared status with local counts every five seconds, so neither path may retain that object:
     * Netty can serialize the reply after the next vanilla update.
     */
    public static ServerStatusResponse forAddress(ServerStatusResponse original, java.net.SocketAddress remote) {
        if (!ClusterService.isRunning()) return original;
        boolean loopback = remote instanceof java.net.InetSocketAddress address && address.getAddress() != null
            && address.getAddress()
                .isLoopbackAddress();
        ServerStatusResponse.PlayerCountData players = loopback ? localPlayers : clusterPlayers;
        if (players == null) return original;
        ServerStatusResponse copy = new ServerStatusResponse();
        copy.func_151315_a(original.func_151317_a());
        copy.func_151321_a(original.func_151322_c());
        copy.func_151320_a(original.func_151316_d());
        copy.func_151319_a(players);
        return copy;
    }
}

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

    private ClusterPingOnline() {}

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClusterService.isRunning()) return;
        if (++ticks < 20) return;
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
        status.func_151319_a(data);
    }

    /** The local panel connects over loopback; public and proxy pings retain the cluster total. */
    public static ServerStatusResponse forAddress(ServerStatusResponse original, java.net.SocketAddress remote) {
        ServerStatusResponse.PlayerCountData local = localPlayers;
        if (!ClusterService.isRunning() || local == null
            || !(remote instanceof java.net.InetSocketAddress address)
            || address.getAddress() == null
            || !address.getAddress()
                .isLoopbackAddress())
            return original;
        ServerStatusResponse copy = new ServerStatusResponse();
        copy.func_151315_a(original.func_151317_a());
        copy.func_151321_a(original.func_151322_c());
        copy.func_151320_a(original.func_151316_d());
        copy.func_151319_a(local);
        return copy;
    }
}

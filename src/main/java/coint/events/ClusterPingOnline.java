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

    private ClusterPingOnline() {}

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClusterService.isRunning()) return;
        if (++ticks < 20) return;
        ticks = 0;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return;
        ServerStatusResponse status = server.func_147134_at();
        com.mojang.authlib.GameProfile[] players = ClusterService.visibleProfiles();
        ServerStatusResponse.PlayerCountData data = new ServerStatusResponse.PlayerCountData(
            ClusterService.totalCapacity(),
            players.length);
        data.func_151330_a(players);
        status.func_151319_a(data);
    }
}

package coint.cluster;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.network.PacketTransfer;
import coint.network.WorldTravelNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Sends players to another server of the network: the client is told where to go and reconnects by itself. One that
 * is still here after {@link CointConfig.Cluster#transferTimeoutSeconds} (a client without CointCore) is disconnected
 * with the address to join.
 */
@EventBusSubscriber
public final class ClusterTransfers {

    /** Players asked to leave: when they must be gone, and where to. */
    private static final Map<UUID, Pending> pending = new HashMap<>();

    private ClusterTransfers() {}

    static void send(EntityPlayerMP player, ClusterDatabase.NodeRow node) {
        CointCore.LOG
            .info("[Cluster] Sending {} to server {} ({})", player.getCommandSenderName(), node.id, node.address);
        player.addChatMessage(new ChatComponentText("§aПереход на сервер " + node.name + "..."));
        WorldTravelNetwork.CHANNEL.sendTo(new PacketTransfer(node.name, node.address), player);
        long deadline = System.currentTimeMillis() + CointConfig.cluster.transferTimeoutSeconds * 1000L;
        pending.put(player.getUniqueID(), new Pending(player, node, deadline));
    }

    /** Asked to leave and not gone yet. */
    static boolean isLeaving(UUID uuid) {
        return pending.containsKey(uuid);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Pending> it = pending.values()
            .iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            boolean online = MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList.contains(p.player);
            if (!online) {
                it.remove();
            } else if (now >= p.deadline) {
                it.remove();
                p.player.playerNetServerHandler
                    .kickPlayerFromServer("Перейдите на сервер " + p.node.name + ": " + p.node.address);
            }
        }
    }

    private static final class Pending {

        final EntityPlayerMP player;
        final ClusterDatabase.NodeRow node;
        final long deadline;

        Pending(EntityPlayerMP player, ClusterDatabase.NodeRow node, long deadline) {
            this.player = player;
            this.node = node;
            this.deadline = deadline;
        }
    }
}

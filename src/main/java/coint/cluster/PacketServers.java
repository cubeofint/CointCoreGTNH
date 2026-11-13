package coint.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import coint.CointConfig;
import coint.client.ClientServers;
import coint.network.WorldTravelNetwork;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Server to client: the servers of the network for the servers screen (empty and not available: network down). */
public class PacketServers implements IMessage {

    public boolean available;
    /** Open the servers screen (the hub answers the travel key with it). */
    public boolean open;
    /** World travel is open to this player here (not on the hub). */
    public boolean travel = true;
    public final List<Entry> entries = new ArrayList<>();

    public static final class Entry {

        public String id = "", name = "";
        public int players;
        public double tps;
        public boolean online, here, home;
    }

    public PacketServers() {}

    /** Asks the database and answers {@code player}; called on the server thread. */
    static void sendTo(EntityPlayerMP player) {
        sendTo(player, false);
    }

    /** {@code open}: the client opens the servers screen with it. Server thread. */
    public static void sendTo(EntityPlayerMP player, boolean open) {
        boolean travel = !ClusterHub.isHub();
        int timeout = CointConfig.cluster.nodeTimeoutSeconds;
        UUID uuid = player.getUniqueID();
        boolean started = ClusterService.query(db -> db.nodes(timeout), nodes -> {
            PacketServers packet = new PacketServers();
            packet.available = true;
            packet.open = open;
            packet.travel = travel;
            String home = ClusterHomes.homeOf(uuid);
            for (ClusterDatabase.NodeRow node : nodes) {
                Entry entry = new Entry();
                entry.id = node.id;
                entry.name = node.name;
                entry.players = node.players;
                entry.tps = node.tps;
                entry.online = node.online;
                entry.here = node.id.equals(ClusterService.nodeId());
                entry.home = node.id.equals(home);
                packet.entries.add(entry);
            }
            send(player, packet);
        }, error -> send(player, down(open, travel)));
        if (!started) {
            send(player, down(open, travel));
        }
    }

    private static PacketServers down(boolean open, boolean travel) {
        PacketServers packet = new PacketServers();
        packet.open = open;
        packet.travel = travel;
        return packet;
    }

    private static void send(EntityPlayerMP player, PacketServers packet) {
        if (player.playerNetServerHandler != null) {
            WorldTravelNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        available = buf.readBoolean();
        open = buf.readBoolean();
        travel = buf.readBoolean();
        int n = Math.min(buf.readUnsignedByte(), 64);
        for (int i = 0; i < n; i++) {
            Entry entry = new Entry();
            entry.id = ByteBufUtils.readUTF8String(buf);
            entry.name = ByteBufUtils.readUTF8String(buf);
            entry.players = buf.readInt();
            entry.tps = buf.readDouble();
            int flags = buf.readByte();
            entry.online = (flags & 1) != 0;
            entry.here = (flags & 2) != 0;
            entry.home = (flags & 4) != 0;
            entries.add(entry);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(available);
        buf.writeBoolean(open);
        buf.writeBoolean(travel);
        int n = Math.min(entries.size(), 64);
        buf.writeByte(n);
        for (int i = 0; i < n; i++) {
            Entry entry = entries.get(i);
            ByteBufUtils.writeUTF8String(buf, entry.id);
            ByteBufUtils.writeUTF8String(buf, entry.name == null ? entry.id : entry.name);
            buf.writeInt(entry.players);
            buf.writeDouble(entry.tps);
            buf.writeByte((entry.online ? 1 : 0) | (entry.here ? 2 : 0) | (entry.home ? 4 : 0));
        }
    }

    public static class Handler implements IMessageHandler<PacketServers, IMessage> {

        @Override
        public IMessage onMessage(PacketServers m, MessageContext ctx) {
            ClientServers.receive(m);
            return null;
        }
    }

    /** Client to server: send me the servers list. */
    public static class Request implements IMessage {

        @Override
        public void fromBytes(ByteBuf buf) {}

        @Override
        public void toBytes(ByteBuf buf) {}
    }

    public static class RequestHandler implements IMessageHandler<Request, IMessage> {

        /** When each player last asked: one answer a second at most. */
        private static final java.util.Map<UUID, Long> asked = new java.util.HashMap<>();

        @Override
        public IMessage onMessage(Request m, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() == null ? null : ctx.getServerHandler().playerEntity;
            if (player != null) {
                SupplyServerTasks.run(() -> {
                    long now = System.currentTimeMillis();
                    Long last = asked.put(player.getUniqueID(), now);
                    if (last == null || now - last >= 1000) {
                        sendTo(player);
                    }
                });
            }
            return null;
        }
    }
}

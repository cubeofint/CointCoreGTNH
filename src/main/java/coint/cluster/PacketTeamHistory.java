package coint.cluster;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;

import coint.CointCore;
import coint.supply.SupplyServerTasks;
import coint.teamhistory.HistoryEvent;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class PacketTeamHistory implements IMessage {

    public String title = "", error = "";
    public long since;
    public int request;
    public boolean older, open;
    public final List<HistoryEvent> entries = new ArrayList<>();

    @Override
    public void fromBytes(ByteBuf buffer) {
        request = buffer.readInt();
        open = buffer.readBoolean();
        title = ByteBufUtils.readUTF8String(buffer);
        error = ByteBufUtils.readUTF8String(buffer);
        since = buffer.readLong();
        older = buffer.readBoolean();
        int count = buffer.readUnsignedByte();
        if (count > TeamHistoryStore.PAGE_SIZE) throw new IllegalArgumentException("History page too large");
        for (int i = 0; i < count; i++) {
            HistoryEvent event = new HistoryEvent();
            event.row = buffer.readLong();
            event.time = buffer.readLong();
            event.kind = ByteBufUtils.readUTF8String(buffer);
            event.label = ByteBufUtils.readUTF8String(buffer);
            event.actor = ByteBufUtils.readUTF8String(buffer);
            event.node = ByteBufUtils.readUTF8String(buffer);
            event.target = ByteBufUtils.readUTF8String(buffer);
            entries.add(event);
        }
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeInt(request);
        buffer.writeBoolean(open);
        ByteBufUtils.writeUTF8String(buffer, title);
        ByteBufUtils.writeUTF8String(buffer, error);
        buffer.writeLong(since);
        buffer.writeBoolean(older);
        int count = Math.min(TeamHistoryStore.PAGE_SIZE, entries.size());
        buffer.writeByte(count);
        for (int i = 0; i < count; i++) {
            HistoryEvent event = entries.get(i);
            buffer.writeLong(event.row);
            buffer.writeLong(event.time);
            ByteBufUtils.writeUTF8String(buffer, event.kind);
            ByteBufUtils.writeUTF8String(buffer, event.label);
            ByteBufUtils.writeUTF8String(buffer, event.actor);
            ByteBufUtils.writeUTF8String(buffer, event.node);
            ByteBufUtils.writeUTF8String(buffer, event.target);
        }
    }

    public static final class Handler implements IMessageHandler<PacketTeamHistory, IMessage> {

        @Override
        public IMessage onMessage(PacketTeamHistory message, MessageContext context) {
            CointCore.proxy.receiveTeamHistory(message);
            return null;
        }
    }

    public static final class Request implements IMessage {

        public long before;
        public int request;

        public Request() {}

        public Request(long before, int request) {
            this.before = before;
            this.request = request;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            before = buffer.readLong();
            request = buffer.readInt();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeLong(before);
            buffer.writeInt(request);
        }
    }

    public static final class RequestHandler implements IMessageHandler<Request, IMessage> {

        @Override
        public IMessage onMessage(Request message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            SupplyServerTasks.run(() -> TeamHistory.request(player, message.before, message.request, false));
            return null;
        }
    }
}

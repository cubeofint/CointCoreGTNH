package coint.network;

import coint.client.ClientSupplyState;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Server to client: what an open supply buffer window shows besides its slots. */
public class PacketSupplyState implements IMessage {

    private int windowId;
    private boolean online;
    private int pending;
    private String error;
    private long[] stock;
    private long[] target;
    private long[] threshold;

    public PacketSupplyState() {}

    public PacketSupplyState(int windowId, boolean online, int pending, String error, long[] stock, long[] target,
        long[] threshold) {
        this.windowId = windowId;
        this.online = online;
        this.pending = pending;
        this.error = error == null ? "" : error;
        this.stock = stock;
        this.target = target;
        this.threshold = threshold;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        windowId = buf.readInt();
        online = buf.readBoolean();
        pending = buf.readInt();
        error = ByteBufUtils.readUTF8String(buf);
        int count = buf.readUnsignedByte();
        stock = new long[count];
        target = new long[count];
        threshold = new long[count];
        for (int i = 0; i < count; i++) {
            stock[i] = buf.readLong();
            target[i] = buf.readLong();
            threshold[i] = buf.readLong();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(windowId);
        buf.writeBoolean(online);
        buf.writeInt(pending);
        ByteBufUtils.writeUTF8String(buf, error);
        buf.writeByte(stock.length);
        for (int i = 0; i < stock.length; i++) {
            buf.writeLong(stock[i]);
            buf.writeLong(target[i]);
            buf.writeLong(threshold[i]);
        }
    }

    public static class Handler implements IMessageHandler<PacketSupplyState, IMessage> {

        @Override
        public IMessage onMessage(PacketSupplyState message, MessageContext ctx) {
            ClientSupplyState.apply(
                message.windowId,
                message.online,
                message.pending,
                message.error,
                message.stock,
                message.target,
                message.threshold);
            return null;
        }
    }
}

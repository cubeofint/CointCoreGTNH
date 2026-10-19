package coint.network;

import net.minecraft.entity.player.EntityPlayerMP;

import coint.supply.ContainerSupplyBuffer;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Client to server: the target and threshold typed for one filter of an open supply buffer window. */
public class PacketSupplySettings implements IMessage {

    private int windowId;
    private int filter;
    private long target;
    private long threshold;

    public PacketSupplySettings() {}

    public PacketSupplySettings(int windowId, int filter, long target, long threshold) {
        this.windowId = windowId;
        this.filter = filter;
        this.target = target;
        this.threshold = threshold;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        windowId = buf.readInt();
        filter = buf.readByte();
        target = buf.readLong();
        threshold = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(windowId);
        buf.writeByte(filter);
        buf.writeLong(target);
        buf.writeLong(threshold);
    }

    public static class Handler implements IMessageHandler<PacketSupplySettings, IMessage> {

        @Override
        public IMessage onMessage(PacketSupplySettings message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() == null ? null : ctx.getServerHandler().playerEntity;
            if (player == null) {
                return null;
            }
            SupplyServerTasks.run(() -> {
                if (player.openContainer instanceof ContainerSupplyBuffer container
                    && container.windowId == message.windowId) {
                    container.setAmounts(player, message.filter, message.target, message.threshold);
                }
            });
            return null;
        }
    }
}

package coint.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import coint.supply.ContainerSupplyBuffer;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Client to server: an item set on a filter from NEI, or a container set with Ctrl of an open supply buffer window. */
public class PacketSupplyFilter implements IMessage {

    private int windowId;
    private int filter;
    private ItemStack item;
    /** A container stands for the fluid in it (Ctrl), not for itself. */
    private boolean asFluid;

    public PacketSupplyFilter() {}

    public PacketSupplyFilter(int windowId, int filter, ItemStack item, boolean asFluid) {
        this.asFluid = asFluid;
        this.windowId = windowId;
        this.filter = filter;
        this.item = item;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        windowId = buf.readInt();
        filter = buf.readByte();
        item = ByteBufUtils.readItemStack(buf);
        asFluid = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(windowId);
        buf.writeByte(filter);
        ByteBufUtils.writeItemStack(buf, item);
        buf.writeBoolean(asFluid);
    }

    public static class Handler implements IMessageHandler<PacketSupplyFilter, IMessage> {

        @Override
        public IMessage onMessage(PacketSupplyFilter message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() == null ? null : ctx.getServerHandler().playerEntity;
            if (player == null || message.item == null) {
                return null;
            }
            SupplyServerTasks.run(() -> {
                if (player.openContainer instanceof ContainerSupplyBuffer container
                    && container.windowId == message.windowId) {
                    container.setFilter(player, message.filter, message.item, message.asFluid);
                }
            });
            return null;
        }
    }
}

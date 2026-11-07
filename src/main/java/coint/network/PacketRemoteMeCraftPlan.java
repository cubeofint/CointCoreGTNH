package coint.network;

import net.minecraft.item.ItemStack;

import coint.client.GuiRemoteCraftConfirm;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: the plan the remote network made for a craft order (compressed NBT, empty when planning failed),
 * shown in the confirm screen.
 */
public class PacketRemoteMeCraftPlan implements IMessage {

    private int windowId;
    private long planId;
    private boolean startable;
    private ItemStack kind;
    private long amount;
    private byte[] data;

    public PacketRemoteMeCraftPlan() {}

    public PacketRemoteMeCraftPlan(int windowId, long planId, boolean startable, ItemStack kind, long amount,
        byte[] data) {
        this.windowId = windowId;
        this.planId = planId;
        this.startable = startable;
        this.kind = kind;
        this.amount = amount;
        this.data = data == null ? new byte[0] : data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        windowId = buf.readInt();
        planId = buf.readLong();
        startable = buf.readBoolean();
        kind = ByteBufUtils.readItemStack(buf);
        amount = buf.readLong();
        data = new byte[buf.readInt()];
        buf.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(windowId);
        buf.writeLong(planId);
        buf.writeBoolean(startable);
        ByteBufUtils.writeItemStack(buf, kind);
        buf.writeLong(amount);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    public static class Handler implements IMessageHandler<PacketRemoteMeCraftPlan, IMessage> {

        @Override
        public IMessage onMessage(PacketRemoteMeCraftPlan m, MessageContext ctx) {
            GuiRemoteCraftConfirm.receive(m.windowId, m.planId, m.startable, m.kind, m.amount, m.data);
            return null;
        }
    }
}

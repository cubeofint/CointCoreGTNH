package coint.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import coint.cluster.RemoteMe;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Client to server: take items of a kind from the remote ME network, or put the cursor's items into it. */
public class PacketRemoteMeAction implements IMessage {

    private int windowId;
    private int action;
    private ItemStack kind;
    private int amount;
    /** For a start: the plan. */
    private long ref;

    public PacketRemoteMeAction() {}

    public PacketRemoteMeAction(int windowId, int action, ItemStack kind, int amount, long ref) {
        this(windowId, action, kind, amount);
        this.ref = ref;
    }

    public PacketRemoteMeAction(int windowId, int action, ItemStack kind, int amount) {
        this.windowId = windowId;
        this.action = action;
        this.kind = kind;
        this.amount = amount;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        windowId = buf.readInt();
        action = buf.readUnsignedByte();
        kind = ByteBufUtils.readItemStack(buf);
        amount = buf.readInt();
        ref = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(windowId);
        buf.writeByte(action);
        ByteBufUtils.writeItemStack(buf, kind);
        buf.writeInt(amount);
        buf.writeLong(ref);
    }

    public static class Handler implements IMessageHandler<PacketRemoteMeAction, IMessage> {

        @Override
        public IMessage onMessage(PacketRemoteMeAction m, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() == null ? null : ctx.getServerHandler().playerEntity;
            if (player == null) {
                return null;
            }
            SupplyServerTasks.run(() -> {
                switch (m.action) {
                    case RemoteMe.ACTION_EXTRACT -> RemoteMe.extract(player, m.windowId, m.kind, m.amount);
                    case RemoteMe.ACTION_INSERT_ALL -> RemoteMe.insertCursor(player, m.windowId, false);
                    case RemoteMe.ACTION_INSERT_ONE -> RemoteMe.insertCursor(player, m.windowId, true);
                    case RemoteMe.ACTION_CRAFT -> RemoteMe.craft(player, m.windowId, m.kind, m.amount);
                    case RemoteMe.ACTION_PLAN -> RemoteMe.plan(player, m.windowId, m.kind, m.amount);
                    case RemoteMe.ACTION_START -> RemoteMe.startPlan(player, m.windowId, m.kind, m.ref, m.amount);
                    default -> {}
                }
            });
            return null;
        }
    }
}

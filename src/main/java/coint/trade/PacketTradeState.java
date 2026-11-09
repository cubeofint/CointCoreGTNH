package coint.trade;

import net.minecraft.item.ItemStack;

import coint.client.ClientTrade;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Server to client: the trade as this player sees it, or that it is over. */
public class PacketTradeState implements IMessage {

    public boolean open;
    public String partner = "";
    public boolean myReady, partnerReady, myConfirm, partnerConfirm;
    /** My offers: the inventory slot, and the items as offered (with the amount). */
    public int[] mySlots = new int[0];
    public ItemStack[] myItems = new ItemStack[0];
    public ItemStack[] partnerItems = new ItemStack[0];
    /** Inventory slots of mine the partner may not receive, and the epoch each needs. */
    public int[] blockedSlots = new int[0];
    public String[] blockedEpochs = new String[0];
    public String[] chat = new String[0];
    public String notice = "";

    public PacketTradeState() {}

    static PacketTradeState closed() {
        return new PacketTradeState();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        open = buf.readBoolean();
        if (!open) {
            return;
        }
        partner = ByteBufUtils.readUTF8String(buf);
        int flags = buf.readByte();
        myReady = (flags & 1) != 0;
        partnerReady = (flags & 2) != 0;
        myConfirm = (flags & 4) != 0;
        partnerConfirm = (flags & 8) != 0;
        int n = buf.readUnsignedByte();
        mySlots = new int[n];
        myItems = new ItemStack[n];
        for (int i = 0; i < n; i++) {
            mySlots[i] = buf.readUnsignedByte();
            myItems[i] = readStack(buf);
        }
        n = buf.readUnsignedByte();
        partnerItems = new ItemStack[n];
        for (int i = 0; i < n; i++) {
            partnerItems[i] = readStack(buf);
        }
        n = buf.readUnsignedByte();
        blockedSlots = new int[n];
        blockedEpochs = new String[n];
        for (int i = 0; i < n; i++) {
            blockedSlots[i] = buf.readUnsignedByte();
            blockedEpochs[i] = ByteBufUtils.readUTF8String(buf);
        }
        n = buf.readUnsignedByte();
        chat = new String[n];
        for (int i = 0; i < n; i++) {
            chat[i] = ByteBufUtils.readUTF8String(buf);
        }
        notice = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(open);
        if (!open) {
            return;
        }
        ByteBufUtils.writeUTF8String(buf, partner);
        buf.writeByte((myReady ? 1 : 0) | (partnerReady ? 2 : 0) | (myConfirm ? 4 : 0) | (partnerConfirm ? 8 : 0));
        buf.writeByte(mySlots.length);
        for (int i = 0; i < mySlots.length; i++) {
            buf.writeByte(mySlots[i]);
            writeStack(buf, myItems[i]);
        }
        buf.writeByte(partnerItems.length);
        for (ItemStack stack : partnerItems) {
            writeStack(buf, stack);
        }
        buf.writeByte(blockedSlots.length);
        for (int i = 0; i < blockedSlots.length; i++) {
            buf.writeByte(blockedSlots[i]);
            ByteBufUtils.writeUTF8String(buf, blockedEpochs[i]);
        }
        buf.writeByte(chat.length);
        for (String line : chat) {
            ByteBufUtils.writeUTF8String(buf, line);
        }
        ByteBufUtils.writeUTF8String(buf, notice == null ? "" : notice);
    }

    /** The amount goes apart: the stack's own byte would cut amounts over 127. */
    private static void writeStack(ByteBuf buf, ItemStack stack) {
        ByteBufUtils.writeItemStack(buf, stack);
        buf.writeInt(stack == null ? 0 : stack.stackSize);
    }

    private static ItemStack readStack(ByteBuf buf) {
        ItemStack stack = ByteBufUtils.readItemStack(buf);
        int count = buf.readInt();
        if (stack != null) {
            stack.stackSize = count;
        }
        return stack;
    }

    public static class Handler implements IMessageHandler<PacketTradeState, IMessage> {

        @Override
        public IMessage onMessage(PacketTradeState m, MessageContext ctx) {
            ClientTrade.receive(m);
            return null;
        }
    }
}

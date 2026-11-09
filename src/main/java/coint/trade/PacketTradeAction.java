package coint.trade;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Client to server: what the player does in the trade window (the server checks it is allowed). */
public class PacketTradeAction implements IMessage {

    /** Put {@code count} of inventory slot {@code a} on the table (adds to what is there). */
    public static final int ADD = 0;
    /** Take back {@code count} of offer {@code a}; 0: all of it. */
    public static final int REMOVE = 1;
    /** Ready, or take it back (which takes back the partner's too). */
    public static final int READY = 2;
    /** The final press, when both are ready. */
    public static final int CONFIRM = 3;
    /** The window was closed: the trade is called off. */
    public static final int CLOSE = 4;
    /** A line in the trade window's chat. */
    public static final int CHAT = 5;

    private int action, a, count;
    private String text = "";

    public PacketTradeAction() {}

    public PacketTradeAction(int action, int a, int count, String text) {
        this.action = action;
        this.a = a;
        this.count = count;
        this.text = text == null ? "" : text;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        a = buf.readShort();
        count = buf.readInt();
        text = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeShort(a);
        buf.writeInt(count);
        ByteBufUtils.writeUTF8String(buf, text.length() > 256 ? text.substring(0, 256) : text);
    }

    public static class Handler implements IMessageHandler<PacketTradeAction, IMessage> {

        @Override
        public IMessage onMessage(PacketTradeAction m, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() == null ? null : ctx.getServerHandler().playerEntity;
            if (player != null) {
                TradeManager.act(player, m.action, m.a, m.count, m.text);
            }
            return null;
        }
    }
}

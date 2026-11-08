package coint.cards;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Client to server: what the player does at his card table (the server checks it is allowed). */
public class PacketCardAction implements IMessage {

    public static final int OPEN = CardTables.ACTION_OPEN, LEAVE = CardTables.ACTION_LEAVE,
        START = CardTables.ACTION_START, PLAY = CardTables.ACTION_PLAY, DRAW = CardTables.ACTION_DRAW,
        PASS = CardTables.ACTION_PASS, ADD_BOT = CardTables.ACTION_ADD_BOT, REMOVE_BOT = CardTables.ACTION_REMOVE_BOT,
        CALL = CardTables.ACTION_CALL;

    private int action, index, suit;

    public PacketCardAction() {}

    /** {@code index}: the card in the hand (PLAY); {@code suit}: the suit picked for a suitless card, or -1. */
    public PacketCardAction(int action, int index, int suit) {
        this.action = action;
        this.index = index;
        this.suit = suit;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        index = buf.readShort();
        suit = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeShort(index);
        buf.writeByte(suit);
    }

    public static class Handler implements IMessageHandler<PacketCardAction, IMessage> {

        @Override
        public IMessage onMessage(PacketCardAction m, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() == null ? null : ctx.getServerHandler().playerEntity;
            if (player != null) {
                CardTables.act(player, m.action, m.index, m.suit);
            }
            return null;
        }
    }
}

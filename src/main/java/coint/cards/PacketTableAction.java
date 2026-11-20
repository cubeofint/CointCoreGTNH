package coint.cards;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Board-game action; the server validates table membership, position, ownership and state revision. */
public class PacketTableAction implements IMessage {

    public static final int SELECT = 0, LEAVE = 1, DEAL = 2, DRAW = 3, MOVE = 4, UNDO = 5, FOLD = 6, CALL = 7,
        RAISE = 8, ADD_BOT = 9, REMOVE_BOT = 10;

    int dim, x, y, z, action, a, b, c, revision;
    long session;

    public PacketTableAction() {}

    public PacketTableAction(PacketTableState state, int action, int a, int b, int c) {
        dim = state.dim;
        x = state.x;
        y = state.y;
        z = state.z;
        revision = state.revision;
        session = state.session;
        this.action = action;
        this.a = a;
        this.b = b;
        this.c = c;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim)
            .writeInt(x)
            .writeInt(y)
            .writeInt(z)
            .writeByte(action)
            .writeInt(a)
            .writeInt(b)
            .writeInt(c)
            .writeInt(revision)
            .writeLong(session);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        action = buf.readUnsignedByte();
        a = buf.readInt();
        b = buf.readInt();
        c = buf.readInt();
        revision = buf.readInt();
        session = buf.readLong();
    }

    public static final class Handler implements IMessageHandler<PacketTableAction, IMessage> {

        @Override
        public IMessage onMessage(PacketTableAction message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler() == null ? null : context.getServerHandler().playerEntity;
            if (player != null) TableGames.act(player, message);
            return null;
        }
    }
}

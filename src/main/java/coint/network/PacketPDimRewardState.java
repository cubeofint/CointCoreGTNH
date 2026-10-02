package coint.network;

import coint.CointCore;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketPDimRewardState implements IMessage {

    public boolean blocked;

    public PacketPDimRewardState() {}

    public PacketPDimRewardState(boolean blocked) {
        this.blocked = blocked;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        blocked = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(blocked);
    }

    public static class Handler implements IMessageHandler<PacketPDimRewardState, IMessage> {

        @Override
        public IMessage onMessage(PacketPDimRewardState message, MessageContext ctx) {
            CointCore.proxy.setPDimRewardBlockedClient(message.blocked);
            return null;
        }
    }
}

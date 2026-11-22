package coint.chat;

import coint.CointCore;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Optional server-to-client capability; never an authorization token accepted from a client. */
public final class PacketAdminChatAccess implements IMessage {

    public boolean allowed;

    public PacketAdminChatAccess() {}

    public PacketAdminChatAccess(boolean allowed) {
        this.allowed = allowed;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        allowed = buffer.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeBoolean(allowed);
    }

    public static final class Handler implements IMessageHandler<PacketAdminChatAccess, IMessage> {

        @Override
        public IMessage onMessage(PacketAdminChatAccess message, MessageContext context) {
            CointCore.proxy.receiveAdminChatAccess(message.allowed);
            return null;
        }
    }
}

package coint.chat;

import net.minecraft.util.IChatComponent;

import coint.CointCore;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** -1 activates the tabs on login; other values deliver a component with its channel. */
public class PacketChatChannel implements IMessage {

    public int channel = -1;
    public String json = "";

    public PacketChatChannel() {}

    public PacketChatChannel(ChatChannel channel, IChatComponent component) {
        this.channel = channel.ordinal();
        json = IChatComponent.Serializer.func_150696_a(component);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        channel = buf.readByte();
        json = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(channel);
        ByteBufUtils.writeUTF8String(buf, json);
    }

    public static class Handler implements IMessageHandler<PacketChatChannel, IMessage> {

        @Override
        public IMessage onMessage(PacketChatChannel message, MessageContext ctx) {
            CointCore.proxy.receiveChatChannel(message);
            return null;
        }
    }
}

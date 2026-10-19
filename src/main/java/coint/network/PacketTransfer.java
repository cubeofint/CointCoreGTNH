package coint.network;

import coint.client.ClientTransfer;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Server to client: leave this server and join another one of the network. */
public class PacketTransfer implements IMessage {

    private String name;
    private String address;

    public PacketTransfer() {}

    public PacketTransfer(String name, String address) {
        this.name = name == null ? "" : name;
        this.address = address == null ? "" : address;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = ByteBufUtils.readUTF8String(buf);
        address = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeUTF8String(buf, address);
    }

    public static class Handler implements IMessageHandler<PacketTransfer, IMessage> {

        @Override
        public IMessage onMessage(PacketTransfer message, MessageContext ctx) {
            ClientTransfer.schedule(message.name, message.address);
            return null;
        }
    }
}

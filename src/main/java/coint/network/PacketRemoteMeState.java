package coint.network;

import coint.client.ClientRemoteMe;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: the state of an open remote ME window, and a part of what the network holds (compressed NBT, split
 * over several packets when large; {@code parts} 0 carries no snapshot).
 */
public class PacketRemoteMeState implements IMessage {

    private int windowId;
    private String title, status;
    private long version;
    private int part, parts;
    private byte[] data;

    public PacketRemoteMeState() {}

    public PacketRemoteMeState(int windowId, String title, String status, long version, int part, int parts,
        byte[] data) {
        this.windowId = windowId;
        this.title = title == null ? "" : title;
        this.status = status == null ? "" : status;
        this.version = version;
        this.part = part;
        this.parts = parts;
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        windowId = buf.readInt();
        title = ByteBufUtils.readUTF8String(buf);
        status = ByteBufUtils.readUTF8String(buf);
        version = buf.readLong();
        part = buf.readUnsignedShort();
        parts = buf.readUnsignedShort();
        data = new byte[buf.readInt()];
        buf.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(windowId);
        ByteBufUtils.writeUTF8String(buf, title);
        ByteBufUtils.writeUTF8String(buf, status);
        buf.writeLong(version);
        buf.writeShort(part);
        buf.writeShort(parts);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    public static class Handler implements IMessageHandler<PacketRemoteMeState, IMessage> {

        @Override
        public IMessage onMessage(PacketRemoteMeState m, MessageContext ctx) {
            ClientRemoteMe.apply(m.windowId, m.title, m.status, m.version, m.part, m.parts, m.data);
            return null;
        }
    }
}

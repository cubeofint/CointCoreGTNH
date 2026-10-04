package coint.network;

import java.util.ArrayList;
import java.util.List;

import coint.CointCore;
import coint.worldtravel.WorldTravelViewEntry;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketOpenWorlds implements IMessage {

    public String title = "Путешествия";
    public final List<WorldTravelViewEntry> entries = new ArrayList<>();

    public PacketOpenWorlds() {}

    public PacketOpenWorlds(String title, List<WorldTravelViewEntry> entries) {
        this.title = title;
        this.entries.addAll(entries);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        title = ByteBufUtils.readUTF8String(buf);
        int count = Math.max(0, Math.min(buf.readInt(), 256));
        entries.clear();
        for (int i = 0; i < count; i++) {
            WorldTravelViewEntry entry = new WorldTravelViewEntry();
            entry.id = ByteBufUtils.readUTF8String(buf);
            entry.name = ByteBufUtils.readUTF8String(buf);
            entry.description = ByteBufUtils.readUTF8String(buf);
            entry.requirementText = ByteBufUtils.readUTF8String(buf);
            entry.tier = buf.readInt();
            entry.dimension = buf.readInt();
            entry.accessible = buf.readBoolean();
            entries.add(entry);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, title == null ? "" : title);
        buf.writeInt(entries.size());
        for (WorldTravelViewEntry entry : entries) {
            ByteBufUtils.writeUTF8String(buf, entry.id == null ? "" : entry.id);
            ByteBufUtils.writeUTF8String(buf, entry.name == null ? "" : entry.name);
            ByteBufUtils.writeUTF8String(buf, entry.description == null ? "" : entry.description);
            ByteBufUtils.writeUTF8String(buf, entry.requirementText == null ? "" : entry.requirementText);
            buf.writeInt(entry.tier);
            buf.writeInt(entry.dimension);
            buf.writeBoolean(entry.accessible);
        }
    }

    public static class Handler implements IMessageHandler<PacketOpenWorlds, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenWorlds message, MessageContext ctx) {
            CointCore.proxy.queueWorldTravelGui(message);
            return null;
        }
    }
}

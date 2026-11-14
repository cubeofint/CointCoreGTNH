package coint.migration;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;

import coint.client.GuiMigration;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/** Independent channel: register on both sides during common init. Actions use permission-checked commands. */
public final class MigrationNetwork {

    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("cointmigrate");
    private static boolean initialized;

    private MigrationNetwork() {}

    public static synchronized void init() {
        if (initialized) return;
        CHANNEL.registerMessage(Handler.class, State.class, 0, Side.CLIENT);
        CHANNEL.registerMessage(ConfirmHandler.class, Confirm.class, 1, Side.CLIENT);
        initialized = true;
    }

    public static void open(EntityPlayerMP player, State state) {
        if (!initialized) {
            player.addChatMessage(new net.minecraft.util.ChatComponentText("MigrationNetwork.init() не подключён"));
            return;
        }
        CHANNEL.sendTo(state, player);
    }

    public static void confirm(EntityPlayerMP player, String id, String target) {
        if (initialized) CHANNEL.sendTo(new Confirm(id, target), player);
    }

    public static class Confirm implements IMessage {

        public String id, target;

        public Confirm() {}

        public Confirm(String id, String target) {
            this.id = id;
            this.target = target;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            ByteBufUtils.writeUTF8String(buf, id);
            ByteBufUtils.writeUTF8String(buf, target);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            id = ByteBufUtils.readUTF8String(buf);
            target = ByteBufUtils.readUTF8String(buf);
            java.util.UUID.fromString(id);
            if (!target.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid destination");
        }
    }

    public static class ConfirmHandler implements IMessageHandler<Confirm, IMessage> {

        @Override
        public IMessage onMessage(Confirm state, MessageContext context) {
            coint.client.GuiMigrationConfirm.receive(state);
            return null;
        }
    }

    public static class State implements IMessage {

        public String current = "";
        public final List<String> nodes = new ArrayList<>();
        public final List<String> names = new ArrayList<>();
        public final List<String> archives = new ArrayList<>();
        /** id, archive, source, status; parallel arrays are serialized together. */
        public final List<String[]> requests = new ArrayList<>();

        public State() {}

        @Override
        public void toBytes(ByteBuf buf) {
            ByteBufUtils.writeUTF8String(buf, current);
            write(buf, nodes, 64);
            write(buf, names, 1000);
            write(buf, archives, 256);
            int count = Math.min(30, requests.size());
            buf.writeInt(count);
            for (int i = 0; i < count; i++) {
                for (String field : requests.get(i)) ByteBufUtils.writeUTF8String(buf, field);
            }
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            current = ByteBufUtils.readUTF8String(buf);
            read(buf, nodes, 64);
            read(buf, names, 1000);
            read(buf, archives, 256);
            int count = count(buf, 30);
            for (int i = 0; i < count; i++) {
                String[] row = new String[4];
                for (int j = 0; j < 4; j++) row[j] = ByteBufUtils.readUTF8String(buf);
                requests.add(row);
            }
        }

        private static void write(ByteBuf buf, List<String> values, int max) {
            int count = Math.min(max, values.size());
            buf.writeInt(count);
            for (int i = 0; i < count; i++) ByteBufUtils.writeUTF8String(buf, values.get(i));
        }

        private static void read(ByteBuf buf, List<String> values, int max) {
            int count = count(buf, max);
            for (int i = 0; i < count; i++) values.add(ByteBufUtils.readUTF8String(buf));
        }

        private static int count(ByteBuf buf, int max) {
            int count = buf.readInt();
            if (count < 0 || count > max) throw new IllegalArgumentException("Invalid migration list size");
            return count;
        }
    }

    public static class Handler implements IMessageHandler<State, IMessage> {

        @Override
        public IMessage onMessage(State state, MessageContext context) {
            GuiMigration.receive(state);
            return null;
        }
    }
}

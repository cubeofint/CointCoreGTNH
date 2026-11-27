package coint.cluster;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import coint.poll.PollDefinition;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

@EventBusSubscriber
public final class PollNetwork {

    private static final String NAME = "cointpoll";
    private static final Set<NetworkManager> supported = Collections
        .synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static SimpleNetworkWrapper channel;

    private PollNetwork() {}

    public static void init() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(NAME);
        channel.registerMessage(Prompt.Handler.class, Prompt.class, 0, Side.CLIENT);
        channel.registerMessage(Reply.Handler.class, Reply.class, 1, Side.CLIENT);
        channel.registerMessage(Vote.Handler.class, Vote.class, 2, Side.SERVER);
    }

    @SubscribeEvent
    public static void registration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.side != Side.SERVER || !event.registrations.contains(NAME)) return;
        if ("REGISTER".equals(event.operation)) supported.add(event.manager);
        else supported.remove(event.manager);
    }

    static NetworkManager connection(EntityPlayerMP player) {
        return player.playerNetServerHandler == null ? null : player.playerNetServerHandler.netManager;
    }

    static boolean supports(EntityPlayerMP player) {
        NetworkManager connection = connection(player);
        return connection != null && connection.isChannelOpen() && supported.contains(connection);
    }

    static void clearConnections() {
        supported.clear();
    }

    static void prompt(EntityPlayerMP player, Prompt prompt) {
        channel.sendTo(prompt, player);
    }

    static void reply(EntityPlayerMP player, String id, Outcome outcome) {
        channel.sendTo(new Reply(id, outcome), player);
    }

    public static void vote(String id, int choice) {
        channel.sendToServer(new Vote(id, choice));
    }

    public enum Outcome {
        SAVED,
        CLOSED,
        RETRY
    }

    public static final class Prompt implements IMessage {

        public PollDefinition definition;
        public long timeLeft;
        private String json;

        public Prompt() {}

        Prompt(PollDefinition definition, long timeLeft) {
            this.definition = definition;
            this.timeLeft = timeLeft;
            json = definition.toJson();
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            timeLeft = buffer.readLong();
            json = ByteBufUtils.readUTF8String(buffer);
            definition = PollDefinition.parse(json);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeLong(timeLeft);
            ByteBufUtils.writeUTF8String(buffer, json);
        }

        public static final class Handler implements IMessageHandler<Prompt, IMessage> {

            @Override
            public IMessage onMessage(Prompt message, MessageContext context) {
                CointCore.proxy.receivePoll(
                    context.getClientHandler()
                        .getNetworkManager(),
                    message);
                return null;
            }
        }
    }

    public static final class Reply implements IMessage {

        public String id;
        public Outcome outcome;

        public Reply() {}

        private Reply(String id, Outcome outcome) {
            this.id = id;
            this.outcome = outcome;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            id = ByteBufUtils.readUTF8String(buffer);
            int value = buffer.readUnsignedByte();
            if (value >= Outcome.values().length) throw new IllegalArgumentException("Invalid poll outcome");
            outcome = Outcome.values()[value];
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            ByteBufUtils.writeUTF8String(buffer, id);
            buffer.writeByte(outcome.ordinal());
        }

        public static final class Handler implements IMessageHandler<Reply, IMessage> {

            @Override
            public IMessage onMessage(Reply message, MessageContext context) {
                CointCore.proxy.receivePollReply(
                    context.getClientHandler()
                        .getNetworkManager(),
                    message);
                return null;
            }
        }
    }

    public static final class Vote implements IMessage {

        private String id;
        private int choice;

        public Vote() {}

        private Vote(String id, int choice) {
            this.id = id;
            this.choice = choice;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            id = ByteBufUtils.readUTF8String(buffer);
            choice = buffer.readUnsignedByte();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            ByteBufUtils.writeUTF8String(buffer, id);
            buffer.writeByte(choice);
        }

        public static final class Handler implements IMessageHandler<Vote, IMessage> {

            @Override
            public IMessage onMessage(Vote message, MessageContext context) {
                EntityPlayerMP player = context.getServerHandler().playerEntity;
                if (PollDefinition.validId(message.id) && message.choice < PollDefinition.MAX_ANSWERS) {
                    SupplyServerTasks.run(() -> ClusterPolls.vote(player, message.id, message.choice));
                }
                return null;
            }
        }
    }
}

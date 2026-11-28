package coint.integration.appliedenergistics2;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.ChatComponentText;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/** The server determines the change and keeps the original AE2 container open during confirmation. */
@EventBusSubscriber
public final class LiteCraftingMode {

    private static final String CHANNEL_NAME = "cointlitecraft";
    private static final Set<NetworkManager> supported = Collections
        .synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Map<UUID, Pending> pending = new HashMap<>();
    private static SimpleNetworkWrapper channel;
    private static long nextRequestId;

    private LiteCraftingMode() {}

    public static boolean isEndgame() {
        return CointConfig.cluster.enabled && "endgame".equalsIgnoreCase(CointConfig.cluster.nodeId.trim());
    }

    public static void initNetwork() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);
        channel.registerMessage(Prompt.Handler.class, Prompt.class, 0, Side.CLIENT);
        channel.registerMessage(Reply.Handler.class, Reply.class, 1, Side.SERVER);
    }

    @SubscribeEvent
    public static void registration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.side != Side.SERVER || !event.registrations.contains(CHANNEL_NAME)) return;
        if ("REGISTER".equals(event.operation)) supported.add(event.manager);
        else supported.remove(event.manager);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player) pending.remove(player.getUniqueID());
    }

    public static void reset() {
        pending.clear();
        supported.clear();
    }

    public static void change(EntityPlayerMP player, Container container, Runnable action) {
        SupplyServerTasks.run(() -> {
            if (!canChange(player, container)) return;
            pending.remove(player.getUniqueID());
            action.run();
        });
    }

    public static void confirm(EntityPlayerMP player, Container container, Scope scope, Runnable apply) {
        if (!supported.contains(player.playerNetServerHandler.netManager)) {
            player.addChatMessage(new ChatComponentText("Для отключения Lite Crafting обновите сборку через лаунчер."));
            return;
        }
        long id = ++nextRequestId;
        pending.put(player.getUniqueID(), new Pending(id, container, apply));
        channel.sendTo(new Prompt(id, container.windowId, scope), player);
    }

    public static void reply(long id, boolean accepted) {
        channel.sendToServer(new Reply(id, accepted));
    }

    private static boolean canChange(EntityPlayerMP player, Container container) {
        return isEndgame() && player.openContainer == container
            && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager.isChannelOpen()
            && container.canInteractWith(player);
    }

    public enum Scope {
        NETWORK,
        MAINTAINER,
        INHERIT
    }

    private static final class Pending {

        final long id;
        final Container container;
        final Runnable apply;

        Pending(long id, Container container, Runnable apply) {
            this.id = id;
            this.container = container;
            this.apply = apply;
        }
    }

    public static final class Prompt implements IMessage {

        public long id;
        public int windowId;
        public Scope scope;

        public Prompt() {}

        private Prompt(long id, int windowId, Scope scope) {
            this.id = id;
            this.windowId = windowId;
            this.scope = scope;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            id = buffer.readLong();
            windowId = buffer.readInt();
            scope = Scope.values()[buffer.readUnsignedByte()];
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeLong(id);
            buffer.writeInt(windowId);
            buffer.writeByte(scope.ordinal());
        }

        public static final class Handler implements IMessageHandler<Prompt, IMessage> {

            @Override
            public IMessage onMessage(Prompt message, MessageContext context) {
                CointCore.proxy.receiveLiteCraftingPrompt(
                    context.getClientHandler()
                        .getNetworkManager(),
                    message);
                return null;
            }
        }
    }

    public static final class Reply implements IMessage {

        private long id;
        private boolean accepted;

        public Reply() {}

        private Reply(long id, boolean accepted) {
            this.id = id;
            this.accepted = accepted;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            id = buffer.readLong();
            accepted = buffer.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeLong(id);
            buffer.writeBoolean(accepted);
        }

        public static final class Handler implements IMessageHandler<Reply, IMessage> {

            @Override
            public IMessage onMessage(Reply message, MessageContext context) {
                EntityPlayerMP player = context.getServerHandler().playerEntity;
                SupplyServerTasks.run(() -> {
                    UUID uuid = player.getUniqueID();
                    Pending request = pending.get(uuid);
                    if (request == null || request.id != message.id) return;
                    pending.remove(uuid);
                    if (message.accepted && canChange(player, request.container)) request.apply.run();
                });
                return null;
            }
        }
    }
}

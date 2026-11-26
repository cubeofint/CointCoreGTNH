package coint.events;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import coint.cluster.ClusterHub;
import coint.cluster.ClusterService;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/** The Hub server thread owns the timer. The optional channel preserves compatibility with older Hub clients. */
@EventBusSubscriber
public final class EventNei {

    private static final String CHANNEL_NAME = "cointeventnei";
    private static final Set<NetworkManager> supported = Collections
        .synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static SimpleNetworkWrapper channel;
    private static long hiddenUntil;

    private EventNei() {}

    public static void initNetwork() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);
        channel.registerMessage(Visibility.Handler.class, Visibility.class, 0, Side.CLIENT);
    }

    public static boolean available() {
        return ClusterService.isRunning() && ClusterHub.isHub();
    }

    public static int hide(int minutes) {
        hiddenUntil = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes);
        return broadcast(true);
    }

    public static void show() {
        hiddenUntil = 0;
        broadcast(false);
    }

    public static void reset() {
        hiddenUntil = 0;
        supported.clear();
    }

    @SubscribeEvent
    public static void registration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.side != Side.SERVER || !event.registrations.contains(CHANNEL_NAME)) return;
        if ("REGISTER".equals(event.operation)) supported.add(event.manager);
        else supported.remove(event.manager);
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (hiddenUntil > System.currentTimeMillis() && available() && event.player instanceof EntityPlayerMP player) {
            send(player, true);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && hiddenUntil != 0
            && (hiddenUntil <= System.currentTimeMillis() || !available())) {
            show();
        }
    }

    private static int broadcast(boolean hidden) {
        int unsupportedClients = 0;
        for (EntityPlayerMP player : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            if (!send(player, hidden)) unsupportedClients++;
        }
        return unsupportedClients;
    }

    private static boolean send(EntityPlayerMP player, boolean hidden) {
        if (player.playerNetServerHandler == null) return false;
        NetworkManager connection = player.playerNetServerHandler.netManager;
        if (!connection.isChannelOpen() || !supported.contains(connection)) return false;
        channel.sendTo(new Visibility(hidden), player);
        return true;
    }

    public static final class Visibility implements IMessage {

        private boolean hidden;

        public Visibility() {}

        private Visibility(boolean hidden) {
            this.hidden = hidden;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            hidden = buffer.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeBoolean(hidden);
        }

        public static final class Handler implements IMessageHandler<Visibility, IMessage> {

            @Override
            public IMessage onMessage(Visibility message, MessageContext context) {
                CointCore.proxy.receiveEventNei(
                    context.getClientHandler()
                        .getNetworkManager(),
                    message.hidden);
                return null;
            }
        }
    }
}

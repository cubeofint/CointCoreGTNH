package coint.cluster;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.network.NetworkManager;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/** Optional channel: the existing hub protocol remains compatible with earlier CointCore builds. */
@EventBusSubscriber
public final class TeamHistoryNetwork {

    private static final String NAME = "cointhistory";
    public static SimpleNetworkWrapper CHANNEL;
    private static final Set<NetworkManager> supported = Collections
        .synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static boolean initialized;

    private TeamHistoryNetwork() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(NAME);
        CHANNEL.registerMessage(PacketTeamHistory.Handler.class, PacketTeamHistory.class, 0, Side.CLIENT);
        CHANNEL
            .registerMessage(PacketTeamHistory.RequestHandler.class, PacketTeamHistory.Request.class, 1, Side.SERVER);
    }

    @SubscribeEvent
    public static void registration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (!event.registrations.contains(NAME)) return;
        if ("REGISTER".equals(event.operation)) supported.add(event.manager);
        else supported.remove(event.manager);
    }

    public static boolean supported(NetworkManager manager) {
        return supported.contains(manager);
    }
}

package coint.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IChatComponent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.network.WorldTravelNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/** Only delivered lines are tagged: selecting a tab never widens permissions or local-chat range. */
@EventBusSubscriber
public final class ChatChannels {

    private static final Map<UUID, Long> LAST_TRADE = new ConcurrentHashMap<>();

    private ChatChannels() {}

    public static void send(ICommandSender recipient, ChatChannel channel, IChatComponent component) {
        if (CointConfig.chat.tabsEnabled && recipient instanceof EntityPlayerMP player) {
            if (player.func_147096_v() == net.minecraft.entity.player.EntityPlayer.EnumChatVisibility.HIDDEN) return;
            WorldTravelNetwork.CHANNEL.sendTo(new PacketChatChannel(channel, component), player);
        } else {
            recipient.addChatMessage(component);
        }
    }

    public static void broadcast(ChatChannel channel, IChatComponent component) {
        MinecraftServer server = MinecraftServer.getServer();
        for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList)
            send(player, channel, component);
        server.addChatMessage(component);
    }

    public static synchronized long tradeCooldown(UUID player, long now) {
        long cooldown = Math.max(0, CointConfig.chat.tradeCooldownSeconds) * 1000L;
        Long last = LAST_TRADE.get(player);
        if (last != null && now >= last && now - last < cooldown) return (cooldown - (now - last) + 999L) / 1000L;
        LAST_TRADE.put(player, now);
        return 0;
    }

    @SubscribeEvent
    public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (CointConfig.chat.tabsEnabled && event.player instanceof EntityPlayerMP player) {
            WorldTravelNetwork.CHANNEL.sendTo(new PacketChatChannel(), player);
        }
    }

    @SubscribeEvent
    public static void left(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_TRADE.remove(event.player.getUniqueID());
    }

    public static void reset() {
        LAST_TRADE.clear();
    }
}

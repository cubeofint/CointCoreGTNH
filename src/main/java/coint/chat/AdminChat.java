package coint.chat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.cluster.ClusterAdminChat;
import coint.cluster.ClusterSanctions;
import coint.player.CointPlayer;
import coint.player.Mute;
import coint.util.ChatUtil;
import coint.util.TimeUtil;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import serverutils.lib.util.permission.PermissionAPI;

/** Permission checks on both send and delivery; no public-chat, Discord or spy broadcast. */
@EventBusSubscriber
public final class AdminChat {

    public static final String PERMISSION = "cointcore.adminchat";
    public static final String COMMAND_PREFIX = "/adminchat ";
    public static final int MAX_MESSAGE = 1024;
    private static final String CHANNEL_NAME = "cointadmin";
    private static final Set<NetworkManager> capable = Collections
        .synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Map<UUID, Boolean> accessSent = new HashMap<>();
    private static SimpleNetworkWrapper channel;
    private static long nextSync;

    private AdminChat() {}

    public static void initNetwork() {
        if (channel != null) return;
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);
        channel.registerMessage(PacketAdminChatAccess.Handler.class, PacketAdminChatAccess.class, 0, Side.CLIENT);
    }

    public static boolean permitted(ICommandSender sender) {
        return !(sender instanceof EntityPlayer player) || PermissionAPI.hasPermission(player, PERMISSION);
    }

    public static boolean canReceive(EntityPlayerMP player) {
        return CointConfig.chat.adminChatEnabled && permitted(player);
    }

    public static boolean supportsTabs(EntityPlayerMP player) {
        return player.playerNetServerHandler != null && capable.contains(player.playerNetServerHandler.netManager);
    }

    public static boolean validMessage(String message) {
        return message != null && !message.trim()
            .isEmpty() && message.length() <= MAX_MESSAGE && message.indexOf('\n') < 0 && message.indexOf('\r') < 0;
    }

    public static void send(ICommandSender sender, String message) {
        // Recheck here as well as in CommandBase: a selected client tab never grants a permission.
        if (!permitted(sender)) {
            sender.addChatMessage(new ChatComponentTranslation("commands.generic.permission"));
            return;
        }
        if (!CointConfig.chat.adminChatEnabled) {
            sender.addChatMessage(new ChatComponentTranslation("cointcore.chat.adminDisabled"));
            return;
        }
        if (!validMessage(message)) {
            sender.addChatMessage(new ChatComponentTranslation("cointcore.chat.adminInvalid", MAX_MESSAGE));
            return;
        }
        if (sender instanceof EntityPlayerMP player) {
            Mute mute = ClusterSanctions.knowsMute(player.getUniqueID()) ? ClusterSanctions.mute(player.getUniqueID())
                : CointPlayer.get(player.getCommandSenderName())
                    .getMute();
            if (mute != null && !mute.isExpired()) {
                player.addChatMessage(
                    new ChatComponentText(
                        "§cВаш чат заблокирован по причине: " + mute.reason
                            + ". Доступен через §6"
                            + TimeUtil.formatDuration(mute.expiresAt - System.currentTimeMillis())));
                return;
            }
        }
        String text = message.trim();
        String display = sender instanceof EntityPlayerMP player ? ChatUtil.getRankFormattedName(player)
            : sender.getCommandSenderName();
        receive(display, text, null);
        ClusterAdminChat.publish(sender.getCommandSenderName(), display, text);
    }

    /** The originating node was responsible for authenticating the sender; every receiver is checked locally. */
    public static void receive(String display, String text, String node) {
        if (!CointConfig.chat.adminChatEnabled || display == null || !validMessage(text)) return;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return;
        IChatComponent component = ChatUtil.getChatMessage(display, "§f" + text, "§cA§7", node);
        deliverTo(server.getConfigurationManager().playerEntityList, component);
        server.addChatMessage(component);
    }

    static void deliverTo(Iterable<EntityPlayerMP> recipients, IChatComponent component) {
        for (EntityPlayerMP player : recipients) {
            ChatChannels.send(player, ChatChannel.ADMIN, component.createCopy());
        }
    }

    @SubscribeEvent
    public static void registration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (!event.registrations.contains(CHANNEL_NAME)) return;
        if ("REGISTER".equals(event.operation)) capable.add(event.manager);
        else capable.remove(event.manager);
    }

    private static void sync(EntityPlayerMP player) {
        UUID uuid = player.getUniqueID();
        if (!supportsTabs(player)) {
            accessSent.remove(uuid);
            return;
        }
        boolean allowed = CointConfig.chat.tabsEnabled && canReceive(player);
        if (!Boolean.valueOf(allowed)
            .equals(accessSent.get(uuid))) {
            channel.sendTo(new PacketAdminChatAccess(allowed), player);
            accessSent.put(uuid, allowed);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (channel == null || event.phase != TickEvent.Phase.END || System.currentTimeMillis() < nextSync) return;
        nextSync = System.currentTimeMillis() + 1000;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return;
        for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) sync(player);
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        accessSent.remove(event.player.getUniqueID());
        if (channel != null && event.player instanceof EntityPlayerMP player) sync(player);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        accessSent.remove(event.player.getUniqueID());
    }

    public static void reset() {
        accessSent.clear();
        capable.clear();
        nextSync = 0;
    }
}

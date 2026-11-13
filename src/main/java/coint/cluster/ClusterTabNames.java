package coint.cluster;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IChatComponent;

import coint.CointCore;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.net.MessageUpdateTabName;

/**
 * The names the players of the other servers show with in the tab list: their nickname and rank, as their own server
 * makes them. Sent with ServerUtilities' own tab name message, which the client already knows how to show.
 *
 * <p>
 * Server thread only.
 */
final class ClusterTabNames {

    private static Field entriesField;
    private static Constructor<?> entryConstructor;
    private static boolean broken;

    private ClusterTabNames() {}

    /** The tab name of a player here as chat component JSON, or null. */
    static String displayJson(EntityPlayerMP player) {
        try {
            Universe universe = Universe.get();
            ForgePlayer forgePlayer = universe == null ? null : universe.getPlayer(player);
            if (forgePlayer == null) {
                return null;
            }
            IChatComponent name = new net.minecraft.util.ChatComponentText(
                coint.events.TabRankSorter.formattedName(player));
            if (name == null || name.getUnformattedText()
                .isEmpty()) {
                return null;
            }
            return IChatComponent.Serializer.func_150696_a(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Shows {@code names} (player name → tab name JSON) to {@code to}, or to everyone here when null. */
    static void send(Map<String, String> names, EntityPlayerMP to) {
        for (Map.Entry<String, String> e : names.entrySet()) {
            // One per message: the client stops at the first name it has no line for.
            MessageUpdateTabName message = message(e.getKey(), e.getValue());
            if (message == null) {
                return;
            }
            if (to == null) {
                message.sendToAll();
            } else {
                message.sendTo(to);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static MessageUpdateTabName message(String name, String json) {
        if (broken || json == null) {
            return null;
        }
        IChatComponent display;
        try {
            display = IChatComponent.Serializer.func_150699_a(json);
        } catch (RuntimeException e) {
            return null;
        }
        try {
            if (entriesField == null) {
                entriesField = MessageUpdateTabName.class.getDeclaredField("entries");
                entriesField.setAccessible(true);
                Class<?> entry = Class.forName("serverutils.net.MessageUpdateTabName$TabNameEntry");
                entryConstructor = entry.getDeclaredConstructor(String.class, IChatComponent.class, boolean.class);
                entryConstructor.setAccessible(true);
            }
            MessageUpdateTabName message = new MessageUpdateTabName();
            ((Collection<Object>) entriesField.get(message)).add(entryConstructor.newInstance(name, display, false));
            return message;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            CointCore.LOG.warn("[Cluster] Tab names of the other servers cannot be shown: {}", e.toString());
            return null;
        }
    }
}

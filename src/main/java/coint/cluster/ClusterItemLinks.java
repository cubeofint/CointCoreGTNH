package coint.cluster;

import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import codechicken.lib.packet.PacketCustom;
import coint.CointCore;
import coint.integration.discord.DiscordModerationWebhook;
import coint.trade.TradeItems;
import coint.util.ChatUtil;

/** NEI links need both the clickable component and the client bookmark cache, not just their visible text. */
public final class ClusterItemLinks {

    private ClusterItemLinks() {}

    public static void publish(EntityPlayerMP sender, NBTTagCompound payload) {
        if (payload == null) return;
        // The readable notice is independent of the (potentially large) NEI bookmark packet.
        // Exactly the node configured as the global Discord bridge sends it, like normal global chat.
        String notice = describe(payload);
        DiscordModerationWebhook.logGlobal(sender, notice);
        ClusterService.publishItemNotice(sender.getCommandSenderName(), notice);
        if (!ClusterService.isRunning()) return;
        try {
            NBTTagCompound copy = (NBTTagCompound) payload.copy();
            TradeItems.portable(copy);
            String encoded = Base64.getEncoder()
                .encodeToString(CompressedStreamTools.compress(copy));
            // cluster_chat.text is bounded. Never silently forward a truncated bookmark.
            if (encoded.length() > 4000) {
                sender.addChatMessage(
                    new ChatComponentText("§eСсылка слишком большая для межсерверного чата; отправлена только здесь."));
                return;
            }
            ClusterService
                .publishItemLink(sender.getCommandSenderName(), ChatUtil.getRankFormattedName(sender), encoded);
        } catch (Exception e) {
            CointCore.LOG.warn("[Cluster] Could not publish NEI link", e);
        }
    }

    static String describe(NBTTagCompound payload) {
        boolean recipe = "recipe".equals(payload.getString("linkType"));
        if (payload.hasKey("item", 10)) {
            String item = itemName(payload.getCompoundTag("item"));
            return (recipe ? "Рецепт: " : "Предмет: ") + (item == null ? "[неизвестный предмет]" : item);
        }
        NBTTagList items = payload.getTagList("items", 10);
        Set<String> names = new LinkedHashSet<>();
        int shown = Math.min(6, items.tagCount());
        for (int i = 0; i < shown; i++) {
            String name = itemName(
                items.getCompoundTagAt(i)
                    .getCompoundTag("item"));
            if (name != null) names.add(name);
        }
        String title = recipe ? "Рецепт" : "Группа закладок";
        return title + (names.isEmpty() ? " (позиций: " + items.tagCount() + ")" : ": " + String.join(", ", names))
            + (items.tagCount() > shown ? "; ещё позиций: " + (items.tagCount() - shown) : "");
    }

    private static String itemName(NBTTagCompound item) {
        try {
            ItemStack stack = (ItemStack) Class.forName("codechicken.nei.recipe.StackInfo")
                .getMethod("loadFromNBT", NBTTagCompound.class)
                .invoke(null, item);
            if (stack == null) return null;
            String name = stack.getDisplayName()
                .replaceAll("(?i)§[0-9A-FK-OR]", "")
                .replaceAll("\\s+", " ")
                .trim();
            if (name.length() > 160) name = name.substring(0, 157) + "…";
            return "[" + name + "]" + (stack.stackSize > 1 ? " ×" + stack.stackSize : "");
        } catch (Exception e) {
            return null;
        }
    }

    public static void receive(String node, String sender, String encoded) {
        try {
            NBTTagCompound payload = CompressedStreamTools.func_152457_a(
                Base64.getDecoder()
                    .decode(encoded),
                new NBTSizeTracker(2097152L));
            if (!TradeItems.localize(payload)) throw new IllegalArgumentException("Unknown bookmark item");
            IChatComponent clickable;
            if (payload.hasKey("item")) {
                ItemStack stack = (ItemStack) Class.forName("codechicken.nei.recipe.StackInfo")
                    .getMethod("loadFromNBT", NBTTagCompound.class)
                    .invoke(null, payload.getCompoundTag("item"));
                if (stack == null) throw new IllegalArgumentException("Invalid bookmark item");
                clickable = stack.func_151000_E();
            } else {
                clickable = new ChatComponentText("[")
                    .appendSibling(
                        new ChatComponentTranslation("nei.chat.item_link." + payload.getString("linkType") + ".text"))
                    .appendText("]");
            }
            String id = UUID.randomUUID()
                .toString();
            int packetId = Class.forName("codechicken.nei.PacketIDs$S2C")
                .getField("CACHE_BOOKMARK_DATA")
                .getInt(null);
            Object channel = Class.forName("codechicken.nei.NEISPH")
                .getField("channel")
                .get(null);
            PacketCustom packet = new PacketCustom(channel, packetId).writeString(id)
                .writeNBTTagCompound(payload);
            clickable.getChatStyle()
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/nei_bookmark " + id));
            IChatComponent message = ChatUtil.getChatMessage(sender, "", "G", node)
                .appendSibling(clickable);
            for (EntityPlayerMP player : MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList) {
                packet.sendToPlayer(player);
                coint.chat.ChatChannels.send(player, coint.chat.ChatChannel.GLOBAL, message.createCopy());
            }
        } catch (Exception e) {
            CointCore.LOG.warn("[Cluster] Could not receive NEI link from {}", node, e);
        }
    }
}

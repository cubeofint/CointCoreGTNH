package coint.cluster;

import java.util.Base64;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import codechicken.lib.packet.PacketCustom;
import coint.CointCore;
import coint.trade.TradeItems;
import coint.util.ChatUtil;

/** NEI links need both the clickable component and the client bookmark cache, not just their visible text. */
public final class ClusterItemLinks {

    private ClusterItemLinks() {}

    public static void publish(EntityPlayerMP sender, NBTTagCompound payload) {
        if (!ClusterService.isRunning() || payload == null) return;
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
                player.addChatMessage(message.createCopy());
            }
        } catch (Exception e) {
            CointCore.LOG.warn("[Cluster] Could not receive NEI link from {}", node, e);
        }
    }
}

package coint.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.CointConfig;
import coint.CointCore;
import serverutils.ServerUtilitiesPermissions;
import serverutils.lib.config.ConfigEnum;
import serverutils.lib.config.RankConfigAPI;
import serverutils.ranks.Ranks;

public class ChatUtil {

    private static final Map<String, String> rfnCache = new HashMap<>();

    public static void refreshRFN(EntityPlayerMP player) {
        rfnCache.remove(
            player.getGameProfile()
                .getName());
        getRankFormattedName(player);
    }

    public static ChatComponentText getChatMessage(String senderFormatted, String text, String origin) {
        LocalTime now = LocalTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        String time = now.format(formatter);

        var sep = origin.isEmpty() ? "" : " ";

        var msg = CointConfig.chat.msgFormat.replace("{msg}", text)
            .replace("{name}", senderFormatted)
            .replace("{time}", time)
            .replace("{origin_sep}", origin + sep)
            .replace("{sep_origin}", sep + origin);

        return new ChatComponentText(msg);
    }

    public static ChatComponentText getNotifyMessage(String text) {
        return new ChatComponentText(EnumChatFormatting.YELLOW + "Уведомление: " + EnumChatFormatting.RESET + text);
    }

    public static String getRankFormattedName(EntityPlayerMP player) {
        String plainName = player.getGameProfile()
            .getName();
        var rfn = rfnCache.get(plainName);
        if (rfn != null) return rfn;

        try {
            if (Ranks.INSTANCE == null) {
                return plainName;
            }

            String format = Ranks.INSTANCE.getPlayerRank(player.getGameProfile())
                .getPermission(ServerUtilitiesPermissions.CHAT_NAME_FORMAT);
            if (format.isEmpty()) {
                return plainName;
            }

            format = format.replace("{name}", plainName)
                .replace("<", "")
                .replace(">", "")
                .replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1")
                .replaceAll(":\\s*$", "")
                .trim();

            rfnCache.put(plainName, format);
            return format;

        } catch (Exception e) {
            CointCore.LOG.warn("Failed to get rank format for {}:\n{}", plainName, e.getMessage());
            return plainName;
        }
    }

    public static String getTextColorCode(EntityPlayerMP player) {
        if (Ranks.INSTANCE == null) {
            return "";
        }

        try {
            EnumChatFormatting color = (EnumChatFormatting) ((ConfigEnum<?>) RankConfigAPI
                .get(player, ServerUtilitiesPermissions.CHAT_TEXT_COLOR)).getValue();
            if (color == EnumChatFormatting.WHITE) {
                return "";
            }

            return color.toString();
        } catch (Exception e) {
            CointCore.LOG.warn(
                "Failed to get text color for {}:\n{}",
                player.getGameProfile()
                    .getName(),
                e.getMessage());
            return "";
        }
    }
}
